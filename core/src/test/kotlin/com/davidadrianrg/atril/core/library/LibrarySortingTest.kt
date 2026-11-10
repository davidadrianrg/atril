package com.davidadrianrg.atril.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Semántica de ordenación compartida por Android y escritorio.
 *
 * Los valores esperados están escritos a mano a partir de las reglas del
 * contrato: nombre sin distinguir mayúsculas, año y nota ausentes al final en
 * descendente, y "nunca jugado" siempre al final.
 */
class LibrarySortingTest {

    private data class Item(
        val name: String,
        val size: Long = 0L,
        val year: Long? = null,
        val rating: Double? = null,
        val lastPlayed: Long? = null,
    )

    private fun sorted(items: List<Item>, sort: LibrarySort): List<String> =
        LibrarySorting.sort(
            items = items,
            sort = sort,
            name = { it.name },
            sizeBytes = { it.size },
            releaseDate = { it.year },
            rating = { it.rating },
            lastPlayedAt = { it.lastPlayed },
        ).map { it.name }

    @Test
    fun `name sorts case-insensitively in both directions`() {
        val items = listOf(Item("banana"), Item("Apple"), Item("cherry"))

        assertEquals(listOf("Apple", "banana", "cherry"), sorted(items, LibrarySort.NAME_ASC))
        assertEquals(listOf("cherry", "banana", "Apple"), sorted(items, LibrarySort.NAME_DESC))
    }

    @Test
    fun `size sorts by raw bytes`() {
        val items = listOf(Item("small", size = 10), Item("large", size = 300), Item("medium", size = 20))

        assertEquals(listOf("large", "medium", "small"), sorted(items, LibrarySort.SIZE_DESC))
        assertEquals(listOf("small", "medium", "large"), sorted(items, LibrarySort.SIZE_ASC))
    }

    @Test
    fun `a missing year sorts last in both directions`() {
        val items = listOf(Item("no-year"), Item("old", year = 2000), Item("new", year = 2010))

        assertEquals(listOf("new", "old", "no-year"), sorted(items, LibrarySort.YEAR_DESC))
        assertEquals(listOf("old", "new", "no-year"), sorted(items, LibrarySort.YEAR_ASC))
    }

    @Test
    fun `a missing rating uses the minus one sentinel`() {
        val items = listOf(Item("unrated"), Item("good", rating = 7.0), Item("great", rating = 9.0))

        assertEquals(listOf("great", "good", "unrated"), sorted(items, LibrarySort.RATING_DESC))
        assertEquals(listOf("unrated", "good", "great"), sorted(items, LibrarySort.RATING_ASC))
    }

    @Test
    fun `last played puts the most recent first and never-played last`() {
        val items = listOf(
            Item("never"),
            Item("yesterday", lastPlayed = 1_000L),
            Item("today", lastPlayed = 3_000L),
        )

        assertEquals(listOf("today", "yesterday", "never"), sorted(items, LibrarySort.LAST_PLAYED_DESC))
    }

    @Test
    fun `last played ties keep the input order`() {
        val items = listOf(
            Item("a", lastPlayed = 1_000L),
            Item("b", lastPlayed = 1_000L),
            Item("c", lastPlayed = 2_000L),
        )

        assertEquals(listOf("c", "a", "b"), sorted(items, LibrarySort.LAST_PLAYED_DESC))
    }

    @Test
    fun `sorting never mutates the input list`() {
        val items = mutableListOf(Item("banana"), Item("Apple"))
        val before = items.toList()

        sorted(items, LibrarySort.NAME_ASC)

        assertEquals(before, items)
    }
}
