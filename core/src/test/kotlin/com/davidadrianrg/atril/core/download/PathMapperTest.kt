package com.davidadrianrg.atril.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SEC-OOS-4: el slug de plataforma es dato del servidor o de WorkManager y se
 * usa como componente de ruta. Ningún valor puede construir una carpeta fuera
 * de la raíz configurada, y los slugs legítimos siguen resolviendo a la misma
 * carpeta de siempre (lowercase + trim antes de validar).
 *
 * Las expectativas salen de la regla única de slug (`[a-z0-9][a-z0-9._-]`), no
 * de la implementación.
 */
class PathMapperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hostileSlugs = listOf(
        "../../x",
        "a/b",
        "",
        "   ",
        "a\u0000b",
        ".",
        "..",
        "/abs",
    )

    private fun assertStrictlyInside(root: File, dir: File) {
        val rootCanonical = root.canonicalFile
        val dirCanonical = dir.canonicalFile
        assertTrue(dirCanonical.path != rootCanonical.path)
        assertTrue(
            "'${dirCanonical.path}' debe quedar bajo '${rootCanonical.path}'",
            dirCanonical.path.startsWith(rootCanonical.path + File.separator),
        )
    }

    @Test
    fun `hostile slugs resolve to a canonical path strictly inside the root`() {
        val root = tmp.newFolder("roms")

        hostileSlugs.forEach { slug ->
            val dir = PathMapper.getPlatformDir(root.absolutePath, slug)

            assertEquals("'$slug' debe caer al centinela", File(root, "unknown").canonicalFile, dir.canonicalFile)
            assertStrictlyInside(root, dir)
        }
    }

    @Test
    fun `rom file and ensure folder never create anything outside the root`() {
        val root = tmp.newFolder("roms-created")

        hostileSlugs.forEach { slug ->
            val romFile = PathMapper.getRomFile(root.absolutePath, slug, "rom.bin")

            assertEquals(File(root, "unknown/rom.bin").canonicalFile, romFile.canonicalFile)
            assertFalse("getRomFile no crea el fichero", romFile.exists())
            assertTrue(PathMapper.ensurePlatformDir(root.absolutePath, slug))
        }

        assertEquals(
            "solo debe existir la carpeta centinela dentro de la raíz",
            setOf("unknown"),
            root.listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test
    fun `legitimate slugs map exactly to root slash slug`() {
        val root = tmp.newFolder("roms-legit")

        listOf("gba", "ps1", "3do", "android-games", "testplat").forEach { slug ->
            assertEquals(File(root, slug).canonicalFile, PathMapper.getPlatformDir(root.absolutePath, slug).canonicalFile)
        }
    }

    @Test
    fun `the existing lowercase and trim normalization is preserved`() {
        val root = tmp.newFolder("roms-normalized")

        assertEquals(File(root, "gba").canonicalFile, PathMapper.getPlatformDir(root.absolutePath, "GBA").canonicalFile)
        assertEquals(File(root, "gba").canonicalFile, PathMapper.getPlatformDir(root.absolutePath, " gba ").canonicalFile)
    }

    @Test
    fun `platform dir name distinguishes missing from invalid`() {
        assertEquals("unknown", PathMapper.platformDirName(null))
        assertEquals("unknown", PathMapper.platformDirName(""))
        assertEquals("unknown", PathMapper.platformDirName("   "))

        assertNull(PathMapper.platformDirName("../x"))
        assertNull(PathMapper.platformDirName("a/b"))
        assertNull(PathMapper.platformDirName("a\u0000b"))

        assertEquals("gba", PathMapper.platformDirName("gba"))
        assertEquals("gba", PathMapper.platformDirName("GBA"))
        assertEquals("gba", PathMapper.platformDirName(" gba "))
        assertEquals("android-games", PathMapper.platformDirName("android-games"))
    }
}
