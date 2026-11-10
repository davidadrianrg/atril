package com.davidadrianrg.atril.data.repository

import android.net.Uri
import com.davidadrianrg.atril.core.i18n.I18n
import com.davidadrianrg.atril.data.local.ServerConfig
import com.davidadrianrg.atril.data.storage.RomFileImporter
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Contrato de "Localizar archivo" en la frontera de configuración: sin carpeta
 * de ROMs (o con un slug de plataforma no válido) no se ha intentado copiar
 * nada, así que el resultado debe pedir configurar la carpeta y no disfrazarse
 * de fallo de copia (REV-007).
 */
class RomFileImportServiceTest {

    private val importer: RomFileImporter = mockk(relaxed = true)
    private val romRepository: RomRepository = mockk(relaxed = true)
    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        previousLocale = I18n.locale
        I18n.locale = Locale.ENGLISH
    }

    @After
    fun tearDown() {
        I18n.locale = previousLocale
    }

    private fun service(romsRootPath: String): RomFileImportService {
        val settingsRepository: SettingsRepository = mockk()
        every { settingsRepository.settings } returns flowOf(
            ServerConfig(
                serverUrl = "",
                apiKey = "",
                romsRootPath = romsRootPath,
                maxConcurrentDownloads = 1,
            ),
        )
        return RomFileImportService(importer, romRepository, settingsRepository)
    }

    @Test
    fun `a blank ROMs root asks to configure the folder instead of reporting a copy failure`() = runTest {
        val outcome = service(romsRootPath = "   ").locate(
            romId = 1,
            groupRomIds = emptyList(),
            platformSlug = "gba",
            fileName = "Game.gba",
            uri = mockk<Uri>(),
            overwrite = false,
        )

        assertEquals(LocateOutcome.NoRomsRoot, outcome)
        coVerify(exactly = 0) { romRepository.updateLocalPath(any(), any()) }
    }

    @Test
    fun `an unsafe platform slug asks to configure the folder instead of copying`() = runTest {
        val outcome = service(romsRootPath = "/roms").locate(
            romId = 1,
            groupRomIds = emptyList(),
            platformSlug = "../outside",
            fileName = "Game.gba",
            uri = mockk<Uri>(),
            overwrite = false,
        )

        assertEquals(LocateOutcome.NoRomsRoot, outcome)
        coVerify(exactly = 0) { romRepository.updateLocalPath(any(), any()) }
    }
}
