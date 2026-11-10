package com.davidadrianrg.atril.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * SEC-OOS-2: los nombres derivados del sistema de ficheros que se interpolan
 * en un comando de root (paquete Android, memory card o carpeta de serial de
 * PS2) deben ser un único token seguro y quedar canónicamente bajo una base de
 * confianza antes de que exista el comando.
 *
 * Las expectativas salen de la regla de producto (charset, longitud y primer
 * carácter alfanumérico) y se comprueban sin root: un nombre hostil hace que
 * `resolveUnder`/`tarCreateCommand` devuelvan null y el handler no llega a
 * ejecutar `su`.
 */
class RootCommandNamesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `rejects names that are unsafe inside a root command`() {
        listOf(
            "a'b",
            "a\nb",
            "a\tb",
            "a\u0000b",
            "..",
            ".",
            "a/b",
            "a\\b",
            "",
            " card",
            "-card",
            "café",
            "a".repeat(RootCommandNames.MAX_LENGTH + 1),
        ).forEach { name ->
            assertFalse("debe rechazar '$name'", RootCommandNames.isValid(name))
        }
        assertFalse(RootCommandNames.isValid(null))
    }

    @Test
    fun `accepts real filesystem names up to the maximum length`() {
        listOf(
            "com.example.game",
            "Shared.ps2",
            "BASLUS-21050",
            "BASLUS-21050GAME",
            "Mcd001.ps2",
            "My Save",
            "a".repeat(RootCommandNames.MAX_LENGTH),
        ).forEach { name ->
            assertTrue("debe aceptar '$name'", RootCommandNames.isValid(name))
        }
    }

    @Test
    fun `resolves a valid name strictly under the base`() {
        val base = tmp.newFolder("base")

        val resolved = RootCommandNames.resolveUnder(base, "My Save")

        assertEquals(File(base, "My Save").canonicalFile, resolved)
        assertEquals(base.canonicalFile, resolved!!.parentFile)
    }

    @Test
    fun `returns null for hostile names and for names that escape the base`() {
        val base = tmp.newFolder("base")

        listOf("..", "../x", ".", "a/b", "a\\b", "-card", "").forEach { name ->
            assertNull("debe devolver null para '$name'", RootCommandNames.resolveUnder(base, name))
        }
    }

    @Test
    fun `returns null when a symlink takes the folder outside the base`() {
        val base = tmp.newFolder("base-link")
        val outside = tmp.newFolder("outside-link")
        val link = base.toPath().resolve("escape")
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (e: Exception) {
            Assume.assumeTrue("el sistema de ficheros no admite enlaces simbólicos: ${e.message}", false)
        }

        assertNull(RootCommandNames.resolveUnder(base, "escape"))
    }

    @Test
    fun `builds the tar command for the single Android component`() {
        val base = tmp.newFolder("root")
        val archive = File(tmp.root, "out.tar")
        val member = File(base, "My Save").also { it.mkdirs() }

        val command = RootCommandNames.tarCreateCommand(archive, base, member)

        assertEquals(
            "tar -cf '${archive.absolutePath}' -C '${base.canonicalFile.path}' 'My Save'",
            command,
        )
    }

    @Test
    fun `builds the tar command for the two PS2 components`() {
        val memcards = tmp.newFolder("memcards")
        val card = File(memcards, "Shared.ps2").also { it.mkdirs() }
        val serial = File(card, "BASLUS-21050").also { it.mkdirs() }
        val archive = File(tmp.root, "ps2.tar")

        val command = RootCommandNames.tarCreateCommand(archive, memcards, serial)

        assertEquals(
            "tar -cf '${archive.absolutePath}' -C '${memcards.canonicalFile.path}' " +
                "'Shared.ps2/BASLUS-21050'",
            command,
        )
    }

    @Test
    fun `returns null when the member is outside the base or equals it`() {
        val base = tmp.newFolder("base-command")
        val archive = File(tmp.root, "out.tar")
        val outside = tmp.newFolder("outside-command")

        assertNull(RootCommandNames.tarCreateCommand(archive, base, outside))
        assertNull(RootCommandNames.tarCreateCommand(archive, base, base))
    }

    @Test
    fun `returns null when a path component fails the strict rule`() {
        val memcards = tmp.newFolder("memcards-invalid")
        val card = File(memcards, "Shared.ps2").also { it.mkdirs() }
        val hostileMember = File(card, "-card")
        val archive = File(tmp.root, "out.tar")

        assertNull(RootCommandNames.tarCreateCommand(archive, memcards, hostileMember))
    }
}
