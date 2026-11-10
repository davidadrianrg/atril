package com.davidadrianrg.atril.core.launch

import java.io.File

/**
 * Regla única de "el `.m3u` hermano que referencia este fichero".
 *
 * La usan el lanzador ([LaunchFileResolver]) y el agrupador de juegos locales
 * multidisco, para que ambos resuelvan la playlist igual. Cualquier error de
 * lectura devuelve `null`, nunca lanza.
 *
 * La lectura está acotada: una playlist enorme o ilegible se descarta en vez de
 * cargarse entera en memoria (el agrupador la consulta con frecuencia).
 */
object PlaylistFiles {

    /** Tamaño máximo aceptado de una `.m3u` (256 KiB). */
    private const val MAX_PLAYLIST_BYTES = 256 * 1024L

    /** Número máximo de líneas leídas de una `.m3u` antes de darla por no válida. */
    private const val MAX_PLAYLIST_LINES = 5_000

    /**
     * `.m3u` de la misma carpeta que lista [file] (el primero por nombre), o
     * `null` si [file] ya es un `.m3u`, no tiene carpeta padre, no hay ninguna
     * playlist que lo referencie o la playlist es demasiado grande o ilegible.
     */
    fun siblingPlaylist(file: File): File? {
        if (file.extension.equals("m3u", ignoreCase = true)) return null
        val siblings = file.parentFile?.listFiles { f -> f.isFile && f.extension.equals("m3u", ignoreCase = true) }
            ?: return null
        return siblings.sortedBy { it.name }.firstOrNull { m3u ->
            referencesFile(m3u, file.name)
        }
    }

    /**
     * `true` si [playlist] referencia [fileName] en sus primeras
     * [MAX_PLAYLIST_LINES] líneas. Las playlists por encima de
     * [MAX_PLAYLIST_BYTES] o ilegibles se tratan como no válidas.
     */
    private fun referencesFile(playlist: File, fileName: String): Boolean = runCatching {
        if (playlist.length() > MAX_PLAYLIST_BYTES) return@runCatching false
        playlist.useLines { lines ->
            lines.take(MAX_PLAYLIST_LINES).any { line ->
                line.trim().substringAfterLast('/').substringAfterLast('\\') == fileName
            }
        }
    }.getOrDefault(false)
}
