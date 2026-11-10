package com.davidadrianrg.atril.core.download

import java.io.File
import java.nio.file.Files

/**
 * Busca un fichero de ROM por nombre exacto bajo las carpetas de plataforma.
 *
 * La búsqueda es pura y determinista: primero comprueba el hijo directo de
 * `<raiz>/<plataforma>` y después hace un recorrido BFS acotado por nombre.
 * Nunca crea, borra ni escribe, nunca lanza y devuelve `null` cuando no hay
 * coincidencia o los datos de entrada no son utilizables (AC-05: el nombre
 * llega de la frontera SAF y no puede contener separadores).
 *
 * Límites: [MAX_DEPTH] niveles por debajo de la carpeta de plataforma y
 * [MAX_ENTRIES] entradas visitadas. Se saltan las entradas ocultas, no se
 * siguen enlaces simbólicos y se aborta en el primer acierto.
 */
object RomFileLocator {

    /** Profundidad máxima del recorrido, relativa a la carpeta de plataforma. */
    const val MAX_DEPTH: Int = 6

    /** Número máximo de entradas visitadas antes de rendirse. */
    const val MAX_ENTRIES: Int = 5_000

    /**
     * Busca [fileName] en `<raiz>/<platformSlug>` para cada raíz no vacía.
     *
     * @param romsRoots raíces de ROMs configuradas (principal y secundaria).
     * @param platformSlug slug de la plataforma que define la carpeta.
     * @param fileName nombre exacto del fichero (comparado sin distinguir caja).
     * @return el primer fichero regular encontrado o `null`.
     */
    fun locate(romsRoots: List<String>, platformSlug: String, fileName: String): File? {
        if (!isSafeFileName(platformSlug) || !isSafeFileName(fileName)) return null
        val roots = romsRoots.map { it.trim() }.filter { it.isNotBlank() }
        if (roots.isEmpty()) return null
        val platformDirs = roots.map { PathMapper.getPlatformDir(it, platformSlug) }
        return locateInDirs(platformDirs, fileName)
    }

    /**
     * Busca [fileName] dentro de [platformDirs] (sus hijos directos y luego BFS).
     *
     * @return el primer fichero regular encontrado o `null`.
     */
    fun locateInDirs(platformDirs: List<File>, fileName: String): File? {
        if (!isSafeFileName(fileName)) return null
        val dirs = platformDirs
            .filter { it.isDirectory }
            .distinctBy { it.canonicalPathOrNull() ?: it.absolutePath }
            .sortedBy { it.name.lowercase() }
        if (dirs.isEmpty()) return null

        // Hijo directo: el caso normal de una ROM descargada por la app.
        for (dir in dirs) {
            val match = dir.listChildren()
                ?.firstOrNull {
                    it.isVisibleEntry() && !it.isSymlink() && it.isFile &&
                        it.name.equals(fileName, ignoreCase = true)
                }
            if (match != null) return match
        }

        // BFS acotado para ficheros movidos a subcarpetas (juego/carpeta/...).
        val queue = ArrayDeque<Pair<File, Int>>()
        dirs.sortedBy { it.name.lowercase() }.forEach { queue.addLast(it to 0) }
        val visitedDirs = mutableSetOf<String>()
        var visitedEntries = 0

        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val key = dir.canonicalPathOrNull() ?: continue
            if (!visitedDirs.add(key)) continue
            if (depth >= MAX_DEPTH) continue

            val children = dir.listChildren()?.sortedBy { it.name.lowercase() } ?: continue
            for (child in children) {
                if (!child.isVisibleEntry()) continue
                if (child.isSymlink()) continue
                visitedEntries++
                if (visitedEntries > MAX_ENTRIES) return null
                if (child.isFile && child.name.equals(fileName, ignoreCase = true)) return child
                if (child.isDirectory) queue.addLast(child to depth + 1)
            }
        }
        return null
    }

    /**
     * Valida un nombre de fichero proveniente de una frontera no confiable.
     *
     * Rechaza vacío, `.`, `..`, separadores de ruta (`/`, `\`) y NUL.
     */
    fun isSafeFileName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name == "." || name == "..") return false
        if (name.contains("..")) return false
        return name.none { it == '/' || it == '\\' || it == '\u0000' }
    }

    private fun File.listChildren(): List<File>? =
        runCatching { listFiles()?.toList() }.getOrNull()

    private fun File.isVisibleEntry(): Boolean = !name.startsWith(".")

    private fun File.isSymlink(): Boolean =
        runCatching { Files.isSymbolicLink(toPath()) }.getOrDefault(false)

    private fun File.canonicalPathOrNull(): String? =
        runCatching { canonicalPath }.getOrNull()
}
