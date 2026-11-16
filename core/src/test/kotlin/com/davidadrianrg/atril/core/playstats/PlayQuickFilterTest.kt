package com.davidadrianrg.atril.core.playstats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayQuickFilterTest {

    private fun stats(favorite: Boolean = false, lastPlayed: Long? = null) =
        GamePlayStats(listOf(1), favorite, lastPlayed, 0L)

    @Test
    fun `toggle switches a pill on, off and to another`() {
        assertEquals(PlayQuickFilter.FAVORITES, PlayQuickFilter.toggle(null, PlayQuickFilter.FAVORITES))
        assertNull(PlayQuickFilter.toggle(PlayQuickFilter.FAVORITES, PlayQuickFilter.FAVORITES))
        assertEquals(PlayQuickFilter.RECENT, PlayQuickFilter.toggle(PlayQuickFilter.FAVORITES, PlayQuickFilter.RECENT))
    }

    @Test
    fun `filters by favorite and by play history`() {
        assertTrue(PlayQuickFilter.matches(null, stats()))
        assertTrue(PlayQuickFilter.matches(PlayQuickFilter.FAVORITES, stats(favorite = true)))
        assertFalse(PlayQuickFilter.matches(PlayQuickFilter.FAVORITES, stats(lastPlayed = 5)))
        assertTrue(PlayQuickFilter.matches(PlayQuickFilter.RECENT, stats(lastPlayed = 5)))
        assertFalse(PlayQuickFilter.matches(PlayQuickFilter.RECENT, stats(favorite = true)))
    }

    @Test
    fun `empty message explains the active filter`() {
        assertEquals("play.empty.favorites", PlayQuickFilter.emptyMessageKey(PlayQuickFilter.FAVORITES))
        assertEquals("play.empty.recent", PlayQuickFilter.emptyMessageKey(PlayQuickFilter.RECENT))
        assertEquals("play.empty.platform", PlayQuickFilter.emptyMessageKey(null))
    }

    @Test
    fun `search ignores case, accents and word order, prefix matches first`() {
        val games = listOf("The Legend of Zelda", "Pokémon Esmeralda", "Zelda II", "Metroid")

        assertEquals(games, PlayQuickFilter.search(games, "  ") { it })
        assertEquals(listOf("Pokémon Esmeralda"), PlayQuickFilter.search(games, "POKEMON") { it })
        assertEquals(listOf("Zelda II", "The Legend of Zelda"), PlayQuickFilter.search(games, "zelda") { it })
        assertEquals(listOf("The Legend of Zelda"), PlayQuickFilter.search(games, "zelda legend") { it })
        assertEquals(emptyList<String>(), PlayQuickFilter.search(games, "mario") { it })
    }
}
