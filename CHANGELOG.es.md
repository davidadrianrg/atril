# Registro de cambios

Todos los cambios relevantes de Atril se documentan en este archivo.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el
proyecto se acoge a [Versionado Semántico](https://semver.org/lang/es/).

## [Unreleased]

Paquete de mejoras del launcher entregado el 2026-11-10.

### Añadido

- **Tiempo jugado y última partida** — cada lanzamiento se cronometra en Android (desde el arranque del emulador hasta que Atril vuelve a primer plano) y en Linux (desde el lanzamiento hasta que la ventana recupera el foco). La ficha del juego muestra *Última partida* y *Tiempo jugado* con fechas relativas («ahora mismo», «hace 5 min», «hace 2 h», «ayer», «hace 3 días») y duraciones («1 h 23 min», «12 min», «<1 min»), o «Nunca» cuando no hay datos. Las sesiones de menos de 5 segundos se ignoran.
- **Favoritos** — estrella en la ficha del juego en Android y Linux, insignia de favorito en la carta del juego y filtro *Favoritos* en las dos bibliotecas, guardado por juego.
- **Seguir jugando** — fila compacta con los cinco juegos jugados más recientemente (carátula, nombre y tiempo jugado) en las pantallas Jugar de Android y de escritorio. La fila se oculta mientras no hay datos de partida y al elegir un juego se salta a él en el carrusel.
- **Leyenda del mando (solo Android)** — barra inferior discreta y no interactiva en la pantalla Jugar con los glifos A/B/X/Y y las acciones actuales (Jugar, Atrás, Favorito, Detalles). X alterna el favorito del juego seleccionado e Y abre sus detalles.
- Textos en inglés y español para cada etiqueta, acción y formato de tiempo nuevos.

### Notas

- El tiempo jugado y los favoritos se guardan localmente por juego en un almacén JSON compartido (directorio de archivos de la app en Android, `~/.config/atril` en Linux). No se envía nada al servidor RomM: no hay sincronización de tiempo jugado ni de favoritos en el servidor.
- La leyenda del mando es solo de Android; la app de escritorio mantiene sus indicaciones de teclas actuales.
