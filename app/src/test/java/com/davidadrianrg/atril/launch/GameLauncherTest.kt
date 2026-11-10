package com.davidadrianrg.atril.launch

import android.content.Context
import com.davidadrianrg.atril.core.i18n.I18n
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Contrato de error del enrutador de lanzamientos: un `ApkLaunchResult` se
 * traduce al mensaje que ve el usuario y `null` significa "se abrió algo".
 * Ningún test abre un intent real: el lanzador APK y el emulador son mocks.
 */
class GameLauncherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = mockk(relaxed = true)
    private lateinit var emulatorLauncher: EmulatorLauncher
    private lateinit var apkGameLauncher: ApkGameLauncher
    private lateinit var launcher: GameLauncher
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = I18n.locale
        I18n.locale = Locale.ENGLISH
        emulatorLauncher = mockk()
        apkGameLauncher = mockk()
        launcher = GameLauncher(emulatorLauncher, apkGameLauncher)
    }

    @After
    fun tearDown() {
        I18n.locale = originalLocale
    }

    private fun apkFile(): File = File(tmp.root, "Game.apk").apply { writeText("apk") }

    private fun request(localPath: String, platformSlug: String = "gba") = GameLaunchRequest(
        romId = 1,
        platformSlug = platformSlug,
        platformLabel = "Game Boy Advance",
        localPath = localPath,
        fileName = "Game.gba",
    )

    private fun startApk(result: ApkLaunchResult): String? {
        val apk = apkFile()
        every { apkGameLauncher.launch(apk) } returns result
        return launcher.start(context, GameLaunchPlan.Apk(apk))
    }

    @Test
    fun `an already installed apk reports success when it opens`() {
        assertNull(startApk(ApkLaunchResult.Opened("com.example.game")))
    }

    @Test
    fun `opening the system installer reports success`() {
        assertNull(startApk(ApkLaunchResult.InstallerOpened))
    }

    @Test
    fun `a missing install permission is explained instead of pretending success`() {
        assertEquals(
            "Allow \"Install unknown apps\" for Atril and press Play again.",
            startApk(ApkLaunchResult.NeedsInstallPermission),
        )
    }

    @Test
    fun `a file that is not an apk is rejected with its own message`() {
        assertEquals("That file isn't a valid APK", startApk(ApkLaunchResult.NotAnApk("/roms/Game.apk")))
    }

    @Test
    fun `an apk that vanished reports its path`() {
        assertEquals(
            "The ROM is no longer on this device: /roms/Game.apk",
            startApk(ApkLaunchResult.Missing("/roms/Game.apk")),
        )
    }

    @Test
    fun `an installed app without a launcher screen names the package`() {
        assertEquals(
            "com.example.game is installed but has no launch screen. Open it from the app drawer.",
            startApk(ApkLaunchResult.NoLaunchActivity("com.example.game")),
        )
    }

    @Test
    fun `an external install error is surfaced verbatim`() {
        assertEquals("No app can open this file", startApk(ApkLaunchResult.Error("No app can open this file")))
    }

    @Test
    fun `a failed plan is returned and never reaches the apk launcher`() {
        val message = launcher.start(context, GameLaunchPlan.Failed("The file is gone", needsLocate = true))

        assertEquals("The file is gone", message)
        verify(exactly = 0) { apkGameLauncher.launch(any()) }
        verify(exactly = 0) { emulatorLauncher.start(any(), any()) }
    }

    @Test
    fun `an apk path is prepared without contacting the emulator`() = runBlocking {
        val apk = apkFile()

        val plan = launcher.prepare(request(apk.absolutePath))

        assertEquals(GameLaunchPlan.Apk(apk), plan)
        coVerify(exactly = 0) { emulatorLauncher.prepare(any()) }
    }

    @Test
    fun `a missing rom path fails before preparing anything`() = runBlocking {
        val path = File(tmp.root, "Gone.gba").absolutePath

        val plan = launcher.prepare(request(path))

        assertEquals(GameLaunchPlan.Failed("The ROM is no longer on this device: $path", needsLocate = true), plan)
        coVerify(exactly = 0) { emulatorLauncher.prepare(any()) }
    }

    @Test
    fun `a regular rom is delegated to the emulator and its failure is preserved`() = runBlocking {
        val rom = File(tmp.root, "Game.gba").apply { writeText("rom") }
        coEvery { emulatorLauncher.prepare(any()) } returns LaunchPlan.Failed("No emulator configured")

        val plan = launcher.prepare(request(rom.absolutePath))

        assertEquals(GameLaunchPlan.Failed("No emulator configured"), plan)
        coVerify(exactly = 1) { emulatorLauncher.prepare(match { it.localPath == rom.absolutePath }) }
    }
}
