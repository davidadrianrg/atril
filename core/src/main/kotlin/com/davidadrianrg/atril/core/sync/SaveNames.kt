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

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")

    /**
     * Indica si [entryPath] es una ruta relativa segura para un miembro de
     * archivo (zip o tar), antes de resolverla contra una carpeta destino.
     *
     * Rechaza rutas vacías o en blanco, el byte NUL, la barra invertida
     * (nombre de fichero en Linux, separador en Windows), rutas absolutas, las
     * letras de unidad de Windows y cualquier componente `..`. Acepta rutas
     * relativas anidadas (`a/b/c.srm`), barras finales (`dir/`), componentes
     * vacíos (`a//b`) y componentes `.`; no accede al sistema de ficheros.
     *
     * @param entryPath nombre del miembro tal como está guardado en el archivo.
     * @return `true` solo si el miembro no puede escapar de su carpeta destino.
     */
    fun isSafeArchiveEntryPath(entryPath: String): Boolean {
        if (entryPath.isBlank()) return false
        if (entryPath.any { it == '\u0000' }) return false
        if (entryPath.contains('\\')) return false
        if (entryPath.startsWith("/")) return false
        if (DRIVE_PREFIX.containsMatchIn(entryPath)) return false
        if (entryPath.split('/').any { it == ".." }) return false
        return true
    }

    /**
     * Resuelve un miembro de archivo [entryPath] bajo [base] y devuelve
     * `null` siempre que el miembro pueda escapar.
     *
     * Es la regla de contención única que comparten todos los puntos de
     * extracción de saves. Primero aplica [isSafeArchiveEntryPath] y después
     * comprueba que la ruta canónica destino quede estrictamente dentro de la
     * base canónica (así se rechazan `.`, la propia base, las rutas de un
     * hermano con el mismo prefijo y las fugas por enlace simbólico).
     *
     * @param base carpeta destino de la extracción.
     * @param entryPath nombre del miembro tal como está guardado en el archivo.
     * @return el fichero canónico destino, o `null` cuando no es seguro.
     */
    fun resolveArchiveEntry(base: File, entryPath: String): File? {
        if (!isSafeArchiveEntryPath(entryPath)) return null
        return try {
            val canonicalBase = base.canonicalFile
            val target = File(canonicalBase, entryPath).canonicalFile
            val basePrefix = canonicalBase.path + File.separator
            if (target.path.startsWith(basePrefix)) target else null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
