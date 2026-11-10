package com.davidadrianrg.atril.core.localgames

import com.davidadrianrg.atril.core.library.DiscMarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agrupación multidisco de juegos locales.
 *
 * Todo se prueba con una [PlaylistLocator] falsa, así que ningún test toca el
 * sistema de ficheros. Los valores esperados salen de la regla de producto:
 * solo se agrupa lo que lleva una marca de disco explícita, en la misma
 * plataforma y con el mismo nombre base; todo lo demás se queda separado.
 */
class LocalDiscGrouperTest {

    private val noPlaylist = PlaylistLocator { null }

    private fun entry(
        id: String,
        title: String,
        path: String,
        platformSlug: String = "ps1",
    ) = LocalDiscEntry(id = id, title = title, path = path, platformSlug = platformSlug)

    @Test
    fun `disc marks are parsed from the file name`() {
        assertEquals(1, DiscMarks.discNumber("Final Fantasy VII (Disc 1).cue"))
        assertEquals(2, DiscMarks.discNumber("Final Fantasy VII (Disk 2).bin"))
        assertEquals(3, DiscMarks.discNumber("Final Fantasy VII (CD 3).iso"))
        assertEquals(1, DiscMarks.discNumber("Final Fantasy VII (Disc 1 of 4).cue"))
        assertNull(DiscMarks.discNumber("Final Fantasy VII (USA).cue"))
        assertEquals("final fantasy vii", DiscMarks.normalizeName("Final Fantasy VII (Disc 2)"))
    }

    @Test
    fun `disc marks come from the path even when the title lost the tag`() {
        val groups = LocalDiscGrouper.group(
            listOf(
                entry("d1", "Final Fantasy VII", "/roms/Final Fantasy VII (Disc 1).cue"),
                entry("d2", "Final Fantasy VII", "/roms/Final Fantasy VII (Disc 2).cue"),
            ),
            noPlaylist,
        )

        val group = groups.single()
        assertEquals(2, group.discCount)
        assertEquals(listOf(1, 2), group.members.map { it.discNumber })
    }

    @Test
    fun `disk and cd marks group the same way as disc marks`() {
        val diskGroup = LocalDiscGrouper.group(
            listOf(
                entry("a", "Saga", "/roms/Saga (Disk 1).iso"),
                entry("b", "Saga", "/roms/Saga (Disk 2).iso"),
            ),
            noPlaylist,
        ).single()
        assertEquals(listOf(1, 2), diskGroup.members.map { it.discNumber })

        val cdGroup = LocalDiscGrouper.group(
            listOf(
                entry("a", "Saga", "/roms/Saga (CD 1).bin"),
                entry("b", "Saga", "/roms/Saga (CD 3).bin"),
            ),
            noPlaylist,
        ).single()
        assertEquals(listOf(1, 3), cdGroup.members.map { it.discNumber })
    }

    @Test
    fun `disc one of four groups with the rest of the set`() {
        val group = LocalDiscGrouper.group(
            listOf(
                entry("d1", "Saga", "/roms/Saga (Disc 1 of 4).iso"),
                entry("d2", "Saga", "/roms/Saga (Disc 2 of 4).iso"),
            ),
            noPlaylist,
        ).single()

        assertEquals(listOf(1, 2), group.members.map { it.discNumber })
        assertEquals("d1", group.representativeId)
    }

    @Test
    fun `two discs group with disc one as representative and ordered members`() {
        val groups = LocalDiscGrouper.group(
            listOf(
                entry("d2", "Final Fantasy VII", "/roms/Final Fantasy VII (Disc 2).cue"),
                entry("d1", "Final Fantasy VII", "/roms/Final Fantasy VII (Disc 1).cue"),
            ),
            noPlaylist,
        )

        val group = groups.single()
        assertEquals("ps1|final fantasy vii", group.key)
        assertEquals("d1", group.representativeId)
        assertEquals(listOf(1, 2), group.members.map { it.discNumber })
        assertEquals(2, group.discCount)
    }

    @Test
    fun `two games with the same title but no disc mark stay separate`() {
        val groups = LocalDiscGrouper.group(
            listOf(
                entry("a", "Sonic", "/roms/Sonic.iso"),
                entry("b", "Sonic", "/roms/Sonic.bin"),
            ),
            noPlaylist,
        )

        assertEquals(2, groups.size)
        assertTrue(groups.all { it.discCount == 1 })
    }

    @Test
    fun `a marker-less file next to a marked disc stays separate`() {
        val groups = LocalDiscGrouper.group(
            listOf(
                entry("plain", "Game", "/roms/Game.iso"),
                entry("d1", "Game", "/roms/Game (Disc 1).iso"),
            ),
            noPlaylist,
        )

        assertEquals(2, groups.size)
        assertTrue(groups.all { it.discCount == 1 })
    }

    @Test
    fun `a sequel number is not a disc mark`() {
        val groups = LocalDiscGrouper.group(
            listOf(
                entry("g2", "Game 2", "/roms/Game 2.iso"),
                entry("gd2", "Game", "/roms/Game (Disc 2).iso"),
            ),
            noPlaylist,
        )

        assertEquals(2, groups.size)
        assertTrue(groups.all { it.discCount == 1 })
    }

    @Test
    fun `different titles stay separate even when both carry disc marks`() {
        val groups = LocalDiscGrouper.group(
            listOf(
                entry("a", "Game A", "/roms/Game A (Disc 1).iso"),
                entry("b", "Game B", "/roms/Game B (Disc 2).iso"),
            ),
            noPlaylist,
        )

        assertEquals(2, groups.size)
        assertTrue(groups.all { it.discCount == 1 })
    }

    @Test
    fun `the same game on different platforms stays separate`() {
        val groups = LocalDiscGrouper.group(
            listOf(
                entry("a", "Game", "/roms/Game (Disc 1).iso", platformSlug = "ps1"),
                entry("b", "Game", "/roms/Game (Disc 2).iso", platformSlug = "gc"),
            ),
            noPlaylist,
        )

        assertEquals(2, groups.size)
        assertTrue(groups.all { it.discCount == 1 })
    }

    @Test
    fun `two files with the same disc number collapse into one member`() {
        val groups = LocalDiscGrouper.group(
            listOf(
                entry("cue", "Game", "/roms/Game (Disc 1).cue"),
                entry("bin", "Game", "/roms/Game (Disc 1).bin"),
                entry("d2", "Game", "/roms/Game (Disc 2).iso"),
            ),
            noPlaylist,
        )

        val group = groups.single()
        assertEquals(2, group.discCount)
        assertEquals(listOf(1, 2), group.members.map { it.discNumber })
        // Gana el primero por ruta: ".bin" ordena antes que ".cue".
        assertEquals("bin", group.representativeId)
    }

    @Test
    fun `an unmarked duplicate in a multidisc bucket stays a separate game`() {
        val groups = LocalDiscGrouper.group(
            listOf(
                entry("d1", "Game", "/roms/Game (Disc 1).iso"),
                entry("d2", "Game", "/roms/Game (Disc 2).iso"),
                entry("plain", "Game", "/roms/Game.iso"),
            ),
            noPlaylist,
        )

        assertEquals(2, groups.size)
        assertEquals(2, groups.first().discCount)
        assertEquals(1, groups.last().discCount)
    }

    @Test
    fun `a grouped game exposes its sibling playlist and defaults to it`() {
        val locator = PlaylistLocator { path ->
            if (path.endsWith("(Disc 1).iso")) "/roms/Game.m3u" else null
        }
        val group = LocalDiscGrouper.group(
            listOf(
                entry("d1", "Game", "/roms/Game (Disc 1).iso"),
                entry("d2", "Game", "/roms/Game (Disc 2).iso"),
            ),
            locator,
        ).single()

        assertEquals("/roms/Game.m3u", group.playlistPath)
        assertEquals(LocalDiscChoice.Playlist("/roms/Game.m3u"), LocalDiscGrouper.defaultChoice(group))
    }

    @Test
    fun `a single game never exposes a playlist`() {
        val locator = PlaylistLocator { "/roms/Game.m3u" }
        val group = LocalDiscGrouper.group(
            listOf(entry("only", "Game", "/roms/Game.iso")),
            locator,
        ).single()

        assertNull(group.playlistPath)
        assertEquals(LocalDiscChoice.Disc(group.members.first()), LocalDiscGrouper.defaultChoice(group))
    }

    @Test
    fun `choiceFor resolves a stored disc, the playlist id and falls back when stale`() {
        val locator = PlaylistLocator { "/roms/Game.m3u" }
        val group = LocalDiscGrouper.group(
            listOf(
                entry("d1", "Game", "/roms/Game (Disc 1).iso"),
                entry("d2", "Game", "/roms/Game (Disc 2).iso"),
            ),
            locator,
        ).single()

        assertEquals(LocalDiscChoice.Disc(group.members[0]), LocalDiscGrouper.choiceFor(group, "d1"))
        assertEquals(LocalDiscChoice.Disc(group.members[1]), LocalDiscGrouper.choiceFor(group, "d2"))
        assertEquals(
            LocalDiscChoice.Playlist("/roms/Game.m3u"),
            LocalDiscGrouper.choiceFor(group, LocalDiscGroup.PLAYLIST_CHOICE_ID),
        )
        assertEquals(LocalDiscGrouper.defaultChoice(group), LocalDiscGrouper.choiceFor(group, "disc-that-vanished"))
        assertEquals(LocalDiscGrouper.defaultChoice(group), LocalDiscGrouper.choiceFor(group, null))
    }

    @Test
    fun `the playlist id without a playlist falls back to disc one`() {
        val group = LocalDiscGrouper.group(
            listOf(
                entry("d1", "Game", "/roms/Game (Disc 1).iso"),
                entry("d2", "Game", "/roms/Game (Disc 2).iso"),
            ),
            noPlaylist,
        ).single()

        assertEquals(LocalDiscChoice.Disc(group.members.first()), LocalDiscGrouper.defaultChoice(group))
        assertEquals(
            LocalDiscChoice.Disc(group.members.first()),
            LocalDiscGrouper.choiceFor(group, LocalDiscGroup.PLAYLIST_CHOICE_ID),
        )
    }

    @Test
    fun `group key is stable across disc paths and platforms and ignores the title`() {
        assertEquals(
            LocalDiscGrouper.groupKey("ps1", "/roms/Game (Disc 1).iso"),
            LocalDiscGrouper.groupKey("ps1", "/roms/Game (Disc 2).iso"),
        )
        assertEquals("ps1|game", LocalDiscGrouper.groupKey("ps1", "/roms/Game (Disc 2).iso"))
        // La barra de Windows no cambia la clave.
        assertEquals(
            LocalDiscGrouper.groupKey("ps1", "/roms/Game (Disc 2).iso"),
            LocalDiscGrouper.groupKey("ps1", "C:\\roms\\Game (Disc 1).iso"),
        )
        assertNotEquals(
            LocalDiscGrouper.groupKey("ps1", "/roms/Game (Disc 1).iso"),
            LocalDiscGrouper.groupKey("gc", "/roms/Game (Disc 1).iso"),
        )
    }

    @Test
    fun `an empty list yields no groups`() {
        assertTrue(LocalDiscGrouper.group(emptyList(), noPlaylist).isEmpty())
    }
}
