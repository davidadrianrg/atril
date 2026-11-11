package com.davidadrianrg.atril.core.launch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Decisión pura del destino de lanzamiento. Un `.apk` solo se puede instalar en
 * Android; en escritorio se rechaza antes de llegar al planner del emulador.
 * El host es un parámetro para que la misma ruta no cambie de significado.
 */
class LaunchTargetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String): String =
        File(tmp.root, name).apply {
            parentFile.mkdirs()
            writeText("data")
        }.absolutePath

    private fun resolve(path: String, platformSlug: String = "gba", host: LaunchHost) =
        LaunchTargetResolver.resolve(platformSlug, path, host)

    @Test
    fun `an apk on android is handed to the installer`() {
        val path = file("Game.apk")

        assertEquals(LaunchTarget.ApkInstaller(path), resolve(path, host = LaunchHost.ANDROID))
    }

    @Test
    fun `an uppercase APK extension is still an apk on android`() {
        val path = file("Game.APK")

        assertEquals(LaunchTarget.ApkInstaller(path), resolve(path, host = LaunchHost.ANDROID))
    }

    @Test
    fun `an apk on desktop is explicitly unsupported`() {
        val path = file("Game.apk")

        assertEquals(LaunchTarget.ApkUnsupported(path), resolve(path, host = LaunchHost.DESKTOP))
    }

    @Test
    fun `an android platform file without apk extension is still an apk`() {
        val path = file("Game.bin")

        assertEquals(LaunchTarget.ApkInstaller(path), resolve(path, "android", LaunchHost.ANDROID))
        assertEquals(LaunchTarget.ApkUnsupported(path), resolve(path, "android", LaunchHost.DESKTOP))
    }

    @Test
    fun `the android games platform alias is treated like android`() {
        val path = file("Game.bin")

        assertEquals(LaunchTarget.ApkInstaller(path), resolve(path, "android-games", LaunchHost.ANDROID))
        assertEquals(LaunchTarget.ApkUnsupported(path), resolve(path, "Android-Games", LaunchHost.DESKTOP))
    }

    @Test
    fun `a regular rom is launched with the emulator on both hosts`() {
        val path = file("Game (USA).gba")

        assertEquals(LaunchTarget.Emulator(path), resolve(path, host = LaunchHost.ANDROID))
        assertEquals(LaunchTarget.Emulator(path), resolve(path, host = LaunchHost.DESKTOP))
    }

    @Test
    fun `a blank path is missing on both hosts`() {
        assertEquals(LaunchTarget.Missing(""), resolve("", host = LaunchHost.ANDROID))
        assertEquals(LaunchTarget.Missing("   "), resolve("   ", host = LaunchHost.DESKTOP))
    }

    @Test
    fun `a path that does not exist is missing even when it looks like an apk`() {
        val path = File(tmp.root, "Gone.apk").absolutePath

        assertEquals(LaunchTarget.Missing(path), resolve(path, host = LaunchHost.ANDROID))
    }

    @Test
    fun `a directory named like an apk is not launchable`() {
        val path = File(tmp.root, "Folder.apk").apply { mkdirs() }.absolutePath

        assertEquals(LaunchTarget.Missing(path), resolve(path, host = LaunchHost.ANDROID))
    }

    @Test
    fun `a rom whose platform is not android keeps the emulator path`() {
        val path = file("Game.zip")

        assertEquals(LaunchTarget.Emulator(path), resolve(path, "snes", LaunchHost.ANDROID))
    }

    @Test
    fun `a package path resolves to an installed app on android`() {
        assertEquals(
            LaunchTarget.InstalledApp("com.example.game"),
            resolve("package:com.example.game", "android", LaunchHost.ANDROID),
        )
    }

    @Test
    fun `a package path is unsupported on desktop`() {
        assertEquals(
            LaunchTarget.InstalledAppUnsupported("com.example.game"),
            resolve("package:com.example.game", "android", LaunchHost.DESKTOP),
        )
    }

    @Test
    fun `an empty package path is missing`() {
        assertEquals(
            LaunchTarget.Missing("package:"),
            resolve("package:", "android", LaunchHost.ANDROID),
        )
    }
}
