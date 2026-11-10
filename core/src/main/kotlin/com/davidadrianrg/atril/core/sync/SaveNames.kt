package com.davidadrianrg.atril.core.sync

import java.io.File
import java.io.IOException

/**
 * Nombres de fichero de saves en RomM.
 *
 * Las subidas con slot se guardan en el servidor con una etiqueta de versión
 * (`Juego [2026-11-01_12-34-56].srm`). Al descargar hay que quitarla: el
 * emulador espera el nombre canónico (`Juego.srm`).
 *
 * El nombre que llega del servidor es dato no confiable: nunca se usa tal cual
 * como ruta. [isSafeSaveFileName] y [resolveInsideDirectory] son la puerta
 * única que comparten [SyncTransfers] y los handlers para impedir que un
 * servidor hostil escriba fuera de la carpeta de saves.
 */
object SaveNames {

    private val VERSION_TAG = Regex("""\s*\[\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}](?=\.[^.]+$)""")

    fun stripVersionTag(fileName: String): String =
        VERSION_TAG.replace(fileName, "")

    /**
     * Indica si [fileName] es un nombre simple y seguro para construir una
     * ruta dentro de un directorio de saves.
     *
     * Rechaza vacíos o en blanco, rutas absolutas, cualquier separador (`/` o
     * `\`), un elemento de ruta `..` y el byte NUL. Un nombre aceptado es
     * siempre un único componente de ruta que no puede escapar del directorio
     * destino.
     *
     * @param fileName nombre de fichero proporcionado por el servidor (sin la
     *   etiqueta de versión).
     * @return `true` solo si el nombre es un componente de ruta simple y seguro.
     */
    fun isSafeSaveFileName(fileName: String): Boolean {
        if (fileName.isBlank()) return false
        if (fileName.any { it == '\u0000' }) return false
        if (fileName.startsWith("/") || fileName.startsWith("\\")) return false
        if (fileName.contains('/') || fileName.contains('\\')) return false
        if (fileName == "." || fileName == "..") return false
        if (fileName.split('/', '\\').any { it == ".." }) return false
        return true
    }

    /**
     * Resuelve [fileName] dentro de [directory] solo cuando es seguro y su
     * ruta canónica sigue contenida en [directory].
     *
     * Es la segunda barrera (defensa en profundidad) antes de escribir un save
     * descargado: aunque [isSafeSaveFileName] ya rechaza separadores y `..`,
     * el chequeo canónico también neutraliza enlaces simbólicos y rarezas del
     * sistema de ficheros.
     *
     * @param directory carpeta destino (p. ej. la carpeta de saves del slug).
     * @param fileName nombre de fichero proporcionado por el servidor.
     * @return el fichero destino resuelto, o `null` si el nombre no es seguro
     *   o la ruta canónica se sale de [directory].
     */
    fun resolveInsideDirectory(directory: File, fileName: String): File? {
        if (!isSafeSaveFileName(fileName)) return null
        return try {
            val base = directory.canonicalFile
            val target = File(base, fileName).canonicalFile
            val basePrefix = base.path + File.separator
            if (target.path != base.path && target.path.startsWith(basePrefix)) target else null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
