package com.davidadrianrg.atril.core.sync.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * REV-002: los helpers de zipeado deben emitir nombres con separador
 * invariante (`/`) para que el resolvedor de entradas (que rechaza `\`) acepte
 * los saves legítimos también en un build de Windows, donde `Path.toString()`
 * usaría `\`.
 */
class DeterministicZipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(dir: File, relativePath: String, content: String): File {
        val file = File(dir, relativePath)
        file.parentFile!!.mkdirs()
        file.writeText(content)
        return file
    }

    private fun entryNames(zip: File): List<String> = ZipInputStream(FileInputStream(zip)).use { zis ->
        val names = mutableListOf<String>()
        var entry = zis.nextEntry
        while (entry != null) {
            names.add(entry.name)
            zis.closeEntry()
            entry = zis.nextEntry
        }
        names
    }

    private fun assertInvariantNames(names: List<String>) {
        assertTrue("el zip no debe quedar vacío", names.isNotEmpty())
        names.forEach { name ->
            assertFalse("nombre con barra invertida: $name", name.contains('\\'))
        }
    }

    @Test
    fun `zipFolderDeterministic emits slash separated names and stable bytes`() {
        val folder = tmp.newFolder("Shared.ps2")
        write(folder, "PARAM.SFO", "sfo")
        write(folder, "BASLUS-21050/save.bin", "bin")
        val first = tmp.newFile("first.zip")
        val second = tmp.newFile("second.zip")

        zipFolderDeterministic(folder, first)
        zipFolderDeterministic(folder, second)

        val names = entryNames(first)
        assertInvariantNames(names)
        assertEquals(
            setOf(
                "Shared.ps2/PARAM.SFO",
                "Shared.ps2/BASLUS-21050/",
                "Shared.ps2/BASLUS-21050/save.bin",
            ),
            names.toSet(),
        )
        assertTrue(
            "el zip debe ser byte a byte determinista",
            first.readBytes().contentEquals(second.readBytes()),
        )
    }

    @Test
    fun `zipFoldersDeterministic emits slash separated names`() {
        val firstFolder = tmp.newFolder("ULUS10064DATA")
        write(firstFolder, "save.bin", "one")
        write(firstFolder, "PROFILE/icon.bin", "icon")
        val secondFolder = tmp.newFolder("ULUS10064DAT2")
        write(secondFolder, "save.bin", "two")
        val zip = tmp.newFile("folders.zip")

        zipFoldersDeterministic(listOf(firstFolder, secondFolder), zip)

        val names = entryNames(zip)
        assertInvariantNames(names)
        assertEquals(
            setOf(
                "ULUS10064DATA/save.bin",
                "ULUS10064DATA/PROFILE/",
                "ULUS10064DATA/PROFILE/icon.bin",
                "ULUS10064DAT2/save.bin",
            ),
            names.toSet(),
        )
    }

    @Test
    fun `zipFolderRelativeToDeterministic emits slash separated names from the root`() {
        val root = tmp.newFolder("Wii/title")
        val folder = File(root, "00010000/GALE01")
        write(folder, "data/save.bin", "wii")
        val zip = tmp.newFile("relative.zip")

        zipFolderRelativeToDeterministic(root, folder, zip)

        val names = entryNames(zip)
        assertInvariantNames(names)
        assertEquals(
            setOf(
                "00010000/GALE01/data/",
                "00010000/GALE01/data/save.bin",
            ),
            names.toSet(),
        )
        assertTrue("el nombre raíz no debe entrar como prefijo", names.none { it.startsWith("title/") })
    }
}
