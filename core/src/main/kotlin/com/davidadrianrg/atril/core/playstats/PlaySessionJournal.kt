package com.davidadrianrg.atril.core.playstats

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persistencia de la partida abierta (B3/F9). En Android el sistema puede
 * matar Atril mientras el emulador está delante; sin esto, al volver se
 * perdían el tiempo jugado y la «última partida».
 */
interface PlaySessionJournal {
    data class OpenSession(val romId: Int, val startedAt: Long)

    fun read(): OpenSession?
    fun write(session: OpenSession)
    fun clear()
}

/**
 * Diario en un fichero de una línea (`romId:startedAt`), escrito de forma
 * atómica. Un fichero ilegible se trata como «sin sesión»: nunca bloquea el
 * arranque. Común a Android y escritorio.
 */
class FilePlaySessionJournal(private val file: File) : PlaySessionJournal {

    override fun read(): PlaySessionJournal.OpenSession? = runCatching {
        if (!file.isFile) return null
        val (id, started) = file.readText().trim().split(':', limit = 2)
        PlaySessionJournal.OpenSession(id.toInt(), started.toLong())
    }.getOrNull()

    override fun write(session: PlaySessionJournal.OpenSession) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText("${session.romId}:${session.startedAt}")
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }
}
