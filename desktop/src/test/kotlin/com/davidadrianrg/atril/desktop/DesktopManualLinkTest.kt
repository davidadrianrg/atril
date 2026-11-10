package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.i18n.I18n
import com.davidadrianrg.atril.core.remote.dto.RomDto
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Asociación manual de un fichero en escritorio: al marcar un juego se persiste
 * la ruta encontrada en `library.properties`, al desmarcar se limpia solo lo que
 * era manual y un `.apk` se rechaza antes de guardar o lanzar nada.
 *
 * Corre bajo el `user.home`/prefs aislados de `build/test-home` y usa ids y slug
 * propios para no chocar con otros tests del módulo.
 */
class DesktopManualLinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var state: DesktopAppState
    private lateinit var previousRomsRoot: String
    private lateinit var previousManual: String
    private lateinit var previousLocale: Locale
    private lateinit var libraryFile: File

    private val romId = 9_770_101
    private val slug = "linktest"

    @Before
    fun setUp() {
        assertTrue(
            "desktop tests must run under the isolated build/test-home",
            System.getProperty("java.util.prefs.userRoot").orEmpty().contains("test-home"),
        )
        previousRomsRoot = DesktopConfig.romsRoot
        previousManual = DesktopConfig.manualDownloaded
        previousLocale = I18n.locale
        I18n.locale = Locale.ENGLISH
        libraryFile = File(DesktopConfig.configDir, "library.properties")
        DesktopConfig.manualDownloaded = ""
        DesktopConfig.serverUrl = ""
        DesktopConfig.apiKey = ""
        DesktopLibrary(libraryFile).removeRom(romId)
        state = DesktopAppState(scope)
    }

    @After
    fun tearDown() {
        state.library.removeRom(romId)
        DesktopConfig.manualDownloaded = previousManual
        DesktopConfig.romsRoot = previousRomsRoot
        I18n.locale = previousLocale
        scope.cancel()
    }

    private fun card(): GameCard {
        val rom = RomDto(
            id = romId,
            name = "Juego $romId",
            fileName = "Game.gba",
            platformId = 7,
            platformSlug = slug,
        )
        return GameCard(rep = rom, discCount = 1, groupRoms = listOf(rom), downloaded = true)
    }

    /** Coloca el fichero bajo `<romsRoot>/<slug>/` y devuelve la raíz temporal. */
    private fun placeRom(fileName: String = "Game.gba"): File {
        val root = tmp.newFolder("roms")
        File(root, "$slug/$fileName").apply {
            parentFile.mkdirs()
            writeText("rom")
        }
        DesktopConfig.romsRoot = root.absolutePath
        return File(root, "$slug/$fileName")
    }

    private fun manualPrefContains(id: Int): Boolean =
        DesktopConfig.manualDownloaded.split(',')
            .map { it.trim() }
            .any { it == id.toString() }

    @Test
    fun `marking a game finds its file in the platform folder and persists the path`() {
        val rom = placeRom()

        state.markDownloadedManually(card())

        assertEquals(rom.absolutePath, state.library.rom(romId)?.localPath)
        assertEquals(rom.absolutePath, state.localPathOf(card()))
        assertFalse("a linked game is no longer a manual mark", state.isManualDownloaded(card()))
        assertFalse(manualPrefContains(romId))
        assertEquals("File linked", state.snackbar.value)
    }

    @Test
    fun `marking a game without a file keeps the manual mark and stores no path`() {
        state.markDownloadedManually(card())

        assertTrue(state.isManualDownloaded(card()))
        assertNull(state.library.rom(romId))
        assertTrue(manualPrefContains(romId))
        assertEquals("Juego $romId marked as downloaded", state.snackbar.value)
    }

    @Test
    fun `unmarking removes the library entry and the manual preference`() {
        state.markDownloadedManually(card())
        state.library.upsertRom(
            DesktopLibrary.RomEntry(
                romId = romId,
                name = "Juego $romId",
                fileName = "Game.gba",
                platformSlug = slug,
                localPath = "/old/location/Game.gba",
            ),
        )

        state.unmarkDownloadedManually(card())

        assertNull(state.library.rom(romId))
        assertFalse(romId in state.manualDownloadedIds())
        assertFalse(manualPrefContains(romId))
        assertEquals("Downloaded mark removed from Juego $romId", state.snackbar.value)
    }

    @Test
    fun `locating an apk is refused and stores nothing`() {
        val apk = File(tmp.newFolder("picked"), "Game.apk").apply { writeText("apk") }

        state.locateFile(card(), apk.absolutePath)

        assertNull(state.library.rom(romId))
        assertEquals("APKs can only be launched on Android", state.snackbar.value)
    }

    @Test
    fun `launching an apk is rejected before the emulator planner`() = runBlocking {
        val apk = File(tmp.newFolder("picked"), "Game.apk").apply { writeText("apk") }
        state.library.upsertRom(
            DesktopLibrary.RomEntry(
                romId = romId,
                name = "Juego $romId",
                fileName = "Game.apk",
                platformSlug = slug,
                localPath = apk.absolutePath,
            ),
        )

        val launched = state.openLocalGame(card())

        assertFalse(launched)
        assertEquals("APKs can only be launched on Android", state.snackbar.value)
    }

    @Test
    fun `a file that appears after marking is found and persisted when launching`() = runBlocking {
        state.markDownloadedManually(card())
        assertTrue(state.isManualDownloaded(card()))

        val rom = placeRom()
        state.openLocalGame(card())

        assertEquals(rom.absolutePath, state.library.rom(romId)?.localPath)
        assertFalse(state.isManualDownloaded(card()))
        assertFalse(manualPrefContains(romId))
    }
}
