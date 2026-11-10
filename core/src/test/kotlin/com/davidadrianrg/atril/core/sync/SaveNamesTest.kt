package com.davidadrianrg.atril.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Nombres de fichero de save que llegan del servidor.
 *
 * El nombre es dato no confiable: la regla de producto es que solo un
 * componente simple puede convertirse en ruta dentro de la carpeta de saves.
 * Los valores esperados salen de esa regla, no de la implementación.
 */
class SaveNamesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `strips the slot version tag before the extension`() {
        assertEquals(
            "Pokemon - Edicion Azul.srm",
            SaveNames.stripVersionTag("Pokemon - Edicion Azul [2026-11-01_12-34-56].srm"),
        )
    }

    @Test
    fun `keeps plain file names untouched`() {
        assertEquals("Metroid Fusion.sav", SaveNames.stripVersionTag("Metroid Fusion.sav"))
    }

    @Test
    fun `strips tags from zip saves`() {
        assertEquals(
            "Dolphin Save.zip",
            SaveNames.stripVersionTag("Dolphin Save [2026-10-31_08-00-00].zip"),
        )
    }

    @Test
    fun `does not strip a timestamp that is part of the name`() {
        assertEquals(
            "Game [2026-11-01] notes.srm",
            SaveNames.stripVersionTag("Game [2026-11-01] notes.srm"),
        )
    }

    @Test
    fun `accepts ordinary save file names`() {
        listOf(
            "game.srm",
            "Game (USA).sav",
            "Pokemon - Edicion Azul.srm",
            "Metroid Fusion [2026-11-01_12-34-56].srm",
            "Juego con acentos áéíóú.srm",
            "name.with.dots.srm",
            "save - copy.srm",
            "  leading and trailing spaces.srm  ",
        ).forEach { name ->
            assertTrue("debe aceptar '$name'", SaveNames.isSafeSaveFileName(name))
        }
    }

    @Test
    fun `rejects blank names`() {
        assertFalse(SaveNames.isSafeSaveFileName(""))
        assertFalse(SaveNames.isSafeSaveFileName("   "))
        assertFalse(SaveNames.isSafeSaveFileName("\t\n"))
    }

    @Test
    fun `rejects absolute paths`() {
        listOf(
            "/etc/passwd",
            "/sdcard/save.srm",
            "C:\\x",
            "C:\\saves\\game.srm",
            "\\\\server\\share\\save.srm",
        ).forEach { name ->
            assertFalse("debe rechazar '$name'", SaveNames.isSafeSaveFileName(name))
        }
    }

    @Test
    fun `rejects any name with a path separator`() {
        listOf(
            "sub/game.srm",
            "sub\\game.srm",
            "../x",
            "..\\x",
            "a/../b",
            "a\\..\\b",
            "game.srm/",
        ).forEach { name ->
            assertFalse("debe rechazar '$name'", SaveNames.isSafeSaveFileName(name))
        }
    }

    @Test
    fun `rejects dot and dot dot as the whole name`() {
        assertFalse(SaveNames.isSafeSaveFileName("."))
        assertFalse(SaveNames.isSafeSaveFileName(".."))
    }

    @Test
    fun `rejects a name containing a NUL byte`() {
        assertFalse(SaveNames.isSafeSaveFileName("game\u0000.srm"))
        assertFalse(SaveNames.isSafeSaveFileName("game.srm\u0000"))
    }

    @Test
    fun `resolves a safe name inside the canonical directory`() {
        val dir = temp.newFolder("saves")

        val target = SaveNames.resolveInsideDirectory(dir, "game.srm")

        assertEquals(File(dir, "game.srm").canonicalFile, target)
        assertEquals(dir.canonicalFile, target!!.parentFile)
        assertTrue(target.canonicalPath.startsWith(dir.canonicalPath + File.separator))
    }

    @Test
    fun `returns null for unsafe names`() {
        val dir = temp.newFolder("saves")

        listOf("", "  ", "/etc/passwd", "C:\\x", "\\\\server\\share", "a/b.srm", "a\\b.srm", ".", "..", "../x", "game\u0000.srm")
            .forEach { name ->
                assertNull("debe devolver null para '$name'", SaveNames.resolveInsideDirectory(dir, name))
            }
    }

    @Test
    fun `returns null when a symlink would escape the directory`() {
        val dir = temp.newFolder("saves")
        val outside = temp.newFile("outside.srm")
        val link = dir.toPath().resolve("escape.srm")
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (e: UnsupportedOperationException) {
            org.junit.Assume.assumeTrue("el sistema de ficheros no admite enlaces simbólicos", false)
            return
        }

        assertNull(SaveNames.resolveInsideDirectory(dir, "escape.srm"))
    }

    // ── Archive entry resolver (SEC-005, shared by every zip/tar site) ─────

    @Test
    fun `accepts nested relative archive entry paths`() {
        listOf(
            "a/b/c.srm",
            "a/",
            "a//b",
            "./a",
            "top/sub/deep.bin",
            "BLUS30181USERDATA/USRDIR/DATA.BIN",
        ).forEach { entry ->
            assertTrue("debe aceptar '$entry'", SaveNames.isSafeArchiveEntryPath(entry))
        }
    }

    @Test
    fun `rejects traversal absolute NUL and backslash archive entries`() {
        listOf(
            "../x",
            "../savesX/y",
            "/abs/x",
            "a/../../x",
            "a\\..\\x",
            "..\\savesX\\y",
            "game\u0000.srm",
            "",
            "   ",
            "C:/x",
            "C:\\x",
        ).forEach { entry ->
            assertFalse("debe rechazar '$entry'", SaveNames.isSafeArchiveEntryPath(entry))
        }
    }

    @Test
    fun `resolves safe nested entries inside the canonical base`() {
        val base = temp.newFolder("archive-base")

        val nested = SaveNames.resolveArchiveEntry(base, "a/b/c.srm")
        assertEquals(File(base, "a/b/c.srm").canonicalFile, nested)
        assertTrue(nested!!.canonicalPath.startsWith(base.canonicalPath + File.separator))

        assertEquals(File(base, "a").canonicalFile, SaveNames.resolveArchiveEntry(base, "a/"))
        assertEquals(File(base, "a/b").canonicalFile, SaveNames.resolveArchiveEntry(base, "a//b"))
        assertEquals(File(base, "a").canonicalFile, SaveNames.resolveArchiveEntry(base, "./a"))
    }

    @Test
    fun `archive resolver rejects dot and never returns the base itself`() {
        val base = temp.newFolder("archive-dot")

        assertNull(SaveNames.resolveArchiveEntry(base, "."))
        assertNull(SaveNames.resolveArchiveEntry(base, "./"))
    }

    @Test
    fun `archive resolver rejects the sibling prefix escape`() {
        val base = temp.newFolder("saves")
        val sibling = temp.newFolder("savesX")
        File(sibling, "keep.bin").writeText("keep")

        listOf("../savesX/y", "a/../../savesX/y", "/${sibling.absolutePath}/y").forEach { entry ->
            assertNull("debe rechazar '$entry'", SaveNames.resolveArchiveEntry(base, entry))
        }
        assertEquals("keep", File(sibling, "keep.bin").readText())
        assertFalse(File(sibling, "y").exists())
    }

    @Test
    fun `archive resolver returns null when a nested symlink would escape`() {
        val base = temp.newFolder("archive-saves")
        val outside = temp.newFolder("archive-outside")
        File(outside, "x").writeText("outside")
        val link = base.toPath().resolve("link")
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (e: Exception) {
            org.junit.Assume.assumeTrue("el sistema de ficheros no admite enlaces simbólicos", false)
            return
        }

        assertNull(SaveNames.resolveArchiveEntry(base, "link/x"))
    }
}
