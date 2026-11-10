package com.davidadrianrg.atril.core.download

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Búsqueda del fichero de un juego que se descargó a mano: primero en
 * `<raiz>/<plataforma>/` y después en subcarpetas, con nombre exacto sin
 * distinguir mayúsculas y sin salir de la raíz.
 *
 * Los tests usan un árbol temporal real: no hay mocks que puedan esconder un
 * fallo de `java.io.File`.
 */
class RomFileLocatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String, content: String = "rom"): File =
        File(tmp.root, path).apply {
            parentFile.mkdirs()
            writeText(content)
        }

    private fun dir(path: String): File =
        File(tmp.root, path).apply { mkdirs() }

    private fun root(): String = tmp.root.absolutePath

    @Test
    fun `a file directly inside the platform folder is found`() {
        val rom = file("gba/Game (USA).gba")

        assertEquals(rom, RomFileLocator.locate(listOf(root()), "gba", "Game (USA).gba"))
    }

    @Test
    fun `the platform slug selects its folder and is case insensitive`() {
        val rom = file("gba/Game.gba")

        assertEquals(rom, RomFileLocator.locate(listOf(root()), "GBA", "Game.gba"))
    }

    @Test
    fun `a file moved into a subfolder is found by exact name`() {
        val rom = file("gba/RPG/Game (USA).gba")
        file("gba/RPG/Other (USA).gba")

        assertEquals(rom, RomFileLocator.locate(listOf(root()), "gba", "Game (USA).gba"))
    }

    @Test
    fun `a name that is not present returns null`() {
        file("gba/Game.gba")

        assertNull(RomFileLocator.locate(listOf(root()), "gba", "Missing.gba"))
    }

    @Test
    fun `an empty or missing root returns null`() {
        file("gba/Game.gba")

        assertNull(RomFileLocator.locate(emptyList(), "gba", "Game.gba"))
        assertNull(RomFileLocator.locate(listOf("", "   "), "gba", "Game.gba"))
        assertNull(RomFileLocator.locate(listOf(File(tmp.root, "not-there").absolutePath), "gba", "Game.gba"))
    }

    @Test
    fun `a root that is a regular file is ignored`() {
        val fakeRoot = File(tmp.root, "roms").apply { writeText("not a directory") }

        assertNull(RomFileLocator.locate(listOf(fakeRoot.absolutePath), "gba", "Game.gba"))
    }

    @Test
    fun `an unreadable root returns null without throwing`() {
        assumeTrue("root can read any directory", System.getProperty("user.name") != "root")
        val romsRoot = dir("roms")
        file("roms/gba/Game.gba")
        val unreadable = PosixFilePermissions.fromString("---------")
        Files.setPosixFilePermissions(romsRoot.toPath(), unreadable)
        try {
            assertNull(RomFileLocator.locate(listOf(romsRoot.absolutePath), "gba", "Game.gba"))
        } finally {
            Files.setPosixFilePermissions(romsRoot.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `matching ignores case in the requested name and in the file on disk`() {
        val rom = file("gba/Game (USA).GBA")

        assertEquals(rom, RomFileLocator.locate(listOf(root()), "gba", "game (usa).gba"))
    }

    @Test
    fun `hidden files and hidden directories are not searched`() {
        file("gba/.Game.gba")
        file("gba/.hidden/Game (USA).gba")

        assertNull(RomFileLocator.locate(listOf(root()), "gba", ".Game.gba"))
        assertNull(RomFileLocator.locate(listOf(root()), "gba", "Game (USA).gba"))
    }

    @Test
    fun `symbolic links are not followed`() {
        val outside = tmp.newFolder("outside")
        File(outside, "Game.gba").writeText("rom")
        val platform = dir("roms/gba")
        val linkCreated = runCatching {
            Files.createSymbolicLink(File(platform, "linked").toPath(), outside.toPath())
        }.isSuccess
        assumeTrue("the filesystem does not support symbolic links", linkCreated)

        assertNull(RomFileLocator.locate(listOf(dir("roms").absolutePath), "gba", "Game.gba"))
    }

    @Test
    fun `a file six levels below the platform folder is found`() {
        val rom = file("gba/d1/d2/d3/d4/d5/Game.gba")

        assertEquals(rom, RomFileLocator.locate(listOf(root()), "gba", "Game.gba"))
    }

    @Test
    fun `a file seven levels below the platform folder is not found`() {
        val rom = file("gba/d1/d2/d3/d4/d5/d6/Game.gba")
        assertTrue("the fixture must exist", rom.isFile)

        assertNull(RomFileLocator.locate(listOf(root()), "gba", "Game.gba"))
    }

    @Test
    fun `the entry budget stops the search before an out-of-budget match`() {
        val platform = dir("gba")
        // El objetivo existe, pero su carpeta ordena después de 5000 entradas:
        // el presupuesto se agota antes de poder visitarla.
        repeat(RomFileLocator.MAX_ENTRIES) { index ->
            File(platform, "f%05d.txt".format(index)).writeText("")
        }
        val target = File(platform, "zzz-later/Game.gba").apply {
            parentFile.mkdirs()
            writeText("rom")
        }
        assertTrue("the fixture must exist", target.isFile)

        assertNull(RomFileLocator.locate(listOf(root()), "gba", "Game.gba"))
    }

    @Test
    fun `the first folder in alphabetical order wins when the same name exists twice`() {
        val first = file("aaa/Game.gba")
        file("zzz/Game.gba")

        assertEquals(
            first,
            RomFileLocator.locateInDirs(
                listOf(File(tmp.root, "zzz"), File(tmp.root, "aaa")),
                "Game.gba",
            ),
        )
    }

    @Test
    fun `a second root is used when the first one has no match`() {
        val rom = file("second/gba/Game.gba")

        assertEquals(
            rom,
            RomFileLocator.locate(
                listOf(File(tmp.root, "first").absolutePath, File(tmp.root, "second").absolutePath),
                "gba",
                "Game.gba",
            ),
        )
    }

    @Test
    fun `unsafe file names are rejected before touching the disk`() {
        val rom = file("gba/Game.gba")
        assertTrue(rom.isFile)

        listOf("../Game.gba", "a/Game.gba", "a\\Game.gba", "..", ".", "   ", "Game\u0000.gba")
            .forEach { unsafe ->
                assertNull("unsafe name should be rejected: $unsafe", RomFileLocator.locate(listOf(root()), "gba", unsafe))
            }
    }

    @Test
    fun `a name with dots in the middle is safe and is found on disk`() {
        val rom = file("gba/Game..v2.gba")

        assertTrue("'Game..v2.gba' is a plain file name", RomFileLocator.isSafeFileName("Game..v2.gba"))
        assertEquals(rom, RomFileLocator.locate(listOf(root()), "gba", "Game..v2.gba"))
    }

    @Test
    fun `navigation components, separators, blanks and NUL are still unsafe`() {
        listOf("..", "../x", "..\\x", "a/b", "a\\b", "   ", "", "Game\u0000.gba")
            .forEach { unsafe ->
                assertFalse("unsafe name must be rejected: $unsafe", RomFileLocator.isSafeFileName(unsafe))
            }
    }

    @Test
    fun `unsafe platform slugs are rejected`() {
        file("gba/Game.gba")

        listOf("../gba", "a/gba", "a\\gba", "..", " ").forEach { unsafe ->
            assertNull("unsafe slug should be rejected: $unsafe", RomFileLocator.locate(listOf(root()), unsafe, "Game.gba"))
        }
    }

    @Test
    fun `locating never creates, deletes or rewrites files`() {
        file("gba/Game.gba")
        file("gba/Sub/Other.gba")
        val before = snapshot()

        RomFileLocator.locate(listOf(root()), "gba", "Game.gba")
        RomFileLocator.locate(listOf(root()), "gba", "Missing.gba")
        RomFileLocator.locate(listOf(root()), "gba", "../escape.gba")
        RomFileLocator.locate(listOf(root()), "gba", "Game.gba")

        assertEquals(before, snapshot())
    }

    private fun snapshot(): List<String> =
        tmp.root.walkTopDown()
            .filter { it != tmp.root }
            .map { "${it.relativeTo(tmp.root).path}:${it.isDirectory}:${it.length()}:${it.lastModified()}" }
            .sorted()
            .toList()
}
