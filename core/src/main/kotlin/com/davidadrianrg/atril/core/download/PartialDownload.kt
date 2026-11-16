package com.davidadrianrg.atril.core.download

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Ciclo de vida del fichero parcial de una descarga de ROM, común a Android y
 * escritorio.
 *
 * Los bytes se escriben en `<nombre>.part` y solo se renombran al nombre final
 * cuando la descarga terminó y pasó la verificación de hash. Así un ROM
 * truncado nunca tiene el nombre que buscan ES-DE, los emuladores o el escaneo
 * de biblioteca.
 *
 * Compatibilidad: las versiones anteriores escribían el parcial con el nombre
 * final. Si no hay `.part` pero sí un fichero final, se usa como punto de
 * reanudación: un `416` confirma que ya estaba completo y un `206` lo mueve a
 * `.part` antes de añadir bytes ([prepareAppend]).
 */
object PartialDownload {

    const val SUFFIX = ".part"

    /** Fichero parcial que corresponde a [target]. */
    fun partFileFor(target: File): File = File(target.parentFile, target.name + SUFFIX)

    /**
     * Desde dónde reanudar.
     *
     * @property file fichero que contiene los [bytes] ya descargados.
     * @property bytes tamaño a pedir con `Range`; 0 = descarga desde cero.
     */
    data class ResumePoint(val file: File, val bytes: Long)

    /**
     * Punto de reanudación para [target]: el `.part` si tiene datos; si no, el
     * fichero final heredado; si no hay nada, el `.part` vacío.
     */
    fun resumePoint(target: File): ResumePoint {
        val part = partFileFor(target)
        if (part.isFile && part.length() > 0L) return ResumePoint(part, part.length())
        if (target.isFile && target.length() > 0L) return ResumePoint(target, target.length())
        return ResumePoint(part, 0L)
    }

    /**
     * Prepara el fichero en el que se escribirá la respuesta y lo devuelve
     * (siempre el `.part`).
     *
     * - [resume] (`206`): los bytes nuevos se añaden a los de [point]; si
     *   estaban en el fichero final heredado se mueven antes a `.part`.
     * - Sin [resume] (`200`): se empieza de cero; se descarta un `.part` viejo
     *   y el fichero final (si lo hay) sigue intacto hasta [commit].
     */
    fun prepareAppend(point: ResumePoint, target: File, resume: Boolean): File {
        val part = partFileFor(target)
        if (resume) {
            if (point.file != part) move(point.file, part)
        } else if (part.exists() && !part.delete()) {
            throw IOException("Cannot discard stale partial ${part.name}")
        }
        return part
    }

    /**
     * Publica [part] como [target] de forma atómica cuando el sistema de
     * ficheros lo permite (sustituye un [target] anterior). Si [part] ya es
     * [target] (fichero heredado confirmado con `416`) no hace nada.
     */
    fun commit(part: File, target: File) {
        if (part.absoluteFile == target.absoluteFile) return
        move(part, target)
    }

    /** Borra el parcial de [target] (cancelación del usuario, hash erróneo). */
    fun discard(target: File): Boolean {
        val part = partFileFor(target)
        return !part.isFile || part.delete()
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(
                from.toPath(), to.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
