# Atril — Segunda revisión: nuevas mejoras y funcionalidades (2026-11-16)

**Alcance:** rama `develop` en `944c4a4` (v0.9.2). Revisión de **producto**, no técnica; complementa a `2026-11-16-auditoria.md` y no repite lo que ya está implementado.
**Método:** lectura del código de `app/`, `desktop/` y `core/`. No se ha ejecutado la app; lo que depende del comportamiento real va con **⚠ verificar**.
**Convenciones:** esfuerzo **S** ≤ 1 día · **M** 2–4 días · **L** ≥ 1 semana. **🌐** = depende de la API de RomM. Los endpoints de RomM que el código aún no usa se citan de memoria (RomM 4.x): hay que confirmarlos contra el servidor antes de empezar cada ítem.

**Siguen abiertos de la primera auditoría** (comprobado en código, no se vuelven a proponer aquí): orden configurable y arranque en frío en el último juego (UX 2), menú con desplazamiento y salto circular (UX 5), buscador a pantalla completa (UX 7), saltar la inserción (UX 8), ajuste de animaciones (UX 15), reloj y batería en la cabecera (F8).

---

## 1. Mejoras de UX e interacción

**U1 · «Instalar» cuando falta el emulador** — Play y Biblioteca (Android) · **S**
Hoy el fallo es un snackbar sin salida: «Instala X para jugar a este juego» (`EmulatorLauncher.kt:103`, `PlayScreen.kt:382-388`). Con el catálogo y la cola persistente ya hechos, el aviso puede llevar la acción **Instalar**, que encola el emulador recomendado (o abre Emuladores filtrado por esa plataforma) y, al terminar, ofrece **Jugar**. Convierte el primer tropiezo de un usuario nuevo en un flujo de dos pulsaciones.

**U2 · Acciones rápidas sobre la carta** — Play (ambas) · **M**
En Android horizontal todo lo que no sea jugar o marcar favorito exige abrir la hoja de detalle y desplazarse. Menú radial o lista corta con **A mantenido** (clic derecho en escritorio): Jugar · Favorito · Carta ↔ medio físico · Cambiar carátula · Cambiar fondo · Sincronizar save ahora · Borrar descarga. Escritorio ya tiene parte de estos botones en `PlayCaption`; el menú los unifica y da paridad.

**U3 · Leyenda del mando adaptativa** — Play (ambas) · **S–M**
La leyenda pinta siempre A/B/X/Y con disposición Xbox (`LauncherComponents.kt:715-720`) y escritorio no tiene leyenda. Propuesta: glifos según el mando detectado (Xbox, Nintendo, PlayStation), opción **«Intercambiar A/B»** en Ajustes → Controles (habitual en portátiles con disposición Nintendo), añadir L1/R1 = «Filtro», y la misma barra en escritorio.

**U4 · Estado del save por juego** — línea de información de Play y detalle · **M**
El estado de sync ya es visible en la pantalla Sync, pero no donde se decide jugar. Mostrar en la carta actual «Save sincronizado hace 2 h», «Pendiente de subir» o «Conflicto», y avisar antes de lanzar si hay conflicto sin resolver. Evita la pérdida de progreso más temida al alternar portátil y escritorio.

**U5 · Copia de seguridad con vista previa, selectiva y automática** — Ajustes (ambas) · **S** + **M**
Importar aplica el fichero sin confirmar nada (`ConfigScreen.kt:1043-1064`). Paso S: diálogo previo («Copia de Android del 12/11 · 38 favoritos · 12 juegos locales · ajustes») con casillas por bloque, y nombre de fichero con fecha. Paso M: copia automática semanal en una carpeta elegida (se conservan las 5 últimas) y botón **«Importar una copia»** en el primer arranque, para migrar de dispositivo sin pasar por Ajustes.

**U6 · Reportar desde el fallo** — snackbars de lanzamiento, descargas fallidas, operaciones de sync fallidas · **S**
El reporte solo se abre desde Ajustes (`ConfigScreen.kt:1370`, `DesktopUi.kt:1812`) y tras un crash. Añadir **«Reportar»** en los errores con contexto ya rellenado (plataforma, emulador, código de error), pasando por el `ReportSanitizer` existente. Los reportes llegan con la información que hoy hay que pedir después.

**U7 · Completar el arte que falta, en lote** — Biblioteca / Ajustes → Apariencia · **M**
El catálogo de media compartido permite un asistente: «14 juegos sin carátula». Para cada uno propone la mejor coincidencia a pantalla completa; **A** acepta, **B** salta, **Y** edita el título. Segundo paso opcional: usar el **logotipo** del juego de SteamGridDB como título en Play en lugar del texto.

**U8 · Detalle por pestañas para mando y TV** — hoja de detalle (Android) · **M**
La hoja es una columna larga (jugar, media, información, archivos, saves, copias). Con D-pad llegar a «Copias de saves» son muchas pulsaciones. Pestañas **Jugar · Media · Saves · Archivos** cambiadas con L1/R1, con el foco inicial en Jugar. ⚠ verificar el recorrido actual con mando antes de dimensionar.

**U9 · Biblioteca navegable sin red** — Biblioteca (ambas) · **M**
Play ya funciona sin servidor; la Biblioteca no. Guardar la última lista por plataforma (nombre, carátula pequeña, tamaño) y permitir encolar descargas que arrancan al recuperar la conexión. La cola persistente ya soporta la espera.

---

## 2. Funcionalidades nuevas

### Sin dependencia de RomM

**N1 · Soporte declarado para Android TV** · **S** · impacto alto en TV
El manifest no declara `LEANBACK_LAUNCHER`, `android:banner` ni `touchscreen` como no requerido (`AndroidManifest.xml:74-91`). En Android TV / Google TV la app instalada no aparece en la fila de aplicaciones. Añadir las tres declaraciones y un banner 320×180. ⚠ verificar en un AVD de TV, junto con la aspereza R5 (el detalle de la Biblioteca depende de la pulsación larga).

**N2 · «Sorpréndeme» y «Continuar»** — Play (ambas) · **S**
El juego aleatorio ya existe, pero solo en la Biblioteca y abre un detalle (`LibraryScreen.kt:714`, `DesktopUi.kt:779`). En Play: píldora 🎲 que gira el carrusel hasta un juego al azar del filtro activo (tecla **R** en escritorio), y opción «Al abrir Atril, ir al último juego jugado» con **A** = continuar. Responde a «quiero jugar a algo ya» sin recorrer 200 cartas.

**N3 · Filtros avanzados y orden en Play** · **M**
La caché offline ya guarda géneros, año y nota (`RomMetadataCacheEntity.kt:20-24`), así que funciona sin red. Panel de filtros (igual que las opciones de vista de la Biblioteca): género, década, «nunca jugados», «más jugados», tamaño. Se combina con el orden pendiente (UX 2) y se persiste con claves comunes en `:core`.

**N4 · Pantalla de estadísticas** — nueva entrada de menú (ambas) · **M**
Hoy solo se guarda total y última partida por juego (`PlayStats.kt:11-18`). Fase 1 (S): tiempo total, juegos jugados, top 10 y reparto por plataforma, con lo que ya hay. Fase 2 (M): registro de sesiones en `:core` (una línea por sesión) para «esta semana», rachas y gráfico por día; entra en el backup como esquema 4.

**N5 · Estado de progreso por juego** · **S–M** (local) · **M** con sync 🌐
Jugando · Pendiente · Terminado · Abandonado, como campo nuevo junto al favorito, con píldora de filtro en Play y en la Biblioteca. Sincronizarlo con RomM es opcional y posterior: `PUT /api/roms/{id}/props` exige el scope `roms.user.write`, que hoy no se pide (`DeviceAuth.kt:27-31`), y por tanto volver a autorizar el dispositivo.

**N6 · Dos direcciones para el mismo servidor** (en lugar de multi-instancia) · **S–M**
Multi-servidor real es **L** y de riesgo alto: Room, estadísticas, carátulas y saves usan `romId` como clave y los ids chocarían entre servidores. No lo recomiendo ahora. Lo que sí resuelve el caso típico de una portátil: URL de casa (`http://192.168.…`) y URL remota (`https://…`) para el mismo RomM; Atril prueba ambas al arrancar y usa la que responde, mostrando cuál está activa en Conexión.

**N7 · Modo consola y atajos en escritorio** · **S–M**
Ya existen Ctrl+1–4, F5, Ctrl+F/F3, F, F9, F10, Re Pág/Av Pág y R (`Main.kt:101-116`, `DesktopLauncher.kt:337-378`, `DesktopUi.kt:645`). Falta: **F11** pantalla completa (no hay ninguna forma de activarla), arranque directo a pantalla completa en Play (ajuste y `--fullscreen`, para HTPC y Steam Deck), recordar tamaño y posición de la ventana (hoy 1280×832 fijos en cada arranque, `Main.kt:75`), **F1 / ?** con la chuleta de atajos, Ctrl+D descargas, Ctrl+, ajustes y teclear una letra para saltar a ella en el carrusel.

**N8 · Accesos directos y widget** (Android) · **M**
Para quien no usa Atril como pantalla de inicio: accesos dinámicos a los 3–4 últimos juegos (pulsación larga sobre el icono), «Anclar a la pantalla de inicio» desde el detalle y widget «Seguir jugando». Necesita una actividad trampolín que ejecute el pull de saves previo (`PreLaunchSaveSync`) antes de lanzar. El tile de ajustes rápidos para sync ya existe.

**N9 · Liberar espacio** — desde Descargas / Ajustes → Descargas · **M**
Uso por plataforma, espacio libre y lista de juegos ordenable por tamaño o por «sin jugar desde…», con selección múltiple para borrar descargas (los saves no se tocan). Combina tamaño y estadísticas que ya existen; muy útil en portátiles de 64–128 GB.

### Dependen de la API de RomM 🌐

**N10 · Colecciones de RomM** — Play y Biblioteca · **M–L**
El scope `collections.read` ya se solicita pero no se usa (`DeviceAuth.kt:28`). `GET /api/collections` (y virtuales/smart si el servidor las tiene) como segundo eje de filtrado: en la Biblioteca, selector «Plataforma | Colección»; en Play, píldoras de colección tras las de plataforma. Los ids por colección se cachean en Room para que el filtro funcione sin red. La Biblioteca de Android está atada a una plataforma (`LibraryViewModel.kt:556-557`): hay que generalizar la fuente del listado, y eso es lo que sube el esfuerzo.

**N11 · Biblioteca global y búsqueda en el servidor** · **M**
Android obliga a elegir plataforma; escritorio ya ofrece «Todas» (`DesktopUi.kt:672`). Añadir «Todas las plataformas» en Android y, en el buscador de Play, una segunda sección **«En tu servidor»** (`/api/roms?search_term=` sin `platform_ids`) con acción Descargar. Un solo buscador para «lo tengo» y «lo quiero».

**N12 · Manual del juego** · **S**
`path_manual` ya llega en el DTO (`RomDto.kt:57`) y no hay visor en ninguna pantalla. Botón **Manual** en el detalle: se descarga a caché y se abre con el visor PDF del sistema (Android) o `xdg-open` (Linux). Para juegos descargados, guardarlo junto a la carátula offline.

**N13 · BIOS / firmware desde RomM** · **M**
El scope `firmware.read` también se pide y no se usa (`DeviceAuth.kt:30`). En Plataformas: «BIOS disponibles en tu servidor» con descarga a la carpeta de sistema del emulador configurado, y aviso antes de lanzar si la plataforma la necesita y falta. El riesgo está en las rutas por emulador; empezar por RetroArch y PS2.

**N14 · Logros de RetroAchievements (solo lectura)** · **M**
Si el servidor tiene RA configurado, RomM expone los logros por juego y la progresión del usuario. Mostrar «12/48 logros» en el detalle y una insignia discreta en Play. ⚠ verificar la forma del JSON y ocultar todo si el servidor no lo ofrece.

**N15 · Novedades del servidor** · **S–M**
Fila «Añadidos recientemente» en la Biblioteca (orden por fecha de alta, ⚠ verificar el parámetro) e insignia en el menú cuando el `rom_count` de una plataforma crece respecto a la tabla local.

---

## 3. Cierre de asperezas

| # | Dónde | Qué pasa | Arreglo |
|---|---|---|---|
| R1 | Play Android, «Seguir jugando» | Solo se quita el filtro de plataforma (`PlayScreen.kt:624-628`). Con Favoritos activo, un juego no favorito no se centra y el salto queda pendiente hasta el siguiente cambio de filtro. Escritorio limpia ambos (`DesktopLauncher.kt:471-478`). | Limpiar también el filtro rápido. |
| R2 | Buscador de Play | Android busca dentro del filtro activo (`PlayScreen.kt:1085`); escritorio en todos los juegos (`DesktopLauncher.kt:682`). En Android, Enter del teclado no elige nada; en escritorio elige el primero (`:1105-1110`). | Buscar siempre en todos y quitar el filtro si oculta el resultado; acción de teclado «Ir». |
| R3 | Píldoras de Play (ambas) | Favoritos y Recientes desaparecen si solo hay una plataforma (`PlayScreen.kt:557`, `DesktopLauncher.kt:433`). L1/R1 solo recorren plataformas, nunca esos dos filtros. | Mostrar siempre los filtros rápidos e incluirlos en el ciclo L1/R1. |
| R4 | Filtros y posición de Play | Android los guarda con `rememberSaveable` (`PlayScreen.kt:191-193`): se pierden al reiniciar. Escritorio con `remember` (`DesktopLauncher.kt:211-213`): se pierden al cambiar de sección y no recuerda el último juego. | Persistirlos con claves comunes en `:core`, como las de la Biblioteca. |
| R5 | Biblioteca Android, rejilla | El toque corto no hace nada (`onClick = {}`) y el detalle solo abre con pulsación larga (`LibraryScreen.kt:1399-1402`). El botón Y solo está cableado en Play (`PlayScreen.kt:435`). ⚠ verificar si con mando se puede abrir el detalle. | Toque / A abre el detalle; Y también, como en Play. |
| R6 | Favorito con mando | X cambia el favorito sin aviso (`PlayScreen.kt:443-447`); tocar el chip de la leyenda sí muestra snackbar (`:875-885`). | Mismo aviso por ambas vías. |
| R7 | Textos ligados a la entrada | «Toca para cancelar» en la inserción también en TV y escritorio (`LauncherComponents.kt:1055`, `DesktopLauncher.kt:754`); «pulsa X sobre un juego» en un móvil sin mando. | Variante por entrada: «B / Esc para cancelar», «toca la estrella». |
| R8 | Backup en Android | El texto dice «tema, fondo, idioma y emuladores manuales» (`ConfigScreen.kt:1087`), pero el esquema 3 lleva preferencias, estadísticas y juegos locales. | Actualizar `backup.hint` (va con U5). |
| R9 | Menú de Android | No hay entrada «Descargas» (`RomMSyncApp.kt:279-287`): desde Sync, Emuladores o Ajustes no se puede ver la cola. Escritorio la tiene como sección. | Añadirla con el contador de activas. |
| R10 | Ayuda de controles | Vive en Ajustes → Acerca de (`ConfigScreen.kt:1094-1100`), donde nadie la busca. En la Biblioteca R1 abre opciones y L1 no hace nada (`LibraryScreen.kt:241-250`). | Sección propia «Controles» (encaja con U3); L1/R1 cambian de plataforma en la Biblioteca y las opciones pasan a otro botón. |

---

## 4. Priorización

Impacto y riesgo: **A**lto / **M**edio / **B**ajo.

| Orden | Ítem | Impacto | Riesgo | Esf. | 🌐 | Lote |
|---|---|---|---|---|---|---|
| 1 | R1–R7 · Asperezas de Play y Biblioteca con mando | A | B | S–M | — | 1 |
| 2 | N1 · Soporte declarado para Android TV | A (TV) | B | S | — | 1 |
| 3 | U1 · «Instalar» cuando falta el emulador | A | B | S | — | 1 |
| 4 | N2 · «Sorpréndeme» y «Continuar» en Play | M–A | B | S | — | 1 |
| 5 | N7 · Modo consola y atajos en escritorio | M–A | B | S–M | — | 1 |
| 6 | U5 (paso S) + R8 · Vista previa y texto del backup | M | B | S | — | 1 |
| 7 | N3 · Filtros avanzados y orden en Play | A | B | M | — | 2 |
| 8 | N10 · Colecciones de RomM | A | M | M–L | sí | 2 |
| 9 | N11 · Biblioteca global y búsqueda en el servidor | A | M | M | sí | 2 |
| 10 | N4 · Estadísticas (fase 1 y registro de sesiones) | M | B | M | — | 2 |
| 11 | U3 + R10 · Leyenda adaptativa y sección Controles | M | B | S–M | — | 2 |
| 12 | U4 · Estado del save por juego | M–A | M | M | — | 2 |
| 13 | N12 · Manual del juego | M | B | S | sí | 2 |
| 14 | U6 · Reportar desde el fallo | M | B | S | — | 2 |
| 15 | N6 · Dos direcciones para el mismo servidor | M–A | M | S–M | — | 3 |
| 16 | N9 · Liberar espacio | M | M | M | — | 3 |
| 17 | N5 · Estado de progreso (local; sync después) | M | B / M | S–M | parcial | 3 |
| 18 | U2 · Acciones rápidas sobre la carta | M | M | M | — | 3 |
| 19 | N13 · BIOS / firmware desde RomM | M | A | M | sí | 3 |
| 20 | U7 · Completar arte en lote y logotipos | M | B | M | — | 3 |
| 21 | U8 · Detalle por pestañas | M | M | M | — | 3 |
| 22 | U9 · Biblioteca sin red | M | M | M | — | 3 |
| 23 | N8 · Accesos directos y widget | B–M | M | M | — | 3 |
| 24 | N14 · Logros de RetroAchievements | B–M | M | M | sí | 3 |
| 25 | N15 · Novedades del servidor | B | B | S–M | sí | 3 |
| — | Multi-servidor real | M | A | L | — | no ahora |

**Por qué este orden**

- **Lote 1** es todo de esfuerzo S o S–M, sin API nueva y con riesgo bajo. Arregla lo que un usuario con mando nota a diario (R1–R7), abre la app a Android TV (N1), elimina el callejón sin salida más frecuente de un usuario nuevo (U1) y da dos mejoras visibles de «launcher de verdad» (N2, N7). El paso S de U5 cierra además un texto que hoy describe mal el backup.
- **Lote 2** es donde está el salto de producto: descubrir qué jugar (N3, N10, N11) y cuidar la partida (U4, N4). N10 y N11 comparten el mismo trabajo de fondo —desacoplar la Biblioteca de Android de «una plataforma»—, así que conviene hacerlas seguidas y empezar por N11, que es la más barata.
- **Lote 3** agrupa lo valioso pero con más incertidumbre (rutas de BIOS, forma del JSON de logros, trampolín de accesos directos) o con menor alcance.
- **N5** se hace primero en local: el sync con RomM obliga a reautorizar el dispositivo y no debería bloquear la función.

**Parity y verificación.** Todo ítem con UI se entrega en Android y Linux en la misma rama `feat/*`, salvo los que son de una sola plataforma por naturaleza (N1 y N8 en Android, N7 en escritorio). Antes del lote 2, confirmar contra el servidor real los endpoints de colecciones, búsqueda sin plataforma y orden por fecha.
