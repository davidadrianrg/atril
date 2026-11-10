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

### Cambiado

- La vista, el orden, el origen y el tamaño de la Biblioteca se guardan ahora en las dos plataformas y se restauran al volver a abrir, con las mismas claves de preferencias definidas en `:core`.
- Android y escritorio comparten una única implementación de orden con nueve criterios, incluido *Jugado recientemente*; el menú de orden de la barra mantiene la lista completa mientras el panel de opciones ofrece nombre, jugado recientemente y año.
- El disco elegido de un grupo local multidisco se guarda por juego, y eliminar un juego local agrupado elimina todos sus discos.
- 73 pruebas unitarias nuevas cubren la persistencia de vista/orden/tamaño, el índice alfabético con filtros, la agrupación local de discos y sus falsos positivos, el códec del disco elegido y el tope de 8 segundos.

### Notas

**Mejoras del launcher**

- El tiempo jugado y los favoritos se guardan localmente por juego en un almacén JSON compartido (directorio de archivos de la app en Android, `~/.config/atril` en Linux). No se envía nada al servidor RomM: no hay sincronización de tiempo jugado ni de favoritos en el servidor.
- La leyenda del mando es solo de Android; la app de escritorio mantiene sus indicaciones de teclas actuales.

**Biblioteca, fase 2**

- La descarga previa al lanzamiento solo baja partidas que faltan o son más nuevas y nunca sube ni resuelve conflictos; la sincronización normal y su interfaz de conflictos siguen siendo la referencia.
- La barra alfabética indexa los juegos ya cargados, no toda la biblioteca del servidor: con resultados paginados de RomM, las letras más allá de las páginas cargadas no tienen destino hasta que se cargan más.
- En este entorno no se usa el emulador de Android, así que las vistas y el comportamiento del foco nuevos se validan con compilación y pruebas unitarias de JVM, no con pruebas de UI instrumentadas.
