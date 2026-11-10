# Registro de cambios

Todos los cambios relevantes de Atril se documentan en este archivo.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el
proyecto se acoge a [Versionado Semántico](https://semver.org/lang/es/).

## [Unreleased]

### Añadido

**Mejoras del launcher (2026-11-10)**

- **Tiempo jugado y última partida** — cada lanzamiento se cronometra en Android (desde el arranque del emulador hasta que Atril vuelve a primer plano) y en Linux (desde el lanzamiento hasta que la ventana recupera el foco). La ficha del juego muestra *Última partida* y *Tiempo jugado* con fechas relativas («ahora mismo», «hace 5 min», «hace 2 h», «ayer», «hace 3 días») y duraciones («1 h 23 min», «12 min», «<1 min»), o «Nunca» cuando no hay datos. Las sesiones de menos de 5 segundos se ignoran.
- **Favoritos** — estrella en la ficha del juego en Android y Linux, insignia de favorito en la carta del juego y filtro *Favoritos* en las dos bibliotecas, guardado por juego.
- **Seguir jugando** — fila compacta con los cinco juegos jugados más recientemente (carátula, nombre y tiempo jugado) en las pantallas Jugar de Android y de escritorio. La fila se oculta mientras no hay datos de partida y al elegir un juego se salta a él en el carrusel.
- **Leyenda del mando (solo Android)** — barra inferior discreta y no interactiva en la pantalla Jugar con los glifos A/B/X/Y y las acciones actuales (Jugar, Atrás, Favorito, Detalles). X alterna el favorito del juego seleccionado e Y abre sus detalles.
- Textos en inglés y español para cada etiqueta, acción y formato de tiempo nuevos.

**Biblioteca, fase 2 (2026-11-10)**

- **Carrusel 3D** — vista opcional de la Biblioteca en Android y Linux: carátulas inclinadas en 3D con un reflejo sutil abajo, navegación con cruceta y teclado, y A/Enter para abrir la ficha del juego. La cuadrícula sigue siendo la vista por defecto, la elección se guarda y la carga con esqueleto y el scroll infinito funcionan en las dos vistas.
- **Panel de opciones de vista** — panel contextual que se abre con el botón de ajustes de la barra de la Biblioteca, R1 en Android y R en Linux (no mientras el buscador tiene el foco). Agrupa *Orden* (nombre, jugado recientemente, año cuando está disponible), *Origen* (todos, local, RomM), *Vista* (cuadrícula, carrusel 3D) y *Tamaño* (pequeño, mediano, grande). Los cambios se aplican al instante, el panel sigue abierto y los valores se conservan entre sesiones. Como la Biblioteca lista juegos de RomM, *Origen: Local* muestra un estado vacío explícito que explica que los juegos locales están en la pantalla Jugar.
- **Índice A-Z** — barra de letras A-Z y # en el borde derecho de la Biblioteca cuando la lista filtrada supera los 40 juegos, en las dos plataformas. Abajo entra desde la cuadrícula, izquierda/derecha se mueve, A/Enter salta y Arriba vuelve. Indexa el resultado filtrado (búsqueda y filtros incluidos) y salta dentro de los juegos ya cargados; reserva espacio en el borde y se atenúa cuando está inactiva.
- **Juegos locales multidisco** — los archivos locales con marca `(Disc 1)`, `(Disk 2)`, `(CD 3)` o `(Disc 1 of 4)` se agrupan en una sola entrada de Jugar con una insignia de *N discos*. La ficha permite elegir el disco (el 1 por defecto, o el `.m3u` hermano si existe) y la elección se recuerda por juego. Al lanzar se usa el disco elegido y se mantiene el comportamiento del `.m3u`; los juegos de RomM y los nombres parecidos sin marca no se tocan.
- **Descarga de partidas antes de lanzar** — con la sincronización de saves activada, al lanzar un juego de RomM se descargan primero las partidas nuevas o más recientes del servidor con un tope duro de 8 segundos y un aviso *Actualizando partidas…*. Un servidor lento o con fallos nunca bloquea ni impide el lanzamiento. Los juegos sin id de RomM o con la sincronización desactivada mantienen el comportamiento anterior.
- Textos en inglés y español para cada etiqueta, control y aviso nuevos, con las mismas claves y marcadores en los dos ficheros.

**Descargas manuales (2026-11-10)**

- **Vinculación automática al marcar un juego** — al marcar un juego de RomM como descargado, Atril busca su archivo en la carpeta de la plataforma (`<ROMs>/<plataforma>/<nombre del archivo>`) y, cuando no está ahí, en una búsqueda acotada por nombre exacto: hasta seis niveles de profundidad y 5000 entradas, saltando las entradas ocultas y los enlaces simbólicos. Si encuentra el archivo guarda su ruta y el juego queda lanzable al instante; si no, mantiene la marca manual como antes.
- **Localizar archivo** — la ficha de un juego marcado a mano muestra un botón *Localizar archivo* mientras no tenga ruta. En Android abre el selector de documentos del sistema y copia el archivo elegido a `<ROMs>/<plataforma>/`, preguntando antes de reemplazar un archivo con el mismo nombre, rechazando los archivos de más de 16 GiB o que no caben en el espacio libre, y pidiendo que configures la carpeta de ROMs cuando no hay ninguna; en Linux usa el diálogo nativo de archivos y guarda la ruta elegida sin copiar nada.
- **Búsqueda al lanzar** — al lanzar un juego que sigue marcado a mano se reintenta primero la búsqueda: si el archivo ha aparecido, se asocia y el juego arranca; si sigue faltando, Atril lo dice. En Android el aviso ofrece *Localizar archivo* y abre el selector de documentos sin salir de la pantalla Jugar; en Linux el aviso solo explica el problema y el botón *Localizar archivo* está en la ficha del juego.
- **Juegos APK en Android** — un `.apk` (o un juego de una plataforma `android`) ya no se envía a un emulador: si la app ya está instalada Atril la abre y, si no, abre el instalador del sistema. En Linux el APK se rechaza con un mensaje claro.
- Textos en inglés y español para cada botón, aviso y mensaje de error nuevos.

### Cambiado

- La vista, el orden, el origen y el tamaño de la Biblioteca se guardan ahora en las dos plataformas y se restauran al volver a abrir, con las mismas claves de preferencias definidas en `:core`.
- Android y escritorio comparten una única implementación de orden con nueve criterios, incluido *Jugado recientemente*; el menú de orden de la barra mantiene la lista completa mientras el panel de opciones ofrece nombre, jugado recientemente y año.
- El disco elegido de un grupo local multidisco se guarda por juego, y eliminar un juego local agrupado elimina todos sus discos.
- Los nombres de fichero de partidas que llegan del servidor se validan ahora antes de cualquier descarga o escritura: se rechazan los nombres vacíos, las rutas absolutas, los separadores de ruta, los elementos `.`/`..` y los bytes NUL, y el destino resuelto debe quedar dentro del directorio canónico de partidas (enlaces simbólicos incluidos). Un nombre rechazado se omite sin abortar el resto de la sincronización.
- Cuando un descriptor `.cue`, `.gdi` o `.m3u` y un fichero de datos comparten número de disco, gana el descriptor (`.cue` > `.gdi` > `.m3u`), así que el grupo multidisco lanza el fichero que un emulador puede arrancar.
- Vincular un archivo a un juego marcado a mano lo convierte en una descarga normal: la ruta es lo que define el estado, así que desaparecen la insignia *Manual*, *Quitar marca* y *Localizar archivo* y en su lugar aparecen las acciones habituales de descarga (borrar el archivo, moverlo a la tarjeta SD, ajustes de sincronización). Es el comportamiento previsto, no un fallo, y es la razón de no necesitar ninguna columna nueva en la base de datos.
- 91 pruebas unitarias cubren ahora el trabajo de la fase 2: las 73 de la primera pasada más 18 pruebas de regresión añadidas con las correcciones de revisión y seguridad. Abarcan la persistencia de vista/orden/tamaño, el índice alfabético con filtros, la agrupación local de discos y sus falsos positivos, el códec del disco elegido, el tope de 8 segundos, la validación de nombres de partida y el desempate por descriptor.

### Corregido

**Endurecimiento de seguridad de la sincronización de partidas (2026-11-10)**

- Los archivos de partidas usan una única regla compartida para cada miembro, tanto en zip como en tar: se rechazan las rutas absolutas, los componentes `..`, los bytes NUL, las barras invertidas y las letras de unidad de Windows, y cada destino resuelto debe quedar dentro de la carpeta canónica de partidas. Una entrada de zip no válida se omite sin abortar el resto de la sincronización.
- Las restauraciones de PS3 ya no se fían del nombre de carpeta de destino que trae el archivo: el nombre (primera entrada del archivo, carpeta local existente o serial) debe ser un nombre simple y seguro, y la carpeta debe resolverse estrictamente dentro de `…/savedata`. Primero se valida todo el archivo, después se extrae en una carpeta temporal oculta dentro de `savedata` y solo cuando el árbol está completo se vacía la carpeta existente y se mueve allí el contenido; si falla la extracción, la partida existente queda intacta.
- Las restauraciones de tar de Android y PS2 en dispositivos rooteados se validan y se extraen como el usuario de la app en una carpeta temporal propia de la aplicación; root solo se usa después, sobre el árbol ya validado, para conservar el propietario y cambiar la carpeta. Los archivos con rutas absolutas o recorridos, enlaces, dispositivos, miembros truncados o más de una carpeta raíz se rechazan antes de cualquier paso privilegiado.
- El `platform_slug` que llega del servidor y se usa en las rutas de partidas y de gamelists debe cumplir `^[a-z0-9][a-z0-9._-]{0,63}$`. Los valores no válidos se descartan en la entrada de datos de RomM (se guardan como vacío o `unknown`, nunca hacen fallar el análisis) y se vuelven a rechazar donde se construye una ruta de partida, así que los valores guardados antes de esta corrección no pueden salir de la carpeta de partidas. Los slugs válidos se usan tal cual.
- Las líneas de registro de la sincronización ya no incluyen mensajes de excepción en bruto ni nombres de partida elegidos por el servidor: los nombres pierden los caracteres de control y se limitan en longitud, y los fallos registran una clasificación fija más el nombre de la clase de la excepción.
- Cambios de comportamiento deliberados: una restauración de tar rechazada o corrupta deja intacta la partida existente en vez de borrarla primero; un archivo de PS3 con cualquier entrada no segura se rechaza entero en vez de extraerse en parte; las entradas de zip que contienen una barra invertida se omiten.
- 61 pruebas de regresión nuevas cubren estas reglas con árboles temporales reales y sin root (598 pruebas unitarias de JVM en total, todas en verde).

**Endurecimiento de seguridad de descargas y rutas de partidas (2026-11-10)**

- Los zip de descarga de ROMs pasan por un único extractor: `ZipStreamExtractor` (`:core/download`) sirve tanto al `DownloadWorker` de Android como al `DownloadEngine` de escritorio, y resuelve cada entrada con `SaveNames.resolveArchiveEntry`. Las entradas con `..`, una ruta absoluta, un byte NUL, una barra invertida, una letra de unidad de Windows o una carpeta hermana que solo comparte el prefijo del destino se omiten sin abortar el archivo, y una extracción fallida o cancelada borra los ficheros que había creado. El recuento de «ficheros extraídos» refleja solo los ficheros realmente escritos.
- Los nombres derivados del sistema de ficheros que se interpolan en un comando `tar` ejecutado con root (la carpeta de la app en Android, la tarjeta de memoria y la carpeta del serial en PS2) se validan con `RootCommandNames` (`:core/sync`): charset ASCII `^[A-Za-z0-9][A-Za-z0-9._ -]{0,199}$`, primer carácter alfanumérico y contención canónica bajo la base configurada antes de construir el comando. `RootShell.sq` sigue siendo la barrera de escapado, y los nombres que llegan dentro de un archivo siguen usando `SaveNames.isSafeSaveFileName`.
- Las restauraciones de partidas ya no borran un destino con un `deleteRecursively` sin validar: un único `SafeDelete.clearDirectoryContents(base, target)` (`:core/util`) sustituye los siete puntos de borrado de los handlers de partidas (los seis sin canonicalizar más la copia privada de PS3, que ya canonicalizaba) y la limpieza de la carpeta temporal de `TarExtractor`. Vacía el destino pero nunca lo borra, no sigue enlaces simbólicos en ningún nivel, elimina un enlace como entrada sin destruir su destino (aunque el destino esté dentro de la base), solo desciende por directorios reales tras revalidar su contención canónica en la base y borra únicamente los hijos que quedan dentro de ella. El resultado de la restauración no cambia: las partidas anteriores se siguen reemplazando, PS2 conserva su modo de fusión y los handlers mantienen su `mkdirs` o su paso por la carpeta temporal.
- `PathMapper.getPlatformDir` sanea el slug de plataforma con la regla única `PlatformSlug` después del `trim` y el `lowercase` existentes, así que un valor hostil nunca llega al constructor de `File`. Un slug ausente o vacío conserva el centinela `unknown` y uno no vacío pero inválido cae a él; el `DownloadWorker` de Android rechaza un slug no vacío inválido con el mensaje de plataforma no válida ya existente antes de crear ninguna carpeta.
- 40 pruebas de regresión nuevas cubren estas reglas con árboles temporales reales, flujos zip y enlaces simbólicos donde el sistema de ficheros lo permite. La suite completa son 638 pruebas unitarias de JVM: 636 en verde y las 2 omisiones preexistentes de servidor real.
- Sin dependencias, plugins, columnas de esquema, claves de preferencias ni textos i18n nuevos.

### Notas

**Mejoras del launcher**

- El tiempo jugado y los favoritos se guardan localmente por juego en un almacén JSON compartido (directorio de archivos de la app en Android, `~/.config/atril` en Linux). No se envía nada al servidor RomM: no hay sincronización de tiempo jugado ni de favoritos en el servidor.
- La leyenda del mando es solo de Android; la app de escritorio mantiene sus indicaciones de teclas actuales.

**Biblioteca, fase 2**

- La descarga previa al lanzamiento solo baja partidas que faltan o son más nuevas y nunca sube ni resuelve conflictos; la sincronización normal y su interfaz de conflictos siguen siendo la referencia.
- La barra alfabética indexa los juegos ya cargados, no toda la biblioteca del servidor: con resultados paginados de RomM, las letras más allá de las páginas cargadas no tienen destino hasta que se cargan más.
- En este entorno no se usa el emulador de Android, así que las vistas y el comportamiento del foco nuevos se validan con compilación y pruebas unitarias de JVM, no con pruebas de UI instrumentadas.

**Descargas manuales**

- La búsqueda solo lee el sistema de archivos: nunca crea, mueve ni borra archivos. En Android *Localizar archivo* copia siempre a la carpeta de ROMs principal, y un documento que ya está bajo una carpeta de ROMs configurada se asocia en su sitio en vez de duplicarse. La copia escribe un archivo `.part` y lo renombra al final, y rechaza una carpeta de destino o un archivo parcial que sea un enlace simbólico, así como un destino fuera de la carpeta de ROMs configurada.
- No hay cambios de esquema, ni preferencias nuevas, ni dependencias nuevas, y el flujo normal de descarga de RomM (cola, descargas en paralelo, verificación por hash, reanudación) y los juegos locales no se tocan.
- Los juegos APK son una función solo de Android: en Linux el archivo se detecta y se rechaza antes del planificador de emuladores, así que el error es explícito en vez de «no hay emulador configurado».
