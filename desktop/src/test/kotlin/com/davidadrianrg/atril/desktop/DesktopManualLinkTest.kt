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
import kotlinx.coroutines.delay
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

    /** Coloca varios ficheros bajo la misma `<romsRoot>/<slug>/`. */
    private fun placeRoms(vararg fileNames: String): Map<String, File> {
        val root = tmp.newFolder("roms-group")
        DesktopConfig.romsRoot = root.absolutePath
        return fileNames.associateWith { fileName ->
            File(root, "$slug/$fileName").apply {
                parentFile.mkdirs()
                writeText("rom")
            }
        }
    }

    /**
     * Sondea una condición que produce una corrutina del estado
     * (p. ej. `deleteDownload` lanza en su scope). Falla con el motivo si no
     * se cumple en el plazo, en vez de devolver un resultado inestable.
     */
    private suspend fun awaitUntil(
        reason: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("$reason (waited ${timeoutMs}ms)")
            delay(20)
        }
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

    /**
     * REV-016: la variante suspend de quitar la marca hace la misma limpieza que
     * la síncrona (marca manual + entrada de `library.properties`) y refresca
     * la ficha abierta aunque la Biblioteca no se haya cargado (REV-015). Antes
     * del arreglo, la tarjeta antigua conservaba `downloaded = true` y el
     * detalle seguía mostrando "Descargado" sin el botón de marcar.
     */
    @Test
    fun `unmarking suspending clears the manual mark and refreshes the open launcher card`() = runBlocking {
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
        state.openGame(card())
        assertTrue(state.roms.value.isEmpty())

        state.unmarkDownloadedManuallySuspending(card())

        assertNull(state.library.rom(romId))
        assertFalse(romId in state.manualDownloadedIds())
        assertFalse(manualPrefContains(romId))
        assertEquals("Downloaded mark removed from Juego $romId", state.snackbar.value)

        val open = state.selectedGame.value
        assertEquals("the detail must stay open", romId, open?.rep?.id)
        assertFalse("the open card must stop reading as downloaded", open!!.downloaded)
        assertFalse(state.isManualDownloaded(open))
    }

    /**
     * REV-015: la ficha abierta desde la Pantalla Jugar se reconstruye aunque
     * la Biblioteca no se haya visitado (`_roms` vacío). Antes del arreglo el
     * refresco solo buscaba en `buildCards(_roms)`, obtenía null y dejaba la
     * copia antigua. El escenario reproduce el camino real del lanzador:
     * `state.openGame` (DesktopLauncher) y `locateFileSuspending` (botón de la
     * ficha).
     *
     * REV-019: el refresco se comprueba por estado, no por identidad. La ficha
     * abierta es un `MutableStateFlow` y no re-emite cuando la tarjeta nueva es
     * estructuralmente igual (ese era el defecto que medía `assertNotSame`).
     * Se abre a propósito con nombre y fichero obsoletos respecto a
     * `library.properties` y se afirma el estado reconstruido: el representante
     * sigue en `groupRoms` (no se pierde el grupo), se conserva `discCount`, el
     * juego queda descargado y deja de ser una marca manual.
     */
    @Test
    fun `locating a file from the launcher detail refreshes the open card state without loading the library`() = runBlocking {
        val freshName = "Juego actualizado $romId"
        val freshFile = "Updated.gba"
        // `library.properties` ya tiene los metadatos buenos, pero sin ruta.
        state.library.upsertRom(
            DesktopLibrary.RomEntry(
                romId = romId,
                name = freshName,
                fileName = freshFile,
                platformSlug = slug,
            ),
        )
        // El lanzador abre la misma GameDetailPanel con state.openGame y una
        // copia antigua de los metadatos (nombre y fichero obsoletos).
        val stale = card()
        assertEquals("Juego $romId", stale.rep.name)
        assertEquals("Game.gba", stale.rep.fileName)
        state.openGame(stale)
        val opened = state.selectedGame.value
        assertEquals(romId, opened?.rep?.id)
        assertEquals("Juego $romId", opened?.rep?.name)
        assertTrue(
            "the fallback only applies while the Library has not been loaded",
            state.roms.value.isEmpty(),
        )

        val file = placeRom(freshFile)
        state.locateFileSuspending(stale, file.absolutePath)

        val open = state.selectedGame.value
        assertEquals("the detail must stay open", romId, open?.rep?.id)
        // Refresco real: los metadatos obsoletos se sustituyen por los persistidos.
        assertEquals(freshName, open?.rep?.name)
        assertEquals(freshFile, open?.rep?.fileName)
        // Invariante de GameCard: el representante encabeza y pertenece al grupo.
        assertEquals(listOf(romId), open?.groupRoms?.map { it.id })
        assertEquals("the representative must stay in the group", open?.rep?.id, open?.groupRoms?.first()?.id)
        assertEquals("the group keeps its disc count", 1, open?.discCount)
        assertFalse(
            "groupRoms must not be empty, or the manual check below would be vacuous",
            open!!.groupRoms.isEmpty(),
        )
        assertTrue("the open card must read as downloaded", open.downloaded)
        assertFalse("the open card must no longer be manual", state.isManualDownloaded(open))
        assertTrue(state.manualDownloadedIds().isEmpty())
        assertEquals(file.absolutePath, state.library.rom(romId)?.localPath)
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
     * REV-019: la reserva de un grupo de dos discos conserva el grupo completo.
     * Con `groupRoms = roms.drop(1)` la ficha abierta desde el lanzador perdía
     * al representante nada más refrescarse, de modo que las acciones de grupo
     * del detalle operaban sobre un grupo incompleto.
     */
    @Test
    fun `a two-disc launcher detail keeps the representative in its rebuilt group`() = runBlocking {
        val disc1 = disc(disc1Id, 1)
        val disc2 = disc(disc2Id, 2)
        val card = GameCard(rep = disc1, discCount = 2, groupRoms = listOf(disc1, disc2), downloaded = true)
        state.openGame(card)
        assertTrue(
            "the fallback only applies while the Library has not been loaded",
            state.roms.value.isEmpty(),
        )

        val file = placeRom("Game (Disc 1).gba")
        state.locateFileSuspending(card, file.absolutePath)

        val open = state.selectedGame.value
        assertEquals(disc1Id, open?.rep?.id)
        assertEquals("the rebuilt group must keep every disc and its order", listOf(disc1Id, disc2Id), open?.groupRoms?.map { it.id })
        assertEquals("the representative must stay in the group", disc1Id, open?.groupRoms?.first()?.id)
        assertEquals("the group keeps its disc count", 2, open?.discCount)
        assertTrue("the rebuilt group must read as downloaded", open!!.downloaded)
        assertFalse(state.isManualDownloaded(open))
        assertEquals(file.absolutePath, state.library.rom(disc1Id)?.localPath)
        assertEquals(file.absolutePath, state.library.rom(disc2Id)?.localPath)
    }

    /**
     * REV-019: borrar la descarga desde la ficha de reserva de dos discos debe
     * borrar los ficheros y las entradas de todo el grupo. Con
     * `groupRoms = roms.drop(1)` el representante quedaba fuera y
     * `deleteDownload` no borraba su fichero ni su entrada.
     */
    @Test
    fun `deleting the download from a launcher detail removes every disc of the group`() = runBlocking {
        val disc1 = disc(disc1Id, 1)
        val disc2 = disc(disc2Id, 2)
        val card = GameCard(rep = disc1, discCount = 2, groupRoms = listOf(disc1, disc2), downloaded = true)
        val files = placeRoms("Game (Disc 1).gba", "Game (Disc 2).gba")
        val first = files.getValue("Game (Disc 1).gba")
        val second = files.getValue("Game (Disc 2).gba")

        state.openGame(card)
        state.locateFileSuspending(card, first.absolutePath)
        // Cada disco acaba en su propio fichero.
        state.library.upsertRom(state.library.rom(disc2Id)!!.copy(localPath = second.absolutePath))
        assertEquals(second.absolutePath, state.library.rom(disc2Id)?.localPath)

        val open = state.selectedGame.value!!
        assertEquals(
            "the reserve detail must keep the whole group before deleting",
            listOf(disc1Id, disc2Id),
            open.groupRoms.map { it.id },
        )
        state.deleteDownload(open)

        // `deleteDownload` lanza en el scope del estado: se espera al snackbar,
        // que se emite después de borrar, persistir y refrescar la ficha.
        awaitUntil("deleteDownload did not finish") {
            state.snackbar.value == "Deleted Juego multidisco"
        }

        assertFalse("the representative's file must be deleted", first.exists())
        assertFalse("the second disc's file must be deleted", second.exists())
        assertNull(state.library.rom(disc1Id))
        assertNull(state.library.rom(disc2Id))
        val refreshed = state.selectedGame.value
        assertEquals(disc1Id, refreshed?.rep?.id)
        assertEquals(listOf(disc1Id, disc2Id), refreshed?.groupRoms?.map { it.id })
        assertFalse("downloadedIds must be cleared for the whole group", refreshed!!.downloaded)
    }

    /**
     * REV-019: volver a marcar desde la ficha de reserva debe alcanzar al
     * representante. `linkManual` itera `groupRoms`; si el refresco lo excluye,
     * el snackbar dice que se marcó y no se marca nada.
     */
    @Test
    fun `marking again from the rebuilt launcher detail reaches the representative`() = runBlocking {
        val card = card()
        state.openGame(card)
        assertTrue(
            "the fallback only applies while the Library has not been loaded",
            state.roms.value.isEmpty(),
        )

        state.unmarkDownloadedManuallySuspending(card)
        val rebuilt = state.selectedGame.value!!
        assertEquals("the detail must stay open", romId, rebuilt.rep.id)
        assertEquals("the rebuilt group must keep the representative", listOf(romId), rebuilt.groupRoms.map { it.id })

        // No hay fichero bajo la raíz configurada: la acción deja marca manual.
        state.markDownloadedManually(rebuilt)

        assertTrue("the representative must be marked again", romId in state.manualDownloadedIds())
        val marked = state.selectedGame.value!!
        assertEquals(romId, marked.rep.id)
        assertTrue("the marked representative must read as manual", state.isManualDownloaded(marked))
        assertEquals("Juego $romId marked as downloaded", state.snackbar.value)
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
