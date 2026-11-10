package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.i18n.I18n
import com.davidadrianrg.atril.core.remote.dto.RomDto
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    // Ids propios para los casos de multidisco y de rutas no confiables.
    private val disc1Id = 9_770_201
    private val disc2Id = 9_770_202
    private val traversalId = 9_770_301
    private val missingRootId = 9_770_302
    private val linkedFolderId = 9_770_303
    private val allIds = listOf(romId, disc1Id, disc2Id, traversalId, missingRootId, linkedFolderId)

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
        DesktopLibrary(libraryFile).apply { allIds.forEach { removeRom(it) } }
        DesktopConfig.manualDownloaded = ""
        DesktopConfig.romsRoot = ""
        DesktopConfig.serverUrl = ""
        DesktopConfig.apiKey = ""
        state = DesktopAppState(scope)
    }

    @After
    fun tearDown() {
        DesktopLibrary(libraryFile).apply { allIds.forEach { removeRom(it) } }
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

    /**
     * REV-008/REV-001: asociar un fichero a un grupo de dos discos debe dejar el
     * grupo completo. Si solo se persiste el representante, el grupo deja de
     * contar como descargado (pasa a Missing) y la Estantería pierde el "2
     * discos" al reconstruir las tarjetas desde `library.roms()`.
     */
    @Test
    fun `locating a two-disc group keeps every disc linked and the card counts two discs`() = runBlocking {
        val disc1 = disc(disc1Id, 1)
        val disc2 = disc(disc2Id, 2)
        val card = GameCard(rep = disc1, discCount = 2, groupRoms = listOf(disc1, disc2), downloaded = false)

        // Sin fichero todavía: los dos discos quedan como marca manual.
        state.markDownloadedManually(card)
        assertEquals(setOf(disc1Id, disc2Id), state.manualDownloadedIds())

        val file = placeRom("Game (Disc 1).gba")
        state.locateFileSuspending(card, file.absolutePath)

        // La ruta se persiste en todos los discos, como hace Android (D13).
        assertEquals(file.absolutePath, state.library.rom(disc1Id)?.localPath)
        assertEquals(file.absolutePath, state.library.rom(disc2Id)?.localPath)
        assertTrue("no disc from the linked group may stay manual", state.manualDownloadedIds().isEmpty())
        assertFalse(state.isManualDownloaded(card))

        // Descargados/Missing y la Estantería se reconstruyen con esta lista.
        seedRoms(disc1, disc2)
        state.setFilter(LibraryFilter.DOWNLOADED)
        val downloaded = state.games()

        assertEquals(1, downloaded.size)
        assertEquals(2, downloaded.single().discCount)
        assertTrue("the linked group must still be downloaded", downloaded.single().downloaded)
        assertEquals(setOf(disc1Id, disc2Id), downloaded.single().groupRoms.map { it.id }.toSet())
        assertTrue("the linked group must not be offered as missing", state.missingGames().isEmpty())
    }

    /**
     * SEC-001: el `platformSlug` llega de RomM y no es de confianza. Un slug
     * `../outside` resolvería una carpeta fuera de `<romsRoot>`; no debe
     * asociarse ni persistirse esa ruta.
     */
    @Test
    fun `a platform slug that escapes the ROMs root links nothing`() = runBlocking {
        val root = tmp.newFolder("roms-escape")
        val outside = tmp.newFolder("outside-escape")
        File(outside, "Secret.gba").writeText("secret")
        DesktopConfig.romsRoot = root.absolutePath

        val rom = RomDto(
            id = traversalId,
            name = "Traversal",
            fileName = "Secret.gba",
            platformId = 7,
            platformSlug = "../outside-escape",
        )
        val card = GameCard(rep = rom, discCount = 1, groupRoms = listOf(rom), downloaded = false)

        state.markDownloadedManually(card)

        assertNull("the outside file must not be persisted", state.library.rom(traversalId))
        assertNull(state.localPathOf(card))
        assertTrue(state.isManualDownloaded(card))
        assertEquals("Traversal marked as downloaded", state.snackbar.value)

        // La resolución perezosa tampoco debe encontrarlo ni guardarlo.
        assertFalse(state.openLocalGame(card))
        assertNull(state.library.rom(traversalId))
        assertEquals("The file wasn't found. Locate it to be able to play it.", state.snackbar.value)
    }

    @Test
    fun `a missing ROMs root links nothing and keeps the manual mark`() {
        DesktopConfig.romsRoot = File(tmp.root, "not-there").absolutePath
        val rom = RomDto(
            id = missingRootId,
            name = "No root",
            fileName = "Game.gba",
            platformId = 7,
            platformSlug = slug,
        )
        val card = GameCard(rep = rom, discCount = 1, groupRoms = listOf(rom), downloaded = false)

        state.markDownloadedManually(card)

        assertNull(state.library.rom(missingRootId))
        assertTrue(state.isManualDownloaded(card))
        assertTrue(manualPrefContains(missingRootId))
    }

    /**
     * SEC-001 (contención canónica): el slug y la carpeta son seguros, pero la
     * carpeta de plataforma es un symlink que resuelve fuera de `<romsRoot>`.
     */
    @Test
    fun `a platform folder that resolves outside the ROMs root links nothing`() {
        val root = tmp.newFolder("roms-symlink")
        val outside = tmp.newFolder("outside-symlink")
        File(outside, "Game.gba").writeText("rom")
        val link = File(root, slug)
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        assumeTrue("the filesystem does not support symbolic links", created)
        DesktopConfig.romsRoot = root.absolutePath

        val rom = RomDto(
            id = linkedFolderId,
            name = "Linked folder",
            fileName = "Game.gba",
            platformId = 7,
            platformSlug = slug,
        )
        val card = GameCard(rep = rom, discCount = 1, groupRoms = listOf(rom), downloaded = false)

        state.markDownloadedManually(card)

        assertNull("a folder resolving outside the root is rejected", state.library.rom(linkedFolderId))
        assertTrue(state.isManualDownloaded(card))
    }

    /** Segundo disco del mismo juego: mismo nombre normalizado e igdb_id. */
    private fun disc(id: Int, number: Int): RomDto = RomDto(
        id = id,
        name = "Juego multidisco",
        fileName = "Game (Disc $number).gba",
        platformId = 7,
        platformSlug = slug,
        igdbId = 77,
    )

    /**
     * `buildCards` (y con él `games()`/`missingGames()`) se calcula sobre la
     * lista que publica la API. No hay punto de inyección público para esa lista
     * en el estado, así que el test la siembra por reflexión para poder
     * comprobar el comportamiento visible (Descargados/Missing y discos).
     */
    @Suppress("UNCHECKED_CAST")
    private fun seedRoms(vararg roms: RomDto) {
        val field = DesktopAppState::class.java.getDeclaredField("_roms")
        field.isAccessible = true
        (field.get(state) as MutableStateFlow<List<RomDto>>).value = roms.toList()
    }
}
