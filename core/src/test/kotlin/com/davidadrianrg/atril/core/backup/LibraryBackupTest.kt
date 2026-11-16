package com.davidadrianrg.atril.core.backup

import com.davidadrianrg.atril.core.localgames.LocalGame
import com.davidadrianrg.atril.core.playstats.PlayStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackupTest {

    @Test
    fun `decodes untyped json numbers and drops invalid entries`() {
        val raw = listOf(
            mapOf("romId" to 3.0, "favorite" to true, "lastPlayedAt" to 1.7e12, "totalPlayedMs" to 5000.0),
            mapOf("romId" to "x"),
            mapOf("favorite" to true),
            mapOf("romId" to 4.0, "totalPlayedMs" to -10.0),
            "garbage",
        )
        val stats = LibraryBackup.decodePlayStats(raw)
        assertEquals(listOf(3, 4), stats.map { it.romId })
        assertEquals(1_700_000_000_000L, stats[0].lastPlayedAt)
        assertEquals(0L, stats[1].totalPlayedMs)
        assertTrue(LibraryBackup.decodePlayStats(null).isEmpty())
        assertTrue(LibraryBackup.decodePlayStats("nope").isEmpty())
    }

    @Test
    fun `merge never loses play time or favourites`() {
        val current = listOf(PlayStats(1, favorite = true, lastPlayedAt = 200, totalPlayedMs = 900), PlayStats(2))
        val restored = listOf(PlayStats(1, favorite = false, lastPlayedAt = 100, totalPlayedMs = 500), PlayStats(3, true))
        val merged = LibraryBackup.mergePlayStats(current, restored).associateBy { it.romId }
        assertEquals(PlayStats(1, true, 200, 900), merged[1])
        assertEquals(setOf(1, 2, 3), merged.keys)
        assertTrue(merged.getValue(3).favorite)
    }

    @Test
    fun `local games round trip keeps romId and merge only adds missing ones`() {
        val game = LocalGame("a", "Doom", "dos", "/g/doom.exe", coverPath = "/me/cover.jpg", romId = -42, addedAt = 7)
        val decoded = LibraryBackup.decodeLocalGames(LibraryBackup.encodeLocalGames(listOf(game)))
        assertEquals(listOf(game.copy(coverPath = null)), decoded)

        val existing = game.copy(title = "Doom (mine)", coverPath = "/me/cover.jpg")
        val other = LocalGame("b", "Quake", "dos", "/g/quake.exe")
        val merged = LibraryBackup.mergeLocalGames(listOf(existing), decoded + other)
        assertEquals(listOf(existing, other), merged)
    }

    @Test
    fun `local games and their stats only restore on the same platform`() {
        assertTrue(LibraryBackup.restoresLocalGames(null, LibraryBackup.PLATFORM_ANDROID))
        assertFalse(LibraryBackup.restoresLocalGames(null, LibraryBackup.PLATFORM_DESKTOP))
        assertFalse(LibraryBackup.restoresLocalGames("desktop", LibraryBackup.PLATFORM_ANDROID))
        val stats = listOf(PlayStats(5), PlayStats(-5))
        assertEquals(listOf(5), LibraryBackup.restorablePlayStats(stats, withLocal = false).map { it.romId })
        assertEquals(2, LibraryBackup.restorablePlayStats(stats, withLocal = true).size)
    }
}
