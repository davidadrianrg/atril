package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.library.LibraryPreferenceKeys
import java.io.File
import java.util.Properties
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mapa `grupo local -> disco elegido` persistido en `library.properties`.
 *
 * Cada test usa un fichero propio bajo el `user.home` aislado de
 * `build/test-home`, así que no toca la biblioteca real ni depende del orden de
 * ejecución.
 */
class DesktopLocalDiscChoicesTest {

    private lateinit var file: File

    @Before
    fun createIsolatedLibraryFile() {
        assertTrue(System.getProperty("user.home").orEmpty().contains("test-home"))
        val home = File(System.getProperty("user.home"))
        home.mkdirs()
        file = File(home, "library-test-${System.nanoTime()}.properties")
    }

    @After
    fun deleteLibraryFile() {
        file.delete()
    }

    @Test
    fun `chosen discs survive a restart`() {
        val library = DesktopLibrary(file)
        library.setLocalDiscChoice("ps1|final fantasy vii", "disc-2")
        library.setLocalDiscChoice("gc|metal gear solid", "@playlist")

        val reopened = DesktopLibrary(file)

        assertEquals(
            mapOf(
                "ps1|final fantasy vii" to "disc-2",
                "gc|metal gear solid" to "@playlist",
            ),
            reopened.localDiscChoices(),
        )
    }

    @Test
    fun `removing a choice keeps the other groups`() {
        val library = DesktopLibrary(file)
        library.setLocalDiscChoice("a", "1")
        library.setLocalDiscChoice("b", "2")

        library.setLocalDiscChoice("a", null)

        assertEquals(mapOf("b" to "2"), DesktopLibrary(file).localDiscChoices())
    }

    @Test
    fun `setting a disc choice leaves unrelated library entries untouched`() {
        val library = DesktopLibrary(file)
        library.upsertRom(
            DesktopLibrary.RomEntry(
                romId = 7,
                name = "Game",
                fileName = "Game.iso",
                platformSlug = "ps1",
            ),
        )
        library.setLocalDiscChoice("ps1|game", "disc-1")

        val reopened = DesktopLibrary(file)

        assertEquals("Game", reopened.rom(7)?.name)
        assertEquals(mapOf("ps1|game" to "disc-1"), reopened.localDiscChoices())
    }

    @Test
    fun `a corrupted stored map decodes to an empty map`() {
        val props = Properties()
        props[LibraryPreferenceKeys.LOCAL_DISCS] = ";;;no-equals;=missing-key;key="
        file.outputStream().use { props.store(it, "test") }

        assertTrue(DesktopLibrary(file).localDiscChoices().isEmpty())
    }
}
