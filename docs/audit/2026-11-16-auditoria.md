# Auditoría de Atril — 2026-11-16

**Alcance:** rama `develop` en `a204c66` (v0.9.2, versionCode 87). Módulos `core/`, `app/`, `desktop/`, `report-worker/` y workflows de CI.
**Método:** lectura estática del código (~67.000 líneas, 725 tests). No se ha ejecutado la app ni los tests; lo que depende del comportamiento en un dispositivo real va marcado con **⚠ verificar**. Las referencias son `archivo:línea` sobre ese commit.

---

## 1. Resumen ejecutivo

**Estado general.** El proyecto está en buen estado para su tamaño. `:core` es JVM puro, bien aislado y muy probado: sync de saves, extracción segura de archivos, agrupación multidisco, i18n con paridad de claves verificada por test. La seguridad de rutas tiene una sola puerta por operación (`ZipStreamExtractor`, `SafeDelete`, `RootCommandNames`) y está documentada en `AGENTS.md`. La i18n es casi completa: solo quedan 3 cadenas fuera de los bundles.

**Dónde está el riesgo.** No está en el núcleo, sino en las capas añadidas más rápido en las últimas semanas (Play, emuladores, media, reportes, backup) y en el cableado de Android:

1. **Play depende de la red para mostrar juegos ya descargados.** Sin servidor alcanzable puede quedarse en spinner durante minutos, y sin conexión no hay carátulas. Es el fallo de mayor impacto para un launcher de consola portátil.
2. **Hay fallos funcionales deducibles del código** en flujos visibles: añadir un juego local en Android, importar una copia de seguridad con credenciales y los avisos diarios de actualización de emuladores con versión `latest`.
3. **`SettingsDataStore` se instancia unas 25 veces.** Cada instancia abre el Keystore y mantiene su propia copia de la API key, lo que da estado incoherente y coste de arranque.
4. **La cadena de publicación firma con una clave desechable** si faltan los secretos, y el endpoint de reportes confía en una clave que viaja dentro del APK público.
5. **La paridad Android ↔ Linux se ha roto** en todo lo reciente (buscador, favorito con mando, filtros rápidos, fanart, selector de media, reportes, backup), contra lo que exige `CLAUDE.md`.

**Deuda técnica.**

- Ficheros gigantes: `DesktopUi.kt` (2.504 líneas), `LibraryScreen.kt` (2.449, con un único composable de ~1.190), `DesktopAppState.kt` (1.981), `ConfigScreen.kt` (1.445), `PlayScreen.kt` (1.327, con ~25 estados `remember`).
- Lógica duplicada entre `app/` y `desktop/` que no vive en `:core`: `SyncCoordinator` (869) frente a `DesktopSyncCoordinator` (816), `CoverFlow`, `MatchRomDialog`, `UploadGameDialog`, `QrLoginDialog`.
- Estado tipado como cadenas mágicas (`"blur"`/`"solid"`/`"fanart"`, `"favorites"`/`"recent"`, `"cover"`/`"background"`).
- 31 `runBlocking` en `SettingsDataStore` y 14 `OkHttpClient` independientes.
- Tests concentrados en `:core`; las piezas nuevas de `app/` no tienen ninguno. CI sin `lint`.

---

## 2. Problemas concretos

### Prioridad alta

**A1 · Play bloquea la lista de descargados detrás de la red** — `ui/viewmodel/ShelfViewModel.kt:172-306`, `PlayScreen.kt:588`

- La hidratación pide el listado completo de cada plataforma con algún juego descargado (páginas de 500, hasta 10.000) y luego `getRom` por cada id que falte. `_items` no se publica hasta terminar.
- Mientras tanto Play muestra un spinner (`loading && items.isEmpty()`). El cliente usa `connectTimeout` de 30 s (`core/remote/NetworkModule.kt:34`). Con el servidor inalcanzable (fuera de la LAN de casa, caso típico) la espera es 30 s por tanda de 4 peticiones: minutos con 40 juegos.
- `DownloadedRomEntity` no guarda carátula ni metadatos, y `dtoCache` solo vive en memoria. Sin servidor, `buildRom(entity, null, …)` deja las cartas sin portada.
- El `collectLatest` se reinicia con cualquier escritura en DataStore: `settings` (`SettingsDataStore.kt:281-295`) no lleva `distinctUntilChanged`. Pulsar el conmutador carta/medio físico (`PlayScreen.kt:555-570`) o terminar un sync relanza toda la descarga de listados.
- `dtoById` y `dtoCache` son `mutableMapOf` escritos desde varios `async(Dispatchers.IO)` en paralelo (`:199-250`): carrera de datos sobre un `HashMap`.
- **Arreglo:** publicar al instante los ítems desde Room y enriquecer después; persistir carátula y metadatos mínimos por juego (migración 9 o JSON); `distinctUntilChanged` sobre `(serverUrl, apiKey)`; cliente de metadatos con timeout corto (3–5 s); `ConcurrentHashMap`.

**A2 · «Añadir juego local» en Android no ofrece plataformas** — `PlayScreen.kt:200`, `LocalGameUi.kt:58,95`

- `remember { runCatching { container.platformDao.getAllPlatformsBlocking() }.getOrDefault(emptyList()) }` se ejecuta en composición, es decir, en el hilo principal. Room no tiene `allowMainThreadQueries` y el DAO generado pasa por `DBUtil.query`, que lanza `IllegalStateException`. El `runCatching` la oculta y devuelve lista vacía.
- Con la lista vacía, `guessPlatform` devuelve null, el desplegable no tiene opciones y el botón de confirmar queda deshabilitado (`enabled = slug != null`).
- Aun arreglando el hilo, en modo «solo local» la tabla `platforms` está vacía porque se rellena desde RomM.
- **Arreglo:** exponer las plataformas desde `ShelfViewModel` como `Flow`, y añadir un catálogo integrado (el de `LaunchPlatforms`/`EmulatorCatalog`) para cuando no hay RomM. ⚠ verificar en dispositivo.

**A3 · HTTP sin TLS bloqueado sin explicación** — `app/src/main/AndroidManifest.xml:63-71`

- No hay `usesCleartextTraffic` ni `networkSecurityConfig`. Con `targetSdk 35` el valor por defecto es bloquear, y OkHttp lo respeta. Un RomM en `http://192.168.x.x:8080`, el despliegue doméstico más habitual, falla con «CLEARTEXT communication not permitted» y la UI no lo traduce.
- **Arreglo:** decidir la política. Si se permite, usar `network_security_config.xml` y avisar en Conexión al usar `http://`. Si no, detectar el esquema y mostrar un error específico. ⚠ verificar con un servidor `http://`.

**A4 · `SettingsDataStore` múltiple: estado incoherente y Keystore en el hilo principal** — `data/local/SettingsDataStore.kt:127-154`

- Hay unas 25 llamadas `SettingsDataStore(context)` fuera del contenedor: `MainActivity.kt:148,161`, `DownloadManager.kt:55,145`, `DownloadWorker.kt:87,413,422`, `ConfigScreen.kt:995`, `DeviceReportCollector.kt:20`, workers y servicios.
- Cada instancia construye `MasterKey` y `EncryptedSharedPreferences` y lanza una corrutina de migración. `MainActivity.onCreate` lo hace dos veces más en el hilo principal durante el arranque.
- Cada instancia tiene su propio `_apiKeyFlow`. El caso visible: `BackupManager` se crea con una instancia nueva (`ConfigScreen.kt:992-997`), así que al importar una copia con credenciales (`BackupManager.kt:62-63`) la API key se guarda pero el contenedor sigue viendo la antigua hasta reiniciar. Tampoco se llama a `configureApi`.
- `EncryptedSharedPreferences.create` no tiene `try/catch` (`:135-141`). Un Keystore corrupto, fallo conocido de esta librería (ya obsoleta, `security-crypto 1.1.0-alpha06`), tumba `Application.onCreate` en bucle.
- **Arreglo:** una sola instancia vía `container.settingsDataStore`; prefs seguras `lazy` con recuperación (borrar el fichero y pedir la clave de nuevo); planificar la salida de `security-crypto`.

**A5 · Las releases pueden salir firmadas con una clave desechable** — `.github/workflows/build-release.yml:76-105`, `develop.yml:51-78`

- Si falta `SIGNING_KEYSTORE_BASE64`, el workflow genera un keystore aleatorio y publica igualmente la release pública. Ese APK no se instala sobre el anterior por firma distinta, y el texto de la release promete lo contrario (`:152`).
- **Arreglo:** en `master`, fallar el job si faltan los secretos y dejar el fallback solo en develop. Publicar el SHA-256 de cada artefacto en la release.

**A6 · Endpoint de reportes abusable y con riesgo de filtrar datos** — `report-worker/src/index.js`, `app/build.gradle.kts:42-49`

- `REPORT_KEY` se compila en `BuildConfig` de un APK público, así que cualquiera la extrae. Si `ATRIL_KEY` no está definida en el worker, el endpoint queda abierto (`index.js:15`).
- El único freno es 5 reportes por hora e IP (`:65-72`). El texto se publica tal cual en issues públicas (hasta 16.000 caracteres, Markdown y `@menciones` incluidos, `:30-38`).
- El diagnóstico incluye la última traza (`DeviceReportCollector.kt:52-58`). Los mensajes de excepción pueden llevar el host del servidor o rutas, aunque el KDoc diga que nunca se envían.
- En builds locales la clave va vacía: el worker responde 403 y el diálogo solo muestra el error (`ReportIssueDialog.kt:160-163`). La ruta de respaldo `openGitHub()` no se alcanza nunca porque `REPORT_URL` tiene valor por defecto.
- **Arreglo:** en el worker, neutralizar `@`, meter el diagnóstico en bloque de código, añadir tope global diario y deduplicar por hash. En la app, limpiar URLs y rutas de la traza y ofrecer «Abrir en GitHub» cuando el envío falle.

### Prioridad media

**M1 · Descargas: parcial con nombre final y reintentos sin verificar** — `download/DownloadWorker.kt:250-254`, `DownloadManager.kt:122-164`

- El parcial se escribe directamente en `<plataforma>/<fichero>`, sin `.part`. Un ROM truncado es indistinguible de uno completo para ES-DE o para el escaneo de biblioteca.
- `retryDownload` no pasa `KEY_FILE_HASH` y fija `KEY_PLATFORM_ID = 0`. El reintento no verifica integridad y guarda la fila con `platformId = 0`, que `RomDao.kt:13` no encuentra.
- `enqueueDownload` hace `runBlocking` y crea un `SettingsDataStore` por ROM (`:53-58`). «Descargar faltantes» (`LibraryViewModel.kt:368-371`) lo repite N veces en el hilo principal: riesgo de ANR con cientos de juegos.
- **Arreglo:** escribir en `.part` y renombrar de forma atómica; conservar hash e id en el reintento; leer `wifiOnly` una sola vez.

**M2 · Vigilancia de saves frente al límite de Android 15** — `data/sync/SaveSyncWatchService.kt:115-117`, manifest `:110-113`

- Es un servicio en primer plano permanente de tipo `dataSync`, que Android 15 limita a 6 h al día. `onTimeout` hace `stopSelf()` y `BootReceiver` asume que puede fallar, pero la UI no informa de nada: la vigilancia se apaga en silencio.
- **Arreglo:** mostrar en la pantalla Sync el estado real («pausada por el sistema hasta abrir Atril») y evaluar activarla solo alrededor de una partida.

**M3 · Emuladores: estado efímero, errores silenciados y avisos falsos**

- Todo el estado vive en `remember` (`ui/screens/EmulatorsScreen.kt:95-121`). Salir de la pantalla cancela la descarga del APK y pierde la cola de instalación.
- `checkUpdates` (`:218-233`) y `latestRelease` (`data/emulators/EmulatorRepository.kt:130-133`) descartan los errores. Sin red, o con el límite anónimo de GitHub (60 peticiones/hora, una por emulador instalado) agotado, se muestra igualmente «actualizaciones comprobadas».
- Las fuentes HTML, JSON o APK directo sin regex de versión devuelven `version = "latest"` (`HtmlReleaseResolver.kt:36,57,96`). `VersionCompare.isNewer("latest", x)` siempre es true (`VersionCompare.kt:12`), así que `EmulatorUpdateWorker.kt:40-48` notifica una actualización cada día para siempre.
- No se comprueba que el paquete del APK descargado coincida con el esperado. El pack se lee de `main` sin fijar versión (`EmulatorRepository.kt:184-185`) y el nombre del fichero sale de la URL sin sanear (`:141`).
- `refreshInstalled` y `learnPackageName` llaman a `PackageManager` en el hilo principal (`:123-125,147`); el segundo parsea un APK de decenas de MB.
- **Arreglo:** `EmulatorsViewModel` con descarga en WorkManager; propagar errores; caché con ETag; tratar `latest` como «desconocida» comparando tamaño o `Last-Modified`; validar paquete y sanear el nombre.

**M4 · Actividad HOME sin `singleTask` ni `onNewIntent`** — manifest `:73-89`, `MainActivity.kt:152-156`

- Los extras de las notificaciones solo se leen en `onCreate`. Con `launchMode` estándar y dos intent-filters, pulsar Home con la app abierta puede apilar otra instancia, y nunca devuelve a Play ni cierra los overlays, que es lo esperable de un launcher.
- **Arreglo:** `launchMode="singleTask"` y `onNewIntent` (Home lleva a Play y cierra menús; los extras navegan). ⚠ verificar.

**M5 · El primer frame compone Ajustes** — `ui/navigation/RomMSyncApp.kt:73-82,184-189`

- `settings` arranca con un `ServerConfig` vacío, así que `needsOnboarding` es true y `startDestination` es Config. Al llegar DataStore, el grafo se reconstruye hacia Play: se crea y destruye `ConfigScreen` en cada arranque en frío.
- **Arreglo:** estado inicial `null` y mantener la splash hasta la primera emisión.

**M6 · Play pierde el contexto al volver** — `PlayScreen.kt:188-189,271-275`

- `platformFilter` y `quickFilter` son `remember` simples, mientras el pager es `rememberSaveable`. Además `LaunchedEffect(platformFilter, quickFilter) { scrollToPage(0) }` corre también en la primera composición.
- Volver de Descargas o Biblioteca devuelve siempre al primer juego y sin filtro, y el arranque en frío nunca abre en el último jugado.
- **Arreglo:** `rememberSaveable` o estado en el ViewModel, saltar el efecto inicial y recordar el último juego seleccionado.

**M7 · Recomposición de toda la pantalla en las dos animaciones clave** — `PlayScreen.kt:259-267,651-659,834-842`

- `insertProgress.value` se lee en el cuerpo de `PlayScreen`: los ~900 líneas del composable se recomponen en cada frame de la inserción.
- `pagerState.currentPageOffsetFraction` se lee en la composición de cada página, no dentro de `graphicsLayer`.
- `visibleItems` se filtra y agrega en cada recomposición sin `remember`.
- **Arreglo:** pasar `progress` como lambda, mover las lecturas al bloque `graphicsLayer` y usar `remember`/`derivedStateOf`.

**M8 · Fanart y selector de media**

- `FanartStore.get` (`data/media/FanartStore.kt:28-36`) no guarda los fallos ni espera a que el carrusel se detenga. Recorrer el carrusel lanza 2 llamadas a SteamGridDB por cada juego sin resultado, en cada pasada.
- Se toma siempre el primer resultado de la búsqueda (`:42`, `MediaCatalog.kt:76`), sin opción de corregir el título.
- Al elegir un fondo se descarga la imagen dos veces y la «temporal» queda para siempre en `filesDir/media-picks` (`PlayScreen.kt:959-968`, `MediaCache.kt:40-44`).
- El fondo nuevo no se ve hasta cambiar de juego, porque el `produceState` solo depende del id (`PlayScreen.kt:429-441`).

**M9 · Paridad con escritorio y secretos**

- `desktop/.../GamepadInput.kt:102-107` deja Start (buscar) y X (favorito) sin acción.
- En `desktop/` no existen los filtros rápidos, el buscador de Play, el fondo fanart, el fondo por juego, el selector de media, los reportes ni el backup.
- En escritorio la API key y la contraseña de ScreenScraper van en `java.util.prefs` en claro (`DesktopConfig.kt:20-22,151-162`). En Android la API key va cifrada, pero las credenciales de scraper están en DataStore en claro (`SettingsDataStore.kt:98-100`).

**M10 · Backup incompleto y con falso éxito** — `data/backup/BackupManager.kt:22-53`, `ConfigScreen.kt:1005-1011`

- Solo exporta tema, fondo, blur, idioma y emuladores manuales. Faltan rutas, ajustes de sync, favoritos y tiempo jugado, juegos locales, fondos por juego y overrides de emulador o core.
- La exportación muestra «exportado» aunque la escritura falle: el `runCatching` no se consulta.
- La importación no comprueba `version`.

**M11 · Tests y CI**

- Sin tests: `ShelfViewModel`, `DownloadWorker`, el `SyncCoordinator` de Android, `EmulatorRepository`, `HtmlReleaseResolver`, `BackupManager`, `MediaCatalog`, `FanartStore`. No hay tests de UI.
- CI no ejecuta `lint`. `ci.yml` solo corre en PR, pero el flujo real fusiona en local, así que los tests corren después del push a `develop`.
- Dependencias de finales de 2024: Kotlin 2.1.0, AGP 8.7.3, Compose BOM 2024.12, Coil 2.7.
- La versión está duplicada a mano en `app/build.gradle.kts:38` y `DesktopConfig.kt:213`.

### Prioridad baja

- **B1 · Cadenas fuera de i18n:** `ShelfViewModel.kt:325` («No se pudo borrar…»), manifest `:96` (`android:label="Sync saves"`), `DownloadManager.kt:179` («Unknown»).
- **B2 · Código muerto:** imports de `NavigationBar`/`NavigationRail` y `isSelected` en `RomMSyncApp.kt:30-35,227-231`; `fadeEdges` en `PlayScreen.kt:1248`; comentarios que aún hablan de rail y barra inferior.
- **B3 · Sesión de juego solo en memoria** (`core/playstats/PlaySessionTracker.kt:19-20`): si Android mata Atril mientras corre el emulador, se pierden el tiempo jugado y la «última partida». Persistir el inicio de sesión.
- **B4 · Ids sintéticos por `hashCode`** (`ShelfViewModel.kt:354-355`): una colisión entre dos juegos locales duplica la `key` de los `LazyColumn` (`PlayScreen.kt:1305`) y provoca un crash. Asignar ids estables.
- **B5 · Límite de 8 KB por valor en `java.util.prefs`:** los CSV de ids (`DesktopConfig.kt:203-210`) lanzan excepción a partir de ~1.300 juegos.
- **B6 · Escrituras con carrera** en `setInstalledLink` y `setGameBackground` (`SettingsDataStore.kt:677-707`): leen con `runBlocking` fuera de la transacción `edit`.
- **B7 · Botones del mando activos con diálogos abiertos** (`PlayScreen.kt:398-416`): X marca favorito con el buscador o el detalle abiertos.
- **B8 · Escritorio copia la lista entera por página** (`DesktopAppState.kt:417-418`) y no tiene caché de biblioteca en disco.
- **B9 · Nombres heredados** (`RomMSyncApplication`, `RomMSyncApp`, `Theme.RomMSync`): renombrar clases, no los ficheros de datos.

---

## 3. Mejoras de diseño (UI/UX)

**Jerarquía en Play**

1. **Línea de información en horizontal.** `Caption` solo se pinta en vertical (`PlayScreen.kt:747-761`). En horizontal, la orientación nativa de una portátil, no se ven metadatos, última partida, tiempo jugado ni emulador; con medio físico tampoco hay título. Propuesta: franja inferior de una línea (título · metadatos · «jugado hace 2 días») a la izquierda, compartiendo fila con la leyenda del mando.
2. **Abrir en el último juego jugado** y ofrecer orden por «última partida», «más jugado» y «añadido recientemente» (`downloadedAt` ya existe). La fila «Seguir jugando» desaparece en horizontal y hoy no tiene sustituto.
3. **Estado vacío por filtro.** Con Favoritos o Recientes sin resultados se muestra `EmptyLauncher` («abre la Biblioteca», `PlayScreen.kt:593`). Debe decir «Aún no tienes favoritos · pulsa X sobre un juego» y ofrecer «Quitar filtro».

**Navegación con mando**

4. **Foco visible en toda la app.** `focusRing()` solo se usa en `PlayScreen` y `LauncherComponents`. Aplicarlo a `LauncherPill`, los botones redondos de cabecera, `RomCard`, las celdas del selector de media, los resultados del buscador y las filas de Emuladores y Apps.
5. **Menú lateral.** El resaltado por `focusedIndex` (`LauncherComponents.kt:779-804`) no desplaza el `verticalScroll` (`:858`): con 7 entradas en una pantalla baja el ítem activo queda fuera de vista. Añadir desplazamiento al ítem, salto circular y bloqueo real del foco, que hoy puede escapar al contenido de detrás.
6. **Leyenda contextual.** Solo existe en Play, y los glifos «▶» y «≡» no se leen como Start y Select; usar píldoras con texto. Llevarla a Biblioteca, detalle y Emuladores, y ocultarla si no hay mando conectado.
7. **Buscador a pantalla completa** con miniaturas, navegable con D-pad y sobre todos los juegos (hoy busca en `visibleItems`, por lo que hereda el filtro activo), más salto por letra con L2/R2.
8. **Saltar la animación de inserción con A.** Hoy se espera siempre la duración completa antes de lanzar (`PlayScreen.kt:336-340`).

**Estados de carga, vacío y error**

9. **Play sin red:** cartas esqueleto (ya existe `SkeletonGrid`) y un aviso discreto «Sin conexión con RomM · datos locales», en lugar del spinner.
10. **Selector de media sin resultados:** distinguir «faltan credenciales» (con acceso directo a Ajustes) de «sin coincidencias» (con campo para editar el título).
11. **Emuladores:** estado de error con «Reintentar» cuando falla el pack o GitHub limita, e insignia en el menú con el número de actualizaciones.

**Consistencia**

12. **Un solo componente de chip.** Play usa `LauncherPill` y Emuladores `FilterChip` (`EmulatorsScreen.kt:307-321`). Lo mismo con los snackbars: Play lleva estilo propio y el resto el del `Scaffold`.
13. **Fila de filtros de Emuladores.** Mezcla categoría, «instalados» y perfil de pantalla en 6 chips. Mover el perfil (una o dos pantallas) a Ajustes y persistirlo: hoy se reinicia en cada visita.
14. **Primer arranque guiado** en 3 pasos (conectar RomM o solo local → permiso de almacenamiento con su porqué → carpeta de ROMs), en vez de aterrizar en Ajustes.

**Animaciones**

15. **Respetar «quitar animaciones» del sistema.** No hay ninguna lectura de la escala de animación. Añadir en Ajustes «Animación de inserción: completa / rápida / desactivada».

**Accesibilidad**

16. Las zonas táctiles laterales del carrusel (`PlayScreen.kt:701-726`) son `clickable` sin etiqueta.
17. Las píldoras usan `Role.Tab` sin estado `selected` y miden 36 dp de alto (`LauncherComponents.kt:742`), por debajo de los 48 dp recomendados.
18. El carrusel no anuncia «juego X de N».
19. Revisar el contraste con `CRIMSON_LIGHT`: el texto usa `onBackground` sobre una imagen con velo de `background` al 42 % en la parte superior (`CinematicHero.kt:85-87`). ⚠ verificar ratio AA.

---

## 4. Mejoras funcionales

| # | Mejora | Valor para el usuario |
|---|---|---|
| F1 | **Caché local de metadatos y carátulas** de lo descargado (resuelve A1) | Play instantáneo y completo sin red |
| F2 | **Catálogo de plataformas integrado** para el modo solo local (resuelve A2) | Atril utilizable sin RomM |
| F3 | **Descargas robustas:** `.part`, verificación en reintentos, espacio libre visible al elegir destino, pausar y reanudar todo | Menos ROMs corruptos en silencio |
| F4 | **Emuladores:** «Actualizar todo», descarga en segundo plano con notificación, caché con ETag, token opcional de GitHub, validación de paquete | Flujo fiable; menos límites de la API |
| F5 | **Backup completo** (ajustes, favoritos y tiempo jugado, juegos locales, fondos, overrides), con versión de esquema, y también en escritorio | Migrar de dispositivo sin reconfigurar |
| F6 | **Selector de media:** título editable, elegir carátula también para juegos de RomM (hoy solo fondo), «restaurar original», resolución visible | Arreglar coincidencias erróneas |
| F7 | **Paridad en escritorio:** buscador (Start / Ctrl+F), favorito (X / F), filtros rápidos, fanart y fondo por juego, reportes | Misma experiencia en Linux y Steam Deck |
| F8 | **Comportamiento de launcher real:** Home vuelve a Play; reloj, batería y Wi-Fi en la cabecera (Play oculta la barra de estado) | Usable como pantalla de inicio |
| F9 | **Sesiones de juego persistentes** (resuelve B3) | Estadísticas fiables |
| F10 | **Reportes:** respaldo a GitHub si falla el envío, copiar diagnóstico, incluir el último resumen de sync | Los reportes llegan y son útiles |
| F11 | **Estado de la vigilancia de saves** visible, con motivo de pausa | Menos saves sin sincronizar por sorpresa |
| F12 | **Verificación de actualizaciones:** SHA-256 publicado y comprobado antes de abrir el instalador | Confianza en la autoactualización |

**Simplificaciones**

- Plataformas como subpantalla de Biblioteca o Ajustes, en vez de entrada propia del menú (hoy son 7).
- Una sola fuente de versión para `app` y `desktop`.
- Enums compartidos en `:core` para fondo del launcher, filtros rápidos y tipo de media.
- Un `OkHttpClient` base compartido, con `newBuilder()` por uso.

**Exploratorio (sin compromiso):** colecciones de RomM como filtros en Play; sincronizar favoritos y tiempo jugado entre dispositivos (el JSON de `play_stats` ya es común a ambas plataformas).

---

## 5. Plan de implementación por fases

Esfuerzo relativo: **S** ≤ 1 día · **M** 2–4 días · **L** ≥ 1 semana. Cada ítem va en su `feat/<slug>`, verificado en Android y Linux cuando afecte a la UI.

### Fase 1 — Alto valor, bajo riesgo

| Ítem | Ref. | Esf. |
|---|---|---|
| Plataformas vía ViewModel y catálogo integrado para juegos locales | A2, F2 | S |
| Play: publicar los ítems de Room al instante, `distinctUntilChanged`, `ConcurrentHashMap`, timeout corto | A1 (parcial) | M |
| Instancia única de `SettingsDataStore`; arreglar la importación de backup y su falso «exportado» | A4, M10 | M |
| CI: fallar en `master` sin secretos de firma, publicar SHA-256, añadir job de `lint` | A5, M11 | S |
| Worker: neutralizar `@`, bloque de código, tope diario; respaldo a GitHub en la app | A6, F10 | S |
| Emuladores: `latest` no cuenta como más nueva; propagar errores de red y de límite | M3 | S |
| Decidir la política de HTTP en claro y dar un error explicativo | A3 | S |
| Play: filtros en `rememberSaveable`, no volver a la página 0 al entrar, lecturas diferidas en animaciones | M6, M7 | S |
| Fanart: no repetir búsquedas fallidas y esperar ~400 ms; quitar la doble descarga; refrescar el fondo al guardar | M8 | S |
| `focusRing` en píldoras, cabecera, rejilla, selector y buscador; estados vacíos por filtro | UX 3, 4 | M |
| Cadenas fuera de i18n, código muerto, botones del mando con diálogos abiertos | B1, B2, B7 | S |

### Fase 2 — Robustez

| Ítem | Ref. | Esf. |
|---|---|---|
| Caché persistente de metadatos y carátulas (migración Room 9 + tests) | F1 | M–L |
| Descargas: `.part` con renombrado atómico, hash e id en reintentos, encolado sin `runBlocking` | M1, F3 | M |
| `EmulatorsViewModel` + WorkManager, ETag, validación de paquete, perfil persistido | M3, F4 | M |
| `singleTask` + `onNewIntent`; puerta de arranque con splash; primer arranque guiado | M4, M5, UX 14 | M |
| Línea de información en horizontal; leyenda contextual; menú con desplazamiento y foco bloqueado | UX 1, 5, 6 | M |
| Estado visible de la vigilancia de saves y estrategia para Android 15 | M2, F11 | M |
| Sesión de juego persistente; ids estables para juegos locales | B3, B4 | S |
| Tests de `ShelfViewModel`, `DownloadWorker`, `HtmlReleaseResolver`, `BackupManager` | M11 | M |

### Fase 3 — Paridad y deuda estructural

| Ítem | Ref. | Esf. |
|---|---|---|
| Escritorio: buscador, favorito, filtros rápidos, fanart y fondo por juego, selector de media, reportes, backup | M9, F7 | L |
| Extraer el coordinador de sync común a `:core` (Android y escritorio como adaptadores) | Deuda | L |
| Trocear `PlayScreen`, `LibraryScreen`, `ConfigScreen`, `DesktopUi`, `DesktopAppState`; enums en `:core` | Deuda | L |
| Backup completo con versión de esquema | F5 | M |
| Actualizar Kotlin, AGP, Compose BOM y Coil; sustituir `security-crypto`; fuente única de versión | M11, A4 | M |
| Secretos en escritorio (keyring o fichero 0600) y credenciales de scraper cifradas en Android | M9 | M |

### Fase 4 — Pulido y exploración

| Ítem | Ref. | Esf. |
|---|---|---|
| Buscador a pantalla completa con salto por letra; orden configurable en Play | UX 2, 7 | M |
| Ajuste de animaciones y respeto a la escala del sistema; saltar inserción | UX 8, 15 | S |
| Reloj, batería y Wi-Fi en la cabecera de Play; Home vuelve a Play | F8 | M |
| Accesibilidad: semántica del carrusel, objetivos de 48 dp, contraste en tema claro | UX 16–19 | M |
| Tests de captura de pantalla para Play y Biblioteca en ambos módulos | M11 | M |
| Colecciones de RomM; sync de favoritos entre dispositivos | Exploratorio | L |

---

## Seguimiento

**Fase 1** (merge `31bbe2e`, `e4e3109`, `e341489`, `ab85bcc`): A1 (parcial: sin caché persistente), A2, A3, A4, A5, A6, M3 (parcial), M6, M7, M8, B1, B6, B7, UX 3–4.

**Fase 2** (`feat/audit-fase2`):

- Revisión de la Fase 1: portada local desde el selector que no se guardaba (stream cerrado antes de leerse), último juego perdido al recrear Play, estado vacío por filtro con la estantería vacía.
- M1/F3: `.part` + renombrado atómico (Android y escritorio), identidad completa en reintentos (antes `romId = -1`), encolado sin `runBlocking`.
- M4: `singleTask` + `onNewIntent` (Home → Play sin overlays; notificaciones navegan).
- M2/F11: estado real de la vigilancia de saves en Sync; se reanuda al abrir Atril; `startForeground` denegado ya no tumba el proceso.
- B3/F9: sesión de juego persistida (diario en disco), tope de 8 h por sesión.
- B4: ids de juegos locales persistidos y únicos, compatibles con los anteriores.
- UX 4/11: estado de error con «Reintentar» en Emuladores; `focusRing` en Emuladores y en el buscador.

**Pendiente:** F1 (caché persistente de metadatos y carátulas), M3 resto (`EmulatorsViewModel` + WorkManager, ETag, validación de paquete), M5, M9/F7 (paridad de escritorio), M10/F5, M11 resto (tests de `ShelfViewModel`, `HtmlReleaseResolver`, `BackupManager`), UX 1–2, 5–19, B2, B5, B8, B9. Verificación en dispositivo de M4 y M2 (ver anexo).

## Anexo — Elementos a verificar en dispositivo

1. **A2:** abrir «+» en Play (Android), elegir un fichero y comprobar si el desplegable de plataforma tiene opciones.
2. **A3:** conectar a un RomM `http://` en LAN y anotar el mensaje de error.
3. **A1:** con juegos descargados, poner una IP de servidor inalcanzable y medir cuánto tarda Play en mostrar algo.
4. **M4:** abrir desde el icono, navegar a Biblioteca y pulsar Home con Atril como launcher; revisar `adb shell dumpsys activity activities` por si hay instancias duplicadas.
5. **M3:** instalar un emulador de fuente HTML o APK directo y comprobar la notificación diaria.
6. **UX 19:** contraste del texto de Play con `CRIMSON_LIGHT` y fondo fanart.
7. **M2:** con la vigilancia activa en Android 15, agotar el límite (reduciéndolo con `device_config` según la guía de Android 15 sobre el timeout de `dataSync`, o esperando 6 h) y comprobar el estado en Sync y su reanudación al abrir Atril.
8. **M1:** cortar la red a mitad de descarga y comprobar que solo existe `<fichero>.part`; reintentar desde Descargas y verificar que reanuda y renombra.
