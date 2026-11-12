package com.davidadrianrg.atril.core.emulators

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseSelectorTest {

    private val settings = PackSettings(
        includePrereleases = false,
        apkFilterRegEx = "",
        versionExtractionRegEx = "",
        releaseTitleAsVersion = false,
        preferredApkIndex = 0,
        autoApkFilterByArch = true,
        about = "",
    )

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        assets: List<GhAsset> = listOf(GhAsset("app.apk", "https://x/app.apk", 10)),
        name: String? = null,
    ) = GhRelease(tagName = tag, name = name, prerelease = prerelease, assets = assets)

    @Test
    fun `skips prereleases unless the pack allows them`() {
        val releases = listOf(
            release("v2.0", prerelease = true),
            release("v1.0"),
        )
        assertEquals("v1.0", ReleaseSelector.select(releases, settings)?.version)
    }

    @Test
    fun `includes prereleases when configured`() {
        val releases = listOf(release("v2.0", prerelease = true), release("v1.0"))
        val picked = ReleaseSelector.select(releases, settings.copy(includePrereleases = true))
        assertEquals("v2.0", picked?.version)
    }

    @Test
    fun `prefers the arm64 apk when several match`() {
        val assets = listOf(
            GhAsset("app-arm64-v8a.apk", "https://x/arm64.apk"),
            GhAsset("app-universal.apk", "https://x/universal.apk"),
        )
        val picked = ReleaseSelector.select(listOf(release("v1.0", assets = assets)), settings)
        assertEquals("app-arm64-v8a.apk", picked?.assetName)
    }

    @Test
    fun `applies the apk filter regex`() {
        val assets = listOf(
            GhAsset("app-vanilla.apk", "https://x/vanilla.apk"),
            GhAsset("app-googleplay.apk", "https://x/gp.apk"),
        )
        val picked = ReleaseSelector.select(
            listOf(release("v1.0", assets = assets)),
            settings.copy(apkFilterRegEx = "vanilla"),
        )
        assertEquals("app-vanilla.apk", picked?.assetName)
    }

    @Test
    fun `extracts the version with the pack regex`() {
        val release = release("release-1.4.2-android")
        val picked = ReleaseSelector.select(
            listOf(release),
            settings.copy(versionExtractionRegEx = """(\d+\.\d+\.\d+)"""),
        )
        assertEquals("1.4.2", picked?.version)
    }

    @Test
    fun `dual profile prefers dual assets from the same release`() {
        val assets = listOf(
            GhAsset("app-arm64-v8a.apk", "https://x/single.apk"),
            GhAsset("app-dual-arm64-v8a.apk", "https://x/dual.apk"),
        )
        val release = release("v1.0", assets = assets)
        assertEquals(
            "app-dual-arm64-v8a.apk",
            ReleaseSelector.select(listOf(release), settings, dualScreen = true)?.assetName,
        )
        assertEquals(
            "app-arm64-v8a.apk",
            ReleaseSelector.select(listOf(release), settings, dualScreen = false)?.assetName,
        )
    }

    @Test
    fun `uses the release title as version when asked`() {
        val release = release("v1.0", name = "Version 1.0.3")
        val picked = ReleaseSelector.select(
            listOf(release),
            settings.copy(releaseTitleAsVersion = true, versionExtractionRegEx = """(\d+\.\d+\.\d+)"""),
        )
        assertEquals("1.0.3", picked?.version)
    }

    @Test
    fun `returns null without apk assets`() {
        val release = release("v1.0", assets = listOf(GhAsset("source.zip", "https://x/src.zip")))
        assertNull(ReleaseSelector.select(listOf(release), settings))
    }
}
