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
}
