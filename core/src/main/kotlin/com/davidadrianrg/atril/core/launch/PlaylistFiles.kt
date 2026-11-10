package com.davidadrianrg.atril.core.launch

import java.io.File

/**
 * Regla única de "el `.m3u` hermano que referencia este fichero".
 *
 * La usan el lanzador ([LaunchFileResolver]) y el agrupador de juegos locales
 * multidisco, para que ambos resuelvan la playlist igual. Cualquier error de
 * lectura devuelve `null`, nunca lanza.
 */
object PlaylistFiles {

    /**
     * `.m3u` de la misma carpeta que lista [file] (el primero por nombre), o
     * `null` si [file] ya es un `.m3u`, no tiene carpeta padre o no hay
     * ninguna playlist que lo referencie.
     */
    fun siblingPlaylist(file: File): File? {
        if (file.extension.equals("m3u", ignoreCase = true)) return null
        val siblings = file.parentFile?.listFiles { f -> f.isFile && f.extension.equals("m3u", ignoreCase = true) }
            ?: return null
        return siblings.sortedBy { it.name }.firstOrNull { m3u ->
            runCatching {
                m3u.readLines().any { line ->
                    line.trim().substringAfterLast('/').substringAfterLast('\\') == file.name
                }
            }.getOrDefault(false)
        }
    }
}
