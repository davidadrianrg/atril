package com.davidadrianrg.atril.core.sync.platform

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * SEC-OOS-3: la restauración de saves vacía el destino con contención canónica.
 * Un hijo (o el propio destino) enlazado a una carpeta exterior no puede hacer
 * que el borrado recursivo siga el enlace y destruya contenido ajeno.
 *
 * Se invocan los handlers reales sin root (rutas accesibles con la API File).
 * Los enlaces simbólicos se crean de verdad y los casos se omiten con [Assume]
 * si el sistema de ficheros no los soporta. Los asserts son sobre el disco.
 */
class SafeRestoreContainmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun symlink(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: Exception) {
            Assume.assumeTrue("el sistema de ficheros no admite enlaces simbólicos: ${e.message}", false)
        }
    }

    private fun zip(vararg entries: Pair<String, String>): File {
        val out = tmp.newFile()
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return out
    }

    @Test
    fun `android restore removes an escaping child link and keeps the outside folder intact`() = runTest {
        val base = tmp.newFolder("android/saves")
        val outside = tmp.newFolder("android/outside")
        File(outside, "keep.bin").writeText("keep")
        File(base, "stale.bin").writeText("stale")
        val escape = File(base, "escape")
        symlink(escape, outside)

        val ok = AndroidSaveHandler().extractDownload(
            tempFile = zip("legit.sav" to "save-data"),
            romFileName = "Game.gba",
            platformSlug = "gba",
            savesBasePath = base.absolutePath,
            targetFileName = "Game_android_save.zip",
        )

        assertTrue(ok)
        assertEquals("el exterior no se toca", "keep", File(outside, "keep.bin").readText())
        assertFalse(Files.exists(escape.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse("el save previo se sustituye", File(base, "stale.bin").exists())
        assertEquals("save-data", File(base, "legit.sav").readText())
    }

    @Test
    fun `android restore ignores a nested escaping link inside the saves tree`() = runTest {
        val base = tmp.newFolder("android-nested/saves")
        val outside = tmp.newFolder("android-nested/outside")
        File(outside, "keep.bin").writeText("keep")
        File(outside, "nested/deep.bin").apply { parentFile!!.mkdirs(); writeText("deep") }
        val nestedDir = File(base, "sub").also { it.mkdirs() }
        val escape = File(nestedDir, "evil")
        symlink(escape, outside)

        val ok = AndroidSaveHandler().extractDownload(
            tempFile = zip("legit.sav" to "save-data"),
            romFileName = "Game.gba",
            platformSlug = "gba",
            savesBasePath = base.absolutePath,
            targetFileName = "Game_android_save.zip",
        )

        assertTrue(ok)
        assertEquals("el exterior no se toca", "keep", File(outside, "keep.bin").readText())
        assertEquals("el subárbol exterior se conserva", "deep", File(outside, "nested/deep.bin").readText())
        assertFalse("el enlace anidado se elimina", Files.exists(escape.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse("el contenedor del enlace se vacía", nestedDir.exists())
        assertEquals("save-data", File(base, "legit.sav").readText())
        assertTrue("el directorio de saves sigue en pie", base.isDirectory)
    }

    @Test
    fun `ps2 restore empties the serial folder without following a child link outside the card`() = runTest {
        val memcards = tmp.newFolder("memcards")
        val card = File(memcards, "Shared.ps2").also { it.mkdirs() }
        val serialDir = File(card, "BASLUS-21050").also { it.mkdirs() }
        File(serialDir, "old.bin").writeText("old")
        val outside = tmp.newFolder("ps2-outside")
        File(outside, "keep.bin").writeText("keep")
        val escape = File(serialDir, "escape")
        symlink(escape, outside)

        val ok = Ps2SaveHandler().extractDownload(
            tempFile = zip("BASLUS-21050/save.bin" to "ps2-save"),
            romFileName = "SLUS-21050 - Shadow of the Colossus.iso",
            platformSlug = "ps2",
            savesBasePath = memcards.absolutePath,
            targetFileName = "BASLUS-21050_save.zip",
        )

        assertTrue(ok)
        assertEquals("el exterior no se toca", "keep", File(outside, "keep.bin").readText())
        assertFalse(Files.exists(escape.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse("el contenido anterior del serial se reemplaza", File(serialDir, "old.bin").exists())
        assertEquals("ps2-save", File(serialDir, "save.bin").readText())
        assertTrue("la card se conserva para otros juegos", card.isDirectory)
    }

    @Test
    fun `ppsspp restore replaces a disc-id folder that links outside without deleting its contents`() = runTest {
        val base = tmp.newFolder("PSP/SAVEDATA")
        val outside = tmp.newFolder("psp-outside")
        File(outside, "keep.bin").writeText("keep")
        val linkedFolder = File(base, "ULUS10064DATA")
        symlink(linkedFolder, outside)

        val ok = PpssppSaveHandler().extractDownload(
            tempFile = zip("ULUS10064DATA/save.bin" to "psp-save"),
            romFileName = "ULUS10064 - Game.iso",
            platformSlug = "psp",
            savesBasePath = base.absolutePath,
            targetFileName = "ULUS10064_saves.zip",
        )

        assertTrue(ok)
        assertEquals("el exterior no se toca", "keep", File(outside, "keep.bin").readText())
        assertFalse("la carpeta restaurada es real, no un enlace", Files.isSymbolicLink(linkedFolder.toPath()))
        assertEquals("psp-save", File(base, "ULUS10064DATA/save.bin").readText())
    }

    @Test
    fun `a configured path with dot dot still restores inside the base and touches nothing outside`() = runTest {
        val root = tmp.newFolder("config")
        val base = tmp.newFolder("config/saves")
        File(base, "stale.bin").writeText("stale")
        val outside = File(root, "keep.bin").apply { writeText("keep") }
        val pathWithDotDot = File(root, "saves/../saves").absolutePath

        val ok = AndroidSaveHandler().extractDownload(
            tempFile = zip("restored.sav" to "restored"),
            romFileName = "Game.gba",
            platformSlug = "gba",
            savesBasePath = pathWithDotDot,
            targetFileName = "Game_android_save.zip",
        )

        assertTrue(ok)
        assertEquals("el fichero fuera de la base no se toca", "keep", outside.readText())
        assertEquals("restored", File(base, "restored.sav").readText())
        assertFalse(File(base, "stale.bin").exists())
    }
}
