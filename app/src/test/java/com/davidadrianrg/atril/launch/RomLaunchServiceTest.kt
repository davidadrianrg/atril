package com.davidadrianrg.atril.launch

import com.davidadrianrg.atril.core.i18n.I18n
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Resolución perezosa antes de lanzar: un juego marcado a mano no guarda ruta,
 * así que el fichero se busca bajo las raíces de ROMs y se reutiliza la ruta
 * solo si aparece. Un fallo de búsqueda nunca debe propagar una excepción.
 */
class RomLaunchServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val linker: RomFileLinker = mockk()
    private val launcher: GameLauncher = mockk()
    private lateinit var service: RomLaunchService
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = I18n.locale
        I18n.locale = Locale.ENGLISH
        service = RomLaunchService(linker, launcher) { listOf("/roms") }
    }

    @After
    fun tearDown() {
        I18n.locale = originalLocale
    }

    private fun request(romId: Int = 42, localPath: String = "") = GameLaunchRequest(
        romId = romId,
        platformSlug = "gba",
        platformLabel = "Game Boy Advance",
        localPath = localPath,
        fileName = "Game.gba",
    )

    @Test
    fun `a missing path is resolved and the new path is prepared`() = runTest {
        val found = File(tmp.root, "Game.gba").apply { writeText("rom") }
        coEvery {
            linker.locateAndAttach(42, listOf("/roms"), "gba", "Game.gba")
        } returns found.absolutePath
        coEvery { launcher.prepare(any()) } returns GameLaunchPlan.Failed("prepared")

        val plan = service.prepare(request())

        assertEquals(GameLaunchPlan.Failed("prepared"), plan)
        coVerify(exactly = 1) { linker.locateAndAttach(42, listOf("/roms"), "gba", "Game.gba") }
        coVerify(exactly = 1) { launcher.prepare(match { it.localPath == found.absolutePath }) }
    }

    @Test
    fun `no file found fails with the locate hint`() = runTest {
        coEvery { linker.locateAndAttach(any(), any(), any(), any()) } returns null

        val plan = service.prepare(request())

        assertEquals(
            GameLaunchPlan.Failed(
                "The file wasn't found. Locate it to be able to play it.",
                needsLocate = true,
            ),
            plan,
        )
        coVerify(exactly = 0) { launcher.prepare(any()) }
    }

    @Test
    fun `an existing path is not searched again`() = runTest {
        val present = File(tmp.root, "Game.gba").apply { writeText("rom") }
        coEvery { launcher.prepare(any()) } returns GameLaunchPlan.Failed("prepared")

        val plan = service.prepare(request(localPath = present.absolutePath))

        assertEquals(GameLaunchPlan.Failed("prepared"), plan)
        coVerify(exactly = 0) { linker.locateAndAttach(any(), any(), any(), any()) }
    }

    @Test
    fun `a file that disappeared is looked up again`() = runTest {
        val gone = File(tmp.root, "Gone.gba").absolutePath
        val found = File(tmp.root, "Found.gba").apply { writeText("rom") }
        coEvery { linker.locateAndAttach(any(), any(), any(), any()) } returns found.absolutePath
        coEvery { launcher.prepare(any()) } returns GameLaunchPlan.Failed("prepared")

        service.prepare(request(localPath = gone))

        coVerify(exactly = 1) { launcher.prepare(match { it.localPath == found.absolutePath }) }
    }

    @Test
    fun `a linker failure becomes a failed plan instead of an exception`() = runTest {
        coEvery { linker.locateAndAttach(any(), any(), any(), any()) } throws IOException("disk gone")

        val plan = service.prepare(request())

        assertEquals(
            GameLaunchPlan.Failed(
                "The file wasn't found. Locate it to be able to play it.",
                needsLocate = true,
            ),
            plan,
        )
    }

    @Test
    fun `a blank link result is not used as a path`() = runTest {
        coEvery { linker.locateAndAttach(any(), any(), any(), any()) } returns "   "

        val plan = service.prepare(request())

        assertEquals(
            GameLaunchPlan.Failed(
                "The file wasn't found. Locate it to be able to play it.",
                needsLocate = true,
            ),
            plan,
        )
        coVerify(exactly = 0) { launcher.prepare(any()) }
    }

    @Test
    fun `a non RomM game is never linked and gets no locate hint`() = runTest {
        val plan = service.prepare(request(romId = 0))

        assertEquals(
            GameLaunchPlan.Failed(
                "The file wasn't found. Locate it to be able to play it.",
                needsLocate = false,
            ),
            plan,
        )
        coVerify(exactly = 0) { linker.locateAndAttach(any(), any(), any(), any()) }
    }
}
