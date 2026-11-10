package com.davidadrianrg.atril.core.sync.platform

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regresión de SEC-005 (y las cuatro rutas de la misma clase): una entrada de
 * zip con `..` o una ruta absoluta no puede escribir en el directorio hermano
 * `savesX` que comparte prefijo con la carpeta de saves.
 *
 * Los zips se construyen entrada a entrada con [ZipOutputStream] porque son
 * archivos hostiles: `zipFolderDeterministic` nunca generaría esos nombres.
 * Los asserts son sobre el disco observable: el hermano no existe, el centinela
 * exterior conserva su contenido y los saves legítimos siguen restaurándose.
 */
class ArchiveTraversalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sentinel(): File = File(tmp.root, "outside/keep.bin").apply {
        parentFile!!.mkdirs()
        writeText("keep")
    }

    private fun assertSentinelIntact(sentinel: File) {
        assertTrue("el centinela exterior debe seguir existiendo", sentinel.isFile)
        assertEquals("keep", sentinel.readText())
    }

    /** Construye un zip real con las entradas (nombre, contenido) indicadas. */
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

    private fun write(dir: File, relativePath: String, content: String): File {
        val file = File(dir, relativePath)
        file.parentFile!!.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun `android zip cannot create the sibling savesX directory`() = runTest {
        val base = tmp.newFolder("saves")
        val sentinel = sentinel()
        val archive = zip(
            "x/../../savesX/y" to "escaped",
            "../savesX/keep.bin" to "overwritten",
            "root/safe.srm" to "safe",
        )

        val ok = AndroidSaveHandler().extractDownload(
            tempFile = archive,
            romFileName = "Game.gba",
            platformSlug = "gba",
            savesBasePath = base.absolutePath,
            targetFileName = "Game_android_save.zip",
        )

        assertTrue(ok)
        assertFalse("no debe crearse el hermano /savesX", File(tmp.root, "savesX").exists())
        assertSentinelIntact(sentinel)
        assertEquals("safe", File(base, "safe.srm").readText())
    }

    @Test
    fun `cemu zip cannot create the sibling savesX directory`() = runTest {
        val base = tmp.newFolder("cemu/00050000")
        val sentinel = sentinel()
        val archive = zip(
            "10101D00/../../../savesX/y" to "escaped",
            "../savesX/y" to "overwritten",
            "10101D00/user/save.bin" to "cemu",
        )

        val ok = CemuSaveHandler().extractDownload(
            tempFile = archive,
            romFileName = "[0005000010101D00] Game.wux",
            platformSlug = "wiiu",
            savesBasePath = base.absolutePath,
            targetFileName = "0005000010101D00_wiiu_save.zip",
        )

        assertTrue(ok)
        assertFalse(File(tmp.root, "savesX").exists())
        assertFalse(File(base, "savesX").exists())
        assertSentinelIntact(sentinel)
        assertEquals("cemu", File(base, "10101D00/user/save.bin").readText())
    }

    @Test
    fun `n3ds zip cannot create the sibling savesX directory`() = runTest {
        val base = tmp.newFolder("3ds")
        val id0 = tmp.newFolder("3ds/id0")
        File(id0, "id1").mkdirs()
        val sentinel = sentinel()
        val archive = zip(
            "../savesX/y" to "escaped",
            "data/save.bin" to "3ds",
        )

        val ok = N3dsSaveHandler().extractDownload(
            tempFile = archive,
            romFileName = "[0004000000030200] Game.3ds",
            platformSlug = "3ds",
            savesBasePath = base.absolutePath,
            targetFileName = "0004000000030200_3ds_save.zip",
        )

        assertTrue(ok)
        assertFalse(File(tmp.root, "savesX").exists())
        assertFalse(File(id0, "id1/title/00040000/00030200/savesX").exists())
        assertSentinelIntact(sentinel)
        assertEquals("3ds", File(id0, "id1/title/00040000/00030200/data/save.bin").readText())
    }

    @Test
    fun `ppsspp zip cannot create the sibling savesX directory`() = runTest {
        val base = tmp.newFolder("PSP/SAVEDATA")
        val sentinel = sentinel()
        val archive = zip(
            "../savesX/y" to "escaped",
            "ULUS10064DATA/save.bin" to "psp",
        )

        val ok = PpssppSaveHandler().extractDownload(
            tempFile = archive,
            romFileName = "ULUS10064 - Game.iso",
            platformSlug = "psp",
            savesBasePath = base.absolutePath,
            targetFileName = "ULUS10064_saves.zip",
        )

        assertTrue(ok)
        assertFalse(File(tmp.root, "PSP/savesX").exists())
        assertSentinelIntact(sentinel)
        assertEquals("psp", File(base, "ULUS10064DATA/save.bin").readText())
    }

    @Test
    fun `ps2 zip cannot create the sibling savesX directory`() = runTest {
        val base = tmp.newFolder("memcards")
        val sentinel = sentinel()
        val archive = zip(
            "../savesX/y" to "escaped",
            "BASLUS-21050/save.bin" to "ps2",
        )

        val ok = Ps2SaveHandler().extractDownload(
            tempFile = archive,
            romFileName = "SLUS-21050 - Shadow of the Colossus.iso",
            platformSlug = "ps2",
            savesBasePath = base.absolutePath,
            targetFileName = "BASLUS-21050_save.zip",
        )

        assertTrue(ok)
        assertFalse("no debe crearse el hermano savesX", File(base, "savesX").exists())
        assertFalse(File(tmp.root, "savesX").exists())
        assertSentinelIntact(sentinel)
        assertEquals("ps2", File(base, "Shared.ps2/BASLUS-21050/save.bin").readText())
    }

    @Test
    fun `switch zip cannot create the sibling savesX directory`() = runTest {
        val base = tmp.newFolder("save")
        val sentinel = sentinel()
        val archive = zip(
            "0100000000010000/../savesX/y" to "escaped",
            "../savesX/y" to "overwritten",
            "0100000000010000/save.bin" to "switch",
        )

        val ok = SwitchSaveHandler().extractDownload(
            tempFile = archive,
            romFileName = "[0100000000010000] Super Mario Odyssey.nsp",
            platformSlug = "switch",
            savesBasePath = base.absolutePath,
            targetFileName = "0100000000010000_switch_save.zip",
        )

        assertTrue(ok)
        val userRoot = File(base, "0000000000000000/0000000000000001")
        assertFalse(File(userRoot, "savesX").exists())
        assertFalse(File(tmp.root, "savesX").exists())
        assertSentinelIntact(sentinel)
        assertEquals("switch", File(userRoot, "0100000000010000/save.bin").readText())
    }

    @Test
    fun `dolphin gamecube zip cannot create the sibling savesX directory`() = runTest {
        val base = tmp.newFolder("GC")
        val sentinel = sentinel()
        val archive = zip(
            "../savesX/y" to "escaped",
            "GALE01-Super Smash Bros Melee.gci" to "gc",
        )

        val ok = DolphinSaveHandler().extractDownload(
            tempFile = archive,
            romFileName = "GALE01 - Super Smash Bros Melee.iso",
            platformSlug = "gc",
            savesBasePath = base.absolutePath,
            targetFileName = "GALE01_gc_saves.zip",
        )

        assertTrue(ok)
        assertFalse(File(base, "USA/savesX").exists())
        assertFalse(File(tmp.root, "savesX").exists())
        assertSentinelIntact(sentinel)
        assertEquals("gc", File(base, "USA/Card A/GALE01-Super Smash Bros Melee.gci").readText())
    }

    @Test
    fun `dolphin wii zip cannot create the sibling savesX directory`() = runTest {
        val base = tmp.newFolder("Wii/title")
        val sentinel = sentinel()
        val archive = zip(
            "../savesX/y" to "escaped",
            "00010000/GALE01/data/save.bin" to "wii",
        )

        val ok = DolphinSaveHandler().extractDownload(
            tempFile = archive,
            romFileName = "GALE01 - Super Smash Bros Melee.iso",
            platformSlug = "wii",
            savesBasePath = base.absolutePath,
            targetFileName = "GALE01_wii_save.zip",
        )

        assertTrue(ok)
        assertFalse(File(tmp.root, "Wii/savesX").exists())
        assertSentinelIntact(sentinel)
        assertEquals("wii", File(base, "00010000/GALE01/data/save.bin").readText())
    }

    // ── SEC-004: la carpeta de destino del save PS3 no puede escapar ────────

    private fun seedPs3Saves(): Pair<File, File> {
        val hdd = tmp.newFolder("dev_hdd0")
        val saveDir = File(hdd, "home/00000001/savedata/BLUS30181USERDATA")
        write(saveDir, "PARAM.SFO", "sfo")
        write(saveDir, "USRDIR/DATA.BIN", "bin")
        return hdd to saveDir
    }

    @Test
    fun `ps3 first entry dot dot cannot wipe a tree outside savedata`() = runTest {
        val (hdd, saveDir) = seedPs3Saves()
        val sentinel = sentinel()
        val archive = zip(
            "../../wiped/x" to "wiped",
            "BLUS30181USERDATA/PARAM.SFO" to "replaced",
        )

        val ok = Ps3SaveHandler().extractDownload(
            tempFile = archive,
            romFileName = "BLUS-30181 - Demon's Souls.pkg",
            platformSlug = "ps3",
            savesBasePath = hdd.absolutePath,
            targetFileName = "BLUS-30181_ps3_save.zip",
        )

        assertFalse(ok)
        assertFalse("no debe crearse <tmp>/wiped", File(tmp.root, "wiped").exists())
        assertEquals("el savedata existente no se toca", "sfo", File(saveDir, "PARAM.SFO").readText())
        assertEquals("bin", File(saveDir, "USRDIR/DATA.BIN").readText())
        assertSentinelIntact(sentinel)
    }

    @Test
    fun `ps3 absolute first entry is rejected without touching the disk`() = runTest {
        val (hdd, saveDir) = seedPs3Saves()
        val sentinel = sentinel()
        val archive = zip(
            "/abs/x" to "absolute",
            "BLUS30181USERDATA/PARAM.SFO" to "replaced",
        )

        val ok = Ps3SaveHandler().extractDownload(
            tempFile = archive,
            romFileName = "BLUS-30181 - Demon's Souls.pkg",
            platformSlug = "ps3",
            savesBasePath = hdd.absolutePath,
            targetFileName = "BLUS-30181_ps3_save.zip",
        )

        assertFalse(ok)
        assertFalse(File(tmp.root, "abs").exists())
        assertEquals("sfo", File(saveDir, "PARAM.SFO").readText())
        assertEquals("bin", File(saveDir, "USRDIR/DATA.BIN").readText())
        assertSentinelIntact(sentinel)
    }

    @Test
    fun `ps3 legitimate archive still restores the savedata folder`() = runTest {
        val hdd = tmp.newFolder("dev_hdd0_ok")
        val source = tmp.newFolder("BLUS30181USERDATA")
        write(source, "PARAM.SFO", "sfo")
        write(source, "USRDIR/DATA.BIN", "bin")
        val archive = tmp.newFile("legit_ps3.zip")
        zipFolderDeterministic(source, archive)

        val ok = Ps3SaveHandler().extractDownload(
            tempFile = archive,
            romFileName = "BLUS-30181 - Demon's Souls.pkg",
            platformSlug = "ps3",
            savesBasePath = hdd.absolutePath,
            targetFileName = "BLUS-30181_ps3_save.zip",
        )

        assertTrue(ok)
        val root = File(hdd, "home/00000001/savedata/BLUS30181USERDATA")
        assertEquals("sfo", File(root, "PARAM.SFO").readText())
        assertEquals("bin", File(root, "USRDIR/DATA.BIN").readText())
    }
}
