package com.davidadrianrg.atril.core.sync.platform

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * SEC-007: un tar hostil descargado del servidor se rechaza en el parser sin
 * privilegios, antes de que el handler llegue a root.
 *
 * Se ejecuta sin `su`: el rechazo ocurre en [TarExtractor] y el handler retorna
 * `false` sin tocar la rama privilegiada. Los asserts son sobre el disco: los
 * saves existentes se conservan, el centinela exterior no cambia y no aparece
 * ningún árbol fuera de la carpeta de saves.
 *
 * Son tar reales (cabeceras ustar escritas bloque a bloque) para no depender de
 * la herramienta `tar` del host.
 */
class TarRestoreGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeOctal(block: ByteArray, offset: Int, length: Int, value: Long) {
        val digits = java.lang.Long.toOctalString(value)
        require(digits.length <= length - 1)
        val text = digits.padStart(length - 1, '0') + "\u0000"
        System.arraycopy(text.toByteArray(Charsets.US_ASCII), 0, block, offset, length)
    }

    private fun writeText(block: ByteArray, offset: Int, length: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= length)
        System.arraycopy(bytes, 0, block, offset, bytes.size)
    }

    private fun member(name: String, content: String): ByteArray {
        val data = content.toByteArray()
        val block = ByteArray(BLOCK)
        writeText(block, 0, 100, name)
        writeOctal(block, 100, 8, 493)
        writeOctal(block, 108, 8, 0)
        writeOctal(block, 116, 8, 0)
        writeOctal(block, 124, 12, data.size.toLong())
        writeOctal(block, 136, 12, 0)
        block[156] = '0'.code.toByte()
        writeText(block, 257, 6, "ustar\u0000")
        writeText(block, 263, 2, "00")
        var sum = 0L
        for (i in block.indices) {
            sum += if (i in 148..155) 0x20 else block[i].toInt() and 0xFF
        }
        writeText(block, 148, 8, String.format("%06o", sum) + "\u0000 ")

        val out = ByteArrayOutputStream()
        out.write(block)
        out.write(data)
        val padding = ((BLOCK - (data.size % BLOCK)) % BLOCK)
        repeat(padding) { out.write(0) }
        return out.toByteArray()
    }

    private fun hostileTar(vararg members: ByteArray): File {
        val file = tmp.newFile("hostile.tar")
        FileOutputStream(file).use { out ->
            members.forEach { out.write(it) }
            out.write(ByteArray(BLOCK * 2))
        }
        return file
    }

    private fun sentinel(): File = File(tmp.root, "sib/keep.bin").apply {
        parentFile!!.mkdirs()
        writeText("keep")
    }

    @Test
    fun `android handler rejects a hostile tar and keeps the existing save`() = runTest {
        val savesBase = tmp.newFolder("data/data/com.game/files")
        val existing = File(savesBase, "keep.sav").apply { writeText("keep-save") }
        val sentinel = sentinel()
        val archive = hostileTar(
            member("files/keep.sav", "staged"),
            member("files/../../sib/wiped/x", "wiped"),
        )
        assertFalse("el tar debe rechazarse en TarExtractor", TarExtractor.validate(archive))

        val ok = AndroidSaveHandler().extractDownload(
            tempFile = archive,
            romFileName = "com.game",
            platformSlug = "android",
            savesBasePath = savesBase.absolutePath,
            targetFileName = "com.game_android_save.tar",
        )

        assertFalse(ok)
        assertEquals("el save existente no se destruye", "keep-save", existing.readText())
        assertEquals("keep", sentinel.readText())
        assertFalse("no debe crearse el árbol fuera de saves", File(tmp.root, "sib/wiped").exists())
    }

    @Test
    fun `ps2 handler rejects a hostile tar and keeps the existing card`() = runTest {
        val memcards = tmp.newFolder("memcards")
        val save = File(memcards, "Shared.ps2/BASLUS-21050/save.bin").apply {
            parentFile!!.mkdirs()
            writeText("ps2-save")
        }
        val sentinel = sentinel()
        val archive = hostileTar(
            member("Shared.ps2/BASLUS-21050/save.bin", "staged"),
            member("../../sib/wiped/x", "wiped"),
        )
        assertFalse("el tar debe rechazarse en TarExtractor", TarExtractor.validate(archive))

        val ok = Ps2SaveHandler().extractDownload(
            tempFile = archive,
            romFileName = "SLUS-21050 - Shadow of the Colossus.iso",
            platformSlug = "ps2",
            savesBasePath = memcards.absolutePath,
            targetFileName = "BASLUS-21050_save.tar",
        )

        assertFalse(ok)
        assertEquals("el save existente no se destruye", "ps2-save", save.readText())
        assertEquals("keep", sentinel.readText())
        assertFalse("no debe crearse el árbol fuera de memcards", File(tmp.root, "sib/wiped").exists())
    }

    private companion object {
        const val BLOCK = 512
    }
}
