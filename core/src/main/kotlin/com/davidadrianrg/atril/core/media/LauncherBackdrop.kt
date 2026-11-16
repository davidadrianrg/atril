package com.davidadrianrg.atril.core.media

/**
 * Fondo del launcher, igual en Android y escritorio.
 *
 * Modos: `blur` (captura del juego difuminada, por defecto), `solid`
 * (degradado del tema, sin imagen) y `fanart` (SteamGridDB).
 */
object LauncherBackdrop {
    const val BLUR = "blur"
    const val SOLID = "solid"
    const val FANART = "fanart"
    val MODES = listOf(BLUR, SOLID, FANART)

    fun normalizeMode(raw: String?): String = raw?.takeIf { it in MODES } ?: BLUR

    /**
     * Imagen a pintar detrás del carrusel, o null para el degradado.
     * El fondo elegido a mano para el juego manda sobre todo salvo `solid`;
     * con `fanart` sin descargar aún se muestra la captura mientras tanto.
     */
    fun choose(mode: String, gameBackground: String?, fanart: String?, screenshot: String?): String? =
        when {
            normalizeMode(mode) == SOLID -> null
            gameBackground != null -> gameBackground
            normalizeMode(mode) == FANART -> fanart ?: screenshot
            else -> screenshot
        }

    /** `romId=ruta;romId=ruta` (formato plano compartido con Android). */
    fun decodeGameBackgrounds(raw: String): Map<Int, String> =
        raw.split(';').mapNotNull { entry ->
            val id = entry.substringBefore('=', "").trim().toIntOrNull() ?: return@mapNotNull null
            val path = entry.substringAfter('=', "").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            id to path
        }.toMap()

    fun encodeGameBackgrounds(map: Map<Int, String>): String =
        map.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    /** Devuelve el mapa con el fondo de [romId] puesto ([path]) o quitado (null). */
    fun withGameBackground(raw: String, romId: Int, path: String?): String {
        val map = decodeGameBackgrounds(raw).toMutableMap()
        if (path.isNullOrBlank() || path.contains(';')) map.remove(romId) else map[romId] = path
        return encodeGameBackgrounds(map)
    }
}
