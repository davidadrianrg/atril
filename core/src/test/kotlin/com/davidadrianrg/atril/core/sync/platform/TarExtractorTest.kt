package com.davidadrianrg.atril.core.sync.platform

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
 * SEC-007: valida el parser ustar que corre como el usuario de la app antes de
 * cualquier operación con root.
 *
 * Los archivos se construyen bloque a bloque (cabecera de 512 bytes + datos +
 * relleno) para poder generar entradas que ninguna herramienta tar legítima
 * produciría: rutas absolutas, `..`, enlaces, dispositivos, checksums rotos y
 * tamaños base-256. Los asserts son sobre el contrato observable: rechazo sin
 * escribir nada, staging vacío tras el fallo y un único directorio superior
 * tras el éxito.
 */
class TarExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeOctal(block: ByteArray, offset: Int, length: Int, value: Long) {
        val digits = java.lang.Long.toOctalString(value)
        require(digits.length <= length - 1) { "valor $value no cabe en $length bytes octales" }
        val text = digits.padStart(length - 1, '0') + "\u0000"
        System.arraycopy(text.toByteArray(Charsets.US_ASCII), 0, block, offset, length)
    }

    private fun writeText(block: ByteArray, offset: Int, length: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= length) { "'$text' no cabe en $length bytes" }
        System.arraycopy(bytes, 0, block, offset, bytes.size)
    }

    private fun header(
        name: String,
        size: Long = 0,
        type: Char = '0',
        linkName: String = "",
        prefix: String = "",
        badChecksum: Boolean = false,
        base256Size: Boolean = false,
    ): ByteArray {
        val block = ByteArray(BLOCK)
        writeText(block, 0, 100, name)
        writeOctal(block, 100, 8, 493) // 0755
        writeOctal(block, 108, 8, 0)
        writeOctal(block, 116, 8, 0)
        writeOctal(block, 124, 12, size)
        writeOctal(block, 136, 12, 0)
        block[156] = type.code.toByte()
        writeText(block, 157, 100, linkName)
        writeText(block, 257, 6, "ustar\u0000")
        writeText(block, 263, 2, "00")
        writeText(block, 345, 155, prefix)
        if (base256Size) block[124] = 0x80.toByte()

        if (badChecksum) {
            writeText(block, 148, 8, "0000000\u0000")
        } else {
            var sum = 0L
            for (i in block.indices) {
                sum += if (i in 148..155) 0x20 else block[i].toInt() and 0xFF
            }
            writeText(block, 148, 8, String.format("%06o", sum) + "\u0000 ")
        }
        return block
    }

    private fun member(
        name: String,
        content: String = "",
        type: Char = '0',
        linkName: String = "",
        prefix: String = "",
        badChecksum: Boolean = false,
        base256Size: Boolean = false,
        declaredSize: Long = content.toByteArray().size.toLong(),
        truncateData: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header(name, declaredSize, type, linkName, prefix, badChecksum, base256Size))
        if (type == '0' || type == '7') {
            val bytes = content.toByteArray()
            if (truncateData) {
                out.write(bytes)
            } else {
                out.write(bytes)
                val padding = ((BLOCK - (declaredSize % BLOCK)) % BLOCK).toInt()
                repeat(padding) { out.write(0) }
            }
        }
        return out.toByteArray()
    }

    private fun tar(vararg members: ByteArray): File {
        val file = tmp.newFile()
        FileOutputStream(file).use { out ->
            members.forEach { out.write(it) }
            out.write(ByteArray(BLOCK * 2))
        }
        return file
    }

    private fun stage(): File = tmp.newFolder()

    private fun assertRejected(archive: File) {
        assertFalse("validate debe rechazar el tar hostil", TarExtractor.validate(archive))
        val staging = stage()
        assertFalse("extract debe rechazar el tar hostil", TarExtractor.extract(archive, staging))
        assertTrue("el staging debe quedar vacío", staging.list()!!.isEmpty())
    }

    @Test
    fun `extracts a valid single top directory archive`() {
        val staging = tmp.newFolder("stage")
        val archive = tar(
            member("top/", type = '5'),
            member("top/file.txt", "hello"),
            member("top/sub/deep.bin", "deep"),
        )

        assertTrue(TarExtractor.validate(archive))
        assertTrue(TarExtractor.extract(archive, staging))

        assertEquals("hello", File(staging, "top/file.txt").readText())
        assertEquals("deep", File(staging, "top/sub/deep.bin").readText())
        val children = staging.listFiles()!!
        assertEquals("exactamente un directorio superior", 1, children.size)
        assertTrue(children[0].isDirectory)
        assertEquals("top", children[0].name)
    }

    @Test
    fun `rejects an absolute member path`() {
        val absolute = File(tmp.root, "absolute-evil.bin").absolutePath
        val archive = tar(member(absolute, "x"))

        assertRejected(archive)
        assertFalse("la ruta absoluta no debe crearse", File(absolute).exists())
    }

    @Test
    fun `rejects a dot dot member path`() {
        val sentinel = File(tmp.root, "outside/keep.bin").apply {
            parentFile!!.mkdirs()
            writeText("keep")
        }
        val archive = tar(member("../escape.txt", "x"))

        assertRejected(archive)
        assertFalse(File(tmp.root, "escape.txt").exists())
        assertEquals("keep", sentinel.readText())
    }

    @Test
    fun `rejects a nested dot dot member path`() {
        val sentinel = File(tmp.root, "outside/keep.bin").apply {
            parentFile!!.mkdirs()
            writeText("keep")
        }
        val archive = tar(member("top/../../escape.txt", "x"))

        assertRejected(archive)
        assertFalse(File(tmp.root, "escape.txt").exists())
        assertEquals("keep", sentinel.readText())
    }

    @Test
    fun `rejects a symlink member without creating the link`() {
        val staging = stage()
        val archive = tar(member("top/link", type = '2', linkName = "/etc/passwd"))

        assertFalse(TarExtractor.validate(archive))
        assertFalse(TarExtractor.extract(archive, staging))
        assertTrue(staging.list()!!.isEmpty())
        assertFalse(File(staging, "top/link").exists())
    }

    @Test
    fun `rejects hardlink char block fifo pax and gnu longname members`() {
        listOf(
            '1' to "hardlink",
            '3' to "char device",
            '4' to "block device",
            '6' to "fifo",
            'x' to "pax header",
            'L' to "gnu long name",
        ).forEach { (type, label) ->
            val archive = tar(member("top/member", type = type, linkName = "/etc/passwd"))
            assertFalse("debe rechazar $label", TarExtractor.validate(archive))
        }
    }

    @Test
    fun `rejects a header with a bad checksum`() {
        val archive = tar(member("top/file.txt", "hello", badChecksum = true))

        assertRejected(archive)
    }

    @Test
    fun `rejects members with different top level directories`() {
        val archive = tar(
            member("a/x", "1"),
            member("b/y", "2"),
        )

        assertRejected(archive)
    }

    @Test
    fun `rejects a member name containing NUL`() {
        val archive = tar(member("top/fi\u0000le.txt", "x"))

        assertRejected(archive)
    }

    @Test
    fun `rejects a truncated final member`() {
        val archive = tar(member("top/big.bin", "only-a-few-bytes", declaredSize = 2048, truncateData = true))

        assertRejected(archive)
    }

    @Test
    fun `rejects a base-256 size field`() {
        val archive = tar(member("top/file.txt", "hello", base256Size = true))

        assertRejected(archive)
    }

    @Test
    fun `rejects an archive without any member`() {
        val archive = tar()

        assertRejected(archive)
    }

    private companion object {
        const val BLOCK = 512
    }
}
