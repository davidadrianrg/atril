package com.davidadrianrg.atril.core.localgames

import com.davidadrianrg.atril.core.launch.LaunchPlatforms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlatformChoicesTest {

    @Test
    fun `without RomM the builtin catalog is offered`() {
        val choices = LocalPlatformChoices.build(emptyList())
        assertEquals(LocalPlatformChoices.BUILTIN.size, choices.size)
        assertTrue(choices.any { it.first == "psx" })
    }

    @Test
    fun `server platforms win over builtin entries with the same canonical slug`() {
        val choices = LocalPlatformChoices.build(
            listOf("ps" to "Sony PlayStation", "genesis-slash-megadrive" to "Mega Drive"),
        )
        assertTrue(choices.contains("ps" to "Sony PlayStation"))
        assertTrue(choices.none { it.first == "psx" })
        assertTrue(choices.none { it.first == "genesis" })
        assertTrue(choices.any { it.first == "snes" })
    }

    @Test
    fun `choices are sorted by name and unique by slug`() {
        val choices = LocalPlatformChoices.build(listOf("n64" to "Nintendo 64", "n64" to "Duplicada"))
        assertEquals(choices.map { it.second.lowercase() }.sorted(), choices.map { it.second.lowercase() })
        assertEquals(1, choices.count { it.first == "n64" })
    }

    @Test
    fun `builtin slugs are canonical`() {
        LocalPlatformChoices.BUILTIN.forEach { (slug, _) ->
            assertEquals(slug, LaunchPlatforms.canonical(slug))
        }
    }

    @Test
    fun `guess matches exact folder first, then aliases`() {
        val slugs = listOf("n64", "genesis-slash-megadrive")
        assertEquals("n64", LocalPlatformChoices.guess("/sd/ROMs/n64/Mario.z64", slugs))
        assertEquals("genesis-slash-megadrive", LocalPlatformChoices.guess("/sd/ROMs/megadrive/Sonic.md", slugs))
        assertNull(LocalPlatformChoices.guess("/sd/Download/Sonic.md", slugs))
    }
}
