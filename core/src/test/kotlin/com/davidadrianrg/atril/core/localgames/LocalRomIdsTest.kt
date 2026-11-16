package com.davidadrianrg.atril.core.localgames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** B4: ids de juegos locales únicos, estables y compatibles con los antiguos. */
class LocalRomIdsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // "Aa" y "BB" tienen el mismo String.hashCode (2112): colisión garantizada.
    private fun game(id: String, addedAt: Long, romId: Int? = null) =
        LocalGame(id = id, title = id, platformSlug = "gba", path = "/roms/$id.gba", addedAt = addedAt, romId = romId)

    @Test
    fun `existing games keep the id older versions computed`() {
        val assigned = LocalRomIds.assign(listOf(game("one", 1), game("two", 2)))
        assertEquals(LocalRomIds.legacy("one"), assigned[0].romId)
        assertEquals(LocalRomIds.legacy("two"), assigned[1].romId)
    }

    @Test
    fun `a hash collision gets a different negative id and the oldest game keeps its own`() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val assigned = LocalRomIds.assign(listOf(game("BB", addedAt = 20), game("Aa", addedAt = 10)))
        val aa = assigned.first { it.id == "Aa" }.romId!!
        val bb = assigned.first { it.id == "BB" }.romId!!
        assertEquals(LocalRomIds.legacy("Aa"), aa)
        assertNotEquals(aa, bb)
        assertTrue(bb < 0)
    }

    @Test
    fun `persisted ids are kept and a duplicated one is repaired`() {
        val games = listOf(game("x", 1, romId = -5), game("y", 2, romId = -5), game("z", 3, romId = -9))
        val assigned = LocalRomIds.assign(games)
        assertEquals(-5, assigned.first { it.id == "x" }.romId)
        assertEquals(-9, assigned.first { it.id == "z" }.romId)
        val y = assigned.first { it.id == "y" }.romId!!
        assertTrue(y < 0 && y != -5 && y != -9)
    }

    @Test
    fun `nothing to assign returns the same list`() {
        val games = listOf(game("x", 1, romId = -5))
        assertSame(games, LocalRomIds.assign(games))
    }

    @Test
    fun `the store persists ids for legacy files and keeps them on reload`() {
        val file = File(tmp.root, "local_games.json")
        // Fichero de una versión anterior: sin romId y con una colisión de hash.
        file.writeText(
            """[{"id":"Aa","title":"A","platformSlug":"gba","path":"/a","addedAt":1},
               {"id":"BB","title":"B","platformSlug":"gba","path":"/b","addedAt":2}]""",
        )
        val first = LocalGameStore(file).all().associate { it.id to it.romId }
        assertEquals(LocalRomIds.legacy("Aa"), first["Aa"])
        assertNotEquals(first["Aa"], first["BB"])
        assertTrue(file.readText().contains("romId"))

        val reloaded = LocalGameStore(file).all().associate { it.id to it.romId }
        assertEquals(first, reloaded)
    }

    @Test
    fun `new games get a unique id and updates keep it`() {
        val store = LocalGameStore(File(tmp.root, "games.json"))
        val a = store.add("A", "gba", "/a")
        val b = store.add("B", "gba", "/b")
        assertTrue(a.romId!! < 0 && b.romId!! < 0)
        assertNotEquals(a.romId, b.romId)
        val renamed = store.update(a.id) { it.copy(title = "A2", romId = null) }!!
        assertEquals(a.romId, renamed.romId)
    }
}
