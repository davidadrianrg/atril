package com.davidadrianrg.atril.core.util

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/**
 * Vaciado seguro del contenido de un destino de restauración confinado a una
 * base de confianza.
 *
 * Generaliza el patrón que ya usaba el handler de PS3: canonicaliza base y
 * destino, borra solo lo que sigue dentro de la base y reduce un enlace que
 * escapa a borrar el enlace, nunca su destino. El recorrido no sigue enlaces
 * simbólicos en ningún nivel, tampoco los anidados: cada enlace se elimina como
 * entrada y no se desciende por él. El directorio destino se conserva siempre:
 * los handlers crean después su contenido con `mkdirs` y extraen como hasta
 * ahora.
 */
object SafeDelete {

    /**
     * Borra solo el contenido de [target], nunca el propio directorio.
     *
     * Si [target] no existe o no se puede canonicalizar no hace nada. Si el
     * destino canónico no es [base] ni queda estrictamente bajo ella (un enlace
     * que escapa), borra únicamente la entrada [target] y retorna sin enumerar
     * ni borrar nada fuera.
     *
     * En caso normal vacía el contenido del destino canónico recorriéndolo sin
     * seguir enlaces simbólicos: un enlace, a cualquier profundidad, se elimina
     * como entrada y nunca se desciende por él, de modo que no puede alcanzarse
     * contenido fuera de la base. Como defensa adicional, un nodo visitado que
     * no sea un enlace y cuyo canónico no quede bajo la base se borra como
     * entrada sin descender. El destino nunca se borra.
     *
     * [base] y [target] pueden ser el mismo directorio. Es una operación best
     * effort: ninguna [IOException] ni [SecurityException] se propaga y no crea
     * directorios.
     *
     * @param base base de confianza (p. ej. la carpeta de saves configurada).
     * @param target carpeta cuyo contenido se quiere vaciar.
     */
    fun clearDirectoryContents(base: File, target: File) {
        if (!target.exists()) return

        val canonicalBase = canonicalOrNull(base) ?: return
        val canonicalTarget = canonicalOrNull(target) ?: return
        val canonicalBasePath = canonicalBase.toPath()
        val canonicalTargetPath = canonicalTarget.toPath()

        if (!isAtOrUnder(canonicalTargetPath, canonicalBasePath)) {
            // El destino escapa de la base (enlace o hermano): se elimina solo
            // la entrada, nunca lo que hay al otro lado.
            deleteEntry(target.toPath())
            return
        }

        try {
            Files.walkFileTree(
                canonicalTargetPath,
                // Sin FOLLOW_LINKS: un enlace se visita como fichero, nunca se
                // atraviesa, sea cual sea su profundidad.
                EnumSet.noneOf(FileVisitOption::class.java),
                Int.MAX_VALUE,
                ClearingVisitor(canonicalBasePath, canonicalTargetPath)
            )
        } catch (_: IOException) {
            // Best effort: el caller decide el resultado de la restauración.
        } catch (_: SecurityException) {
            // Best effort: un nodo inaccesible no interrumpe el vaciado.
        }
    }

    /**
     * Vacía [root] borrando cada entrada en postorden y sin salir de la base.
     *
     * `walkFileTree` sin FOLLOW_LINKS solo entrega en [preVisitDirectory]
     * directorios reales: un enlace simbólico llega a [visitFile] y se borra
     * como entrada, sin descenderse. El directorio raíz ([root]) es el propio
     * destino y nunca se borra.
     */
    private class ClearingVisitor(
        private val canonicalBase: Path,
        private val root: Path
    ) : SimpleFileVisitor<Path>() {

        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (dir == root) return FileVisitResult.CONTINUE
            val canonicalDir = canonicalOrNull(dir)
            if (canonicalDir != null && canonicalDir.startsWith(canonicalBase)) {
                return FileVisitResult.CONTINUE
            }
            deleteEntry(dir)
            return FileVisitResult.SKIP_SUBTREE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            deleteEntry(file)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            if (file != root) deleteEntry(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (dir != root) deleteEntry(dir)
            return FileVisitResult.CONTINUE
        }
    }

    /** true si [path] es [base] o queda por debajo, comparando componentes. */
    private fun isAtOrUnder(path: Path, base: Path): Boolean =
        path == base || path.startsWith(base)

    private fun deleteEntry(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // Best effort: no se propaga ningún fallo de borrado.
        } catch (_: SecurityException) {
            // Best effort: no se propaga ningún fallo de permisos.
        }
    }

    private fun canonicalOrNull(file: File): File? = try {
        file.canonicalFile
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun canonicalOrNull(path: Path): Path? = canonicalOrNull(path.toFile())?.toPath()
}
