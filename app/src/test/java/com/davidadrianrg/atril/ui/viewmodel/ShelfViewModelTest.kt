package com.davidadrianrg.atril.ui.viewmodel

import com.davidadrianrg.atril.core.localgames.LocalGamesRepository
import com.davidadrianrg.atril.core.playstats.PlayStatsRepository
import com.davidadrianrg.atril.core.remote.RomMApiService
import com.davidadrianrg.atril.core.remote.dto.RomDto
import com.davidadrianrg.atril.core.remote.dto.RomResponse
import com.davidadrianrg.atril.data.local.ServerConfig
import com.davidadrianrg.atril.data.local.dao.PlatformDao
import com.davidadrianrg.atril.data.local.dao.RomDao
import com.davidadrianrg.atril.data.local.entity.DownloadedRomEntity
import com.davidadrianrg.atril.data.local.entity.PlatformEntity
import com.davidadrianrg.atril.data.local.entity.RomMetadataCacheEntity
import com.davidadrianrg.atril.data.repository.FakeCoverFetcher
import com.davidadrianrg.atril.data.repository.FakeRomMetadataCacheDao
import com.davidadrianrg.atril.data.repository.OfflineShelfCache
import com.davidadrianrg.atril.data.repository.PhysicalMediaRepository
import com.davidadrianrg.atril.data.repository.RomRepository
import com.davidadrianrg.atril.data.repository.SettingsRepository
import com.davidadrianrg.atril.launch.EmulatorLauncher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Hidratación de Play: los descargados se publican desde Room al instante y,
 * sin servidor, la caché offline (F1) aporta nombre, metadatos y carátula.
 * Sin red real: el cliente de metadatos es un doble.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val downloaded = MutableStateFlow(
        listOf(
            entity(1, "chrono"),
            entity(2, "zelda"),
        ),
    )
    private val settings = MutableStateFlow(ServerConfig("", "", "", 2))

    private val romDao = mockk<RomDao>(relaxed = true)
    private val platformDao = mockk<PlatformDao>()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val romRepository = mockk<RomRepository>(relaxed = true)
    private val physicalMedia = mockk<PhysicalMediaRepository>(relaxed = true)
    private val localGames = mockk<LocalGamesRepository>()
    private val playStats = mockk<PlayStatsRepository>()

    private val cacheDao = FakeRomMetadataCacheDao()
    private val fetcher = FakeCoverFetcher()
    private lateinit var cache: OfflineShelfCache

    private val factoryCalls = AtomicInteger()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { romDao.observeAllDownloadedRoms() } returns downloaded
        every { platformDao.getAllPlatformsBlocking() } returns listOf(PlatformEntity(3, "snes", "Super Nintendo", 2))
        every { settingsRepository.settings } returns settings
        every { settingsRepository.retroArchBasePath } returns flowOf("/retroarch")
        every { settingsRepository.localDiscChoices } returns flowOf(emptyMap())
        every { settingsRepository.launcherDefaultDisplay } returns flowOf("card")
        every { settingsRepository.launchEmulatorOverrides } returns flowOf(emptyMap())
        every { romRepository.physicalMediaOverrides } returns MutableStateFlow(emptyMap())
        every { physicalMedia.observe() } returns flowOf(emptyMap())
        every { localGames.games } returns MutableStateFlow(emptyList())
        every { playStats.stats } returns MutableStateFlow(emptyMap())
        cache = OfflineShelfCache(cacheDao, File(tmp.root, "covers"), fetcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun entity(id: Int, name: String) = DownloadedRomEntity(
        romId = id,
        name = name,
        fileName = "$name.sfc",
        platformId = 3,
        platformSlug = "snes",
        localPath = "/roms/snes/$name.sfc",
        fileSizeBytes = 10L,
    )

    private fun dto(id: Int, name: String) = RomDto(
        id = id,
        name = name,
        fileName = "${name.lowercase()}.sfc",
        platformId = 3,
        platformSlugRaw = "snes",
        pathCoverLarge = "assets/romm/resources/roms/3/$id/cover/big.png?ts=2026-10-26 15:01:56",
        genres = listOf("RPG"),
    )

    private fun viewModel(api: RomMApiService? = null) = ShelfViewModel(
        romRepository = romRepository,
        romDao = romDao,
        platformDao = platformDao,
        settingsRepository = settingsRepository,
        physicalMediaRepository = physicalMedia,
        emulatorLauncher = mockk<EmulatorLauncher>(relaxed = true),
        localGames = localGames,
        playStatsRepository = playStats,
        offlineCache = cache,
        metadataApi = { _, _ ->
            factoryCalls.incrementAndGet()
            api ?: error("no network in tests")
        },
    )

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    private fun seedCache(id: Int, name: String): String {
        val cover = File(tmp.root, "covers/$id.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        runBlocking {
            cacheDao.upsertAll(
                listOf(
                    RomMetadataCacheEntity(
                        romId = id, name = name, platformSlug = "snes", genres = "RPG",
                        releaseDate = 795_484_800L, coverSource = "http://romm/$id.png",
                        coverPath = cover.absolutePath,
                    ),
                ),
            )
        }
        return "file://${cover.absolutePath}"
    }

    @Test
    fun `without a configured server items come from Room and the offline cache`() {
        val cover = seedCache(1, "Chrono Trigger")
        val vm = viewModel()

        val items = await { vm.items.first { it.size == 2 } }

        val chrono = items.single { it.rom.id == 1 }
        assertEquals("Chrono Trigger", chrono.rom.name)
        assertEquals(cover, chrono.rom.coverUrlLarge)
        assertEquals(listOf("RPG"), chrono.rom.genres)
        assertEquals(795_484_800L, chrono.rom.igdbMetadata?.firstReleaseDate)
        assertEquals("Super Nintendo", chrono.platformLabel)
        assertEquals("/roms/snes/chrono.sfc", chrono.localPath)

        val zelda = items.single { it.rom.id == 2 }
        assertEquals("zelda", zelda.rom.name)
        assertNull(zelda.rom.coverUrlLarge)

        assertFalse(vm.loading.value)
        assertEquals(0, factoryCalls.get())
    }

    @Test
    fun `an unreachable server still shows complete cached cards and skips per-rom lookups`() {
        val cover = seedCache(1, "Chrono Trigger")
        val api = mockk<RomMApiService>()
        coEvery { api.getRoms(any()) } throws IOException("unreachable")
        settings.value = ServerConfig("http://10.0.0.1:8080", "key", "", 2)

        val vm = viewModel(api)
        val items = await { vm.items.first { it.size == 2 } }

        assertEquals(cover, items.single { it.rom.id == 1 }.rom.coverUrlLarge)
        await { while (factoryCalls.get() == 0) kotlinx.coroutines.delay(10) }
        coVerify(timeout = 2_000) { api.getRoms(any()) }
        coVerify(exactly = 0) { api.getRom(any()) }
        assertFalse(vm.loading.value)
    }

    @Test
    fun `server metadata is published and persisted for the next offline start`() {
        val api = mockk<RomMApiService>()
        coEvery { api.getRoms(any()) } returns RomResponse(
            items = listOf(dto(1, "Chrono Trigger"), dto(2, "Zelda")),
        )
        settings.value = ServerConfig("http://romm.local", "key", "", 2)

        val vm = viewModel(api)
        val items = await { vm.items.first { list -> list.any { it.rom.name == "Zelda" } } }
        assertEquals(listOf("Chrono Trigger", "Zelda"), items.map { it.rom.name })

        // La caché se rellena en segundo plano con metadatos y carátula en disco.
        val rows = await { cache.entries.first { it.size == 2 } }
        assertEquals("Zelda", rows.getValue(2).name)
        assertEquals(
            "http://romm.local/assets/romm/resources/roms/3/2/cover/big.png?ts=2026-10-26%2015:01:56",
            rows.getValue(2).coverSource,
        )
        assertEquals(2, fetcher.calls.size)

        // Con la carátula ya en disco, Play la lee de ahí (sin volver a la red).
        val local = await {
            vm.items.first { list -> list.all { it.rom.coverUrlLarge?.startsWith("file://") == true } }
        }
        assertEquals("file://${rows.getValue(1).coverPath}", local.single { it.rom.id == 1 }.rom.coverUrlLarge)
    }

    @Test
    fun `deleted games leave the offline cache`() {
        seedCache(1, "Chrono Trigger")
        seedCache(2, "Zelda")
        val vm = viewModel()
        await { vm.items.first { it.size == 2 } }

        downloaded.value = listOf(entity(2, "zelda"))

        val rows = await { cache.entries.first { it.size == 1 } }
        assertEquals(setOf(2), rows.keys)
        assertFalse(File(tmp.root, "covers/1.png").exists())
    }
}
