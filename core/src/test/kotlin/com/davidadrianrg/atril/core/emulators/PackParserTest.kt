package com.davidadrianrg.atril.core.emulators

import org.junit.Assert.assertEquals
import org.junit.Test

class PackParserTest {

    private val json = "{\"apps\": [{\"id\": \"info.cemu.cemu\", \"url\": \"https://github.com/SSimco/Cemu\", \"author\": \"SSimco\", \"name\": \"Cemu\", \"additionalSettings\": \"{\\\"includePrereleases\\\": false, \\\"apkFilterRegEx\\\": \\\"\\\", \\\"about\\\": \\\"Wii U emulator\\\"}\", \"categories\": [\"Emulator\"]}, {\"id\": \"info.cemu.cemu\", \"url\": \"https://github.com/sapphirerhodonite/cemu\", \"author\": \"SapphireRhodonite\", \"name\": \"Cemu (DS)\", \"additionalSettings\": \"{\\\"about\\\": \\\"Wii U emulator (dual-screen fork)\\\"}\", \"categories\": [\"Emulator\"]}, {\"id\": \"org.example.nogithub\", \"url\": \"https://example.com/app\", \"name\": \"No GitHub\"}]}"

    @Test
    fun `parses apps and nested settings`() {
        val entries = PackParser.parse(json)
        assertEquals(2, entries.size)
        val cemu = entries.first { it.name == "Cemu" }
        assertEquals("SSimco", cemu.owner)
        assertEquals("Cemu", cemu.repo)
        assertEquals("Wii U emulator", cemu.settings.about)
    }

    @Test
    fun `single profile prefers the non dual variant`() {
        val picked = PackParser.forProfile(PackParser.parse(json), dualScreen = false)
        assertEquals(listOf("Cemu"), picked.map { it.name })
    }

    @Test
    fun `dual profile prefers the dual variant with the same id`() {
        val picked = PackParser.forProfile(PackParser.parse(json), dualScreen = true)
        assertEquals(listOf("Cemu (DS)"), picked.map { it.name })
    }

    @Test
    fun `invalid json returns empty`() {
        assertEquals(emptyList<EmulatorEntry>(), PackParser.parse("{"))
    }
}
