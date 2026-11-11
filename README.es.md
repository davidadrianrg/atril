<div align="center">

# 🎮 Atril — Game launcher con integración RomM

🌐 [English](README.md) · **Español**

**Un launcher de juegos para Android y Linux con integración [RomM](https://github.com/rommapp/romm).**

Atril convierte tus ROMs descargadas en un launcher con estética de medio físico: cartas coleccionables, arte real de cartucho o disco cuando RomM lo tiene y una animación de inserción antes de arrancar el juego. También explora tu biblioteca RomM, descarga ROMs en las carpetas de tu frontend y mantiene las partidas sincronizadas entre Android, Steam Deck y PC.

- **Android** (Kotlin + Jetpack Compose): APK nativo, optimizado para consolas portátiles (Anbernic, Retroid Pocket...) con pantalla táctil: UI oscura, botones grandes y navegación pensada para dedos.
- **Linux** (Compose Desktop): AppImage autocontenido para **x86_64** (SteamOS, Steam Deck, PCs) y **aarch64** (Raspberry Pi 5, handhelds ARM) con runtime Java incluido — nada que instalar.

</div>

---

## 📥 Instalación

### Opción A — Android (recomendado)

> **Atril 0.9.0 cambia el applicationId de Android a `com.davidadrianrg.atril`.**
> Se instala como una app nueva: si vienes de RomM Sync, descarga e instala este APK manualmente. La app antigua no puede autoactualizarse al paquete nuevo; configura Atril una vez y elimina la instalación antigua cuando quieras.

1. Descarga `Atril-vX.Y.Z-android.apk` desde [GitHub Releases](https://github.com/davidadrianrg/atril/releases/latest).
2. Configura tu servidor (ver abajo).
3. En **Configuración → Actualizaciones**, pulsa *Buscar actualizaciones* cada vez que quieras comprobar si hay versión nueva. La app descarga el APK y lanza el instalador de Android — sin salir de la aplicación.

> ⚠️ La primera vez, Android pedirá conceder a Atril el permiso **«Instalar apps desconocidas»**. Es el permiso estándar para auto-actualizarse cualquier app fuera de Play Store.

### Opción B — Linux (AppImage)

1. Descarga el AppImage de tu arquitectura desde [GitHub Releases](https://github.com/davidadrianrg/atril/releases/latest):
   - `Atril-vX.Y.Z-linux-x86_64.AppImage` — SteamOS, Steam Deck, la mayoría de PCs
   - `Atril-vX.Y.Z-linux-aarch64.AppImage` — Raspberry Pi 5, handhelds ARM
2. Dale permisos de ejecución y ejecútalo:
   ```bash
   chmod +x Atril-v*-linux-*.AppImage
   ./Atril-v*-linux-x86_64.AppImage
   ```
3. La primera vez pide URL del servidor, API Key y carpeta de ROMs; se guarda para la siguiente.

> El AppImage lleva el runtime Java jlinkeado dentro (~70 MB): no necesitas instalar Java ni nada más.

### Opción C — Compilar desde código

```bash
git clone https://github.com/davidadrianrg/atril.git
cd atril
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

Requisitos: JDK 17 y Android SDK (API 35). Para el desktop: solo JDK 17 (`./gradlew :desktop:packageReleaseUberJarForCurrentOS`). Para firmar tu propio release, crea `keystore.properties` en la raíz (está en `.gitignore`):

```properties
storeFile=/ruta/a/tu.keystore
storePassword=***
keyAlias=***
keyPassword=***
```

## ⚙️ Configuración inicial (2 minutos)

| Qué | Valor |
|---|---|
| **URL del servidor** | `https://romm.tudominio.com` (tu instancia RomM 4.9+ para sync) |
| **API Key** | Token `rmm_...` desde *RomM → User → API Keys* |
| **Directorio de ROMs** | `/storage/emulated/0/ROMs` (o tu microSD) |

La app pide el permiso **«Acceso a todos los archivos»** — lo necesita para escribir las ROMs en las carpetas que espera tu emulador/frontend, sin copiar ni mover nada manualmente.

<details>
<summary><b>¿Por qué API Key y no usuario/contraseña?</b></summary>

RomM admite login OAuth/OIDC tras proxys (Authentik, Authelia...), que suele romper clientes no-web con redirecciones y tokens CSRF. La API Key es un token largo por usuario que funciona siempre, sea cual sea tu setup de autenticación web.
</details>

## ✨ Qué hace la app

- **📚 Biblioteca** — Navega por tu servidor RomM con carátulas, búsqueda y filtros (todos / faltantes / descargados / favoritos). Detecta juegos ya presentes en disco con *Escanear biblioteca*.
- **Vistas de la Biblioteca** — Alterna entre la cuadrícula clásica y un *Carrusel 3D* opcional: carátulas inclinadas en 3D con un reflejo sutil abajo, que se recorren con el mando o el teclado. La elección se recuerda entre sesiones y la carga con esqueleto y el scroll infinito funcionan en las dos vistas.
- **Opciones de vista** — El botón de ajustes de la barra de la Biblioteca (R1 con mando, R en el escritorio) abre un panel contextual — hoja inferior en Android, panel flotante en el escritorio — con *Orden* (nombre, jugado recientemente o año, cuando la lista tiene fechas de lanzamiento), *Origen* (todos, local o RomM), *Vista* (cuadrícula o carrusel 3D), *Tamaño* (pequeño, mediano o grande) y *Estilo de carátula* (caja, medio físico o captura). Los cambios se aplican al instante, el panel sigue abierto y los valores se guardan para la próxima sesión. El menú de orden de la barra mantiene la lista completa de criterios, incluido jugado recientemente. El estilo de carátula elegido se usa solo en la cuadrícula, en el carrusel 3D y en la ficha del juego; el carrusel de Jugar mantiene el arte de caja en las dos plataformas. Cuando un juego no tiene medio físico ni captura, se muestra su carátula de caja. Como la Biblioteca lista juegos de RomM, al elegir *Origen: Local* se muestra una explicación de que los juegos locales están en la pantalla Jugar.
- **Pantalla Jugar cinemática** — El juego seleccionado se pinta detrás del carrusel de Jugar: su primera captura (o su carátula grande si no tiene capturas), reducida y oscurecida con un velo, con un fundido cruzado de 350 ms al cambiar de juego; en Android 12 y posterior también se difumina. Encima aparecen un título más grande, una línea de metadatos (año, hasta tres géneros, plataforma y nota sobre 100), una línea menor con tamaño y discos cuando existen, y la fecha de la última partida. En el escritorio la ficha del juego usa el mismo fondo tras un panel de cristal translúcido, y la ficha de Android usa la misma superficie de cristal. La misma línea de metadatos se usa en las dos fichas, donde sustituye al texto separado de plataforma y, en el escritorio, a la fila de nota independiente.
- **Índice A-Z** — Con más de 40 juegos, la Biblioteca muestra una barra de letras en el borde derecho (A-Z y # para números y símbolos). Indexa el resultado filtrado, así que respeta la búsqueda y los filtros, y salta a juegos ya cargados; reserva su propio espacio y se atenúa cuando está inactiva en vez de tapar las cartas.
- **Favoritos** — Marca un juego con la estrella desde su ficha (Android y Linux) para tenerlo a mano: su carta muestra una insignia de estrella y las dos bibliotecas ganan un filtro *Favoritos* junto a todos / faltantes / descargados.
- **Tiempo jugado y última partida** — Atril cronometra cada partida y muestra *Última partida* y *Tiempo jugado* en la ficha del juego («hace 5 min», «ayer», «1 h 23 min»), con «Nunca» mientras no lo hayas jugado. Las sesiones de menos de 5 segundos no cuentan. Los favoritos y el tiempo jugado se guardan en el dispositivo, por juego: no se sincronizan con tu servidor RomM.
- **Seguir jugando** — La pantalla Jugar muestra arriba, bajo los filtros de plataforma, una fila compacta con los cinco juegos jugados más recientemente (carátula, nombre y tiempo jugado); toca uno para saltar a él en el carrusel. La fila se oculta mientras no hay datos de partida.
- **Leyenda del mando (solo Android)** — En la pantalla Jugar, una barra discreta y translúcida indica qué hacen ahora A, B, X e Y: Jugar, Atrás, Favorito y Detalles. No captura toques y Jugar sigue siendo inmersiva.
- **Juegos locales multidisco** — Los archivos locales con marca de disco como `(Disc 1)`, `(Disk 2)`, `(CD 3)` o `(Disc 1 of 4)` se agrupan en una sola entrada de Jugar con una insignia de *N discos* en Android y Linux. La ficha permite elegir el disco (el 1 por defecto, o el `.m3u` hermano si existe) y Atril recuerda la elección por juego; al lanzar se usa el disco elegido y se sigue prefiriendo el `.m3u`. Los juegos de RomM y los nombres parecidos sin marca de disco no se tocan.
- **⬇️ Descargas** — Cola con descargas paralelas configurables (1–5), reanudación automática si se corta la red, verificación de integridad por hash y aviso de espacio insuficiente. Opción *solo WiFi* para no gastar datos.
- **🔔 Notificaciones de actualización** — comprueba GitHub al abrir la app y una vez al día; envía una notificación cuando hay una versión nueva de Atril.
- **💾 Sincronización de saves** — Tus partidas guardadas siempre al día en todos los dispositivos, con el [Device Sync Protocol](https://docs.romm.app/latest/developers/device-sync-protocol/) de RomM (v4.9+): sube lo que cambió en el portátil, bájate lo que jugaste en el PC. Resuelve conflictos explícitamente en vez de sobrescribir. Antes de lanzar un juego de RomM con la sincronización de saves activada, Atril descarga primero las partidas nuevas o más recientes del servidor con un tope duro de 8 segundos: si el servidor va lento o no responde, el lanzamiento sigue adelante igualmente.
- **🗂️ Exportación de metadatos** — Opción genérica «Descargar media con los juegos» (covers, capturas, vídeo y manual en `<ROMs>/downloaded_media/<plataforma>/…` + un JSON de metadatos por juego, utilizable desde cualquier frontend) y exportación de `gamelist.xml` para ES-DE sobre esa misma media.
- **🔄 Auto-actualización** — Comprueba, descarga e instala nuevas versiones desde GitHub Releases sin desinstalar nada (ver Instalación).
- **🖥️ Linux a la par de Android** — La app de escritorio comparte tema ("Midnight Arcade"), biblioteca con carátulas y detalle de juego completo (screenshots, metadatos IGDB), cola de descargas con cancelar/reintentar/reanudar, sync de saves con resolución de conflictos y exportación ES-DE con merge no destructivo.

### Controles de la Biblioteca

| Acción | Android (mando) | Linux (teclado) |
|---|---|---|
| Abrir las opciones de vista | R1 | R o Av Pág |
| Elegir orden, origen, vista, tamaño o estilo de carátula | Cruceta y A en el panel | Flechas y Enter |
| Carrusel 3D: juego anterior / siguiente | Cruceta izquierda / derecha | Izquierda / derecha |
| Carrusel 3D: abrir la ficha | A | Enter |
| Barra de letras: entrar | Abajo, al final de la cuadrícula (o en cualquier momento en el carrusel) | Abajo, igual |
| Barra de letras: mover, saltar y salir | Izquierda / derecha, A, Arriba | Izquierda / derecha, Enter, Arriba |

R1 abre las opciones de vista solo si el buscador no tiene el foco y no hay una ficha abierta. En la pantalla Jugar, L1 y R1 siguen cambiando el filtro de plataforma.

### Emuladores soportados para sync de saves

| Plataforma | Emulador (por defecto) |
|---|---|
| Retro (NES–N64, GBA, PSX...) | RetroArch |
| Nintendo DS | melonDS |
| PSP | PPSSPP |
| PS2 | AetherSX2 / NetherSX2 |
| GameCube / Wii | Dolphin |
| 3DS | Azahar |
| Wii U | Cemu |
| Switch | Eden |
| Juegos nativos Android | ruta configurable por juego |

> 💡 PS2 requiere activar **Folder Memory Card** en el emulador para sincronizar por juego.

## 🖼️ Permisos que usa y por qué

| Permiso | Motivo |
|---|---|
| Acceso a todos los archivos | Escribir ROMs/metadata en las carpetas que leen los emuladores |
| Instalar apps desconocidas | Solo si usas la auto-actualización: instala el APK que ella misma descarga |
| Notificaciones | Progreso de descargas y sincronización en segundo plano |
| Ignorar optimización de batería | Que la sincronización periódica no la mate el sistema |
| Internet | Hablar con tu servidor RomM y con GitHub (comprobar updates) |

Sin telemetría, sin analytics, sin cuentas: tus datos van de tu dispositivo a **tu** servidor.

## 🏗️ Arquitectura (para contribuir)

Monorepo con tres módulos Gradle que comparten todo el código de negocio:

```
core/src/main/kotlin/com/davidadrianrg/atril/core/   # JVM puro — compartido Android + Desktop
├── remote/              # Retrofit + OkHttp (API RomM, Device Sync Protocol)
├── sync/platform/       # Handlers de saves por emulador (RetroArch, melonDS, PS2, PS3, 3DS...)
├── download/            # PathMapper (estructura de carpetas por plataforma)
├── playstats/           # Tiempo jugado, última partida y favoritos (JSON local)
└── util/                # RootShell, lectura de cabeceras ROM...

app/src/main/java/com/davidadrianrg/atril/           # Android
├── data/local/          # Room + DataStore (config persistente)
├── data/repository/     # Puente remote ↔ cache
├── data/metadata/       # Exportación gamelist.xml / media
├── data/update/         # Auto-actualización desde GitHub Releases
├── download/            # WorkManager + descarga con reanudación
├── ui/                  # Compose Material3 (screens/viewmodel/components)
└── util/                # Permisos Android

desktop/src/main/kotlin/com/davidadrianrg/atril/desktop/  # Linux (Compose Desktop)
├── Main.kt / AppContent.kt   # UI (conexión, biblioteca, descargas, sync)
├── DesktopSyncCoordinator.kt # Ciclo de sync sin Room/WorkManager
├── DownloadEngine.kt         # Descarga stream→disco (fichero único + zip-stream)
└── DesktopConfig.kt          # java.util.prefs (config persistente)
```

- **Stack común**: Kotlin · Retrofit/OkHttp · Moshi · Coroutines
- **Android**: Jetpack Compose (Material 3) · Room · WorkManager · DataStore · Coil
- **Desktop**: Compose Desktop · AppImage (jlink runtime incluido)
- **Idioma del código**: inglés en identificadores/logs; español en textos de UI.
- **Tests**: `./gradlew test` (unit tests en `core/src/test`, `desktop/src/test` y `app/src/test`).

## 🔁 CI/CD

Las releases estables se construyen desde `master`:

1. **APK** (`.github/workflows/build-release.yml`): build firmado (R8) → GitHub Release como `Atril-vX.Y.Z-android.apk`.
2. **AppImage** (`.github/workflows/build-appimage.yml`): build para **x86_64** y **aarch64** → `Atril-vX.Y.Z-linux-<arch>.AppImage`, adjuntos a la misma release que el APK.

El desarrollo del día a día va en **`develop`**. Cada push allí ejecuta `.github/workflows/develop.yml` y actualiza una única release **draft** llamada `develop` con el APK y los dos AppImage. Las releases draft solo las ve el propietario/mantenedores del repo, así que son el canal de pruebas interno.

El tag y el `versionName` coinciden **exactamente** con `versionName` de `app/build.gradle.kts` (p. ej. `0.4.2` → `v0.4.2`); búmpalo ahí para publicar una versión nueva. El `versionCode` interno usa el `run_number` del workflow, que siempre crece (Android lo exige para actualizar sin desinstalar). Repetir una versión reemplaza su release.

El APK de release se firma con una clave fija (secrets `SIGNING_KEYSTORE_BASE64` y derivados) para que las actualizaciones se instalen sobre la versión anterior sin desinstalar. **Guarda copia del keystore**: si se pierde, los usuarios tendrían que desinstalar para actualizar.

## 🤝 Contribuir

1. Fork + rama (`feat/mi-cosa`).
2. `./gradlew test` en verde.
3. PR a `master` — CI debe pasar.

Errores, ideas y PRs bienvenidos en [Issues](https://github.com/davidadrianrg/atril/issues).

## 📄 Licencia

MIT
