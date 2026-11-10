package com.davidadrianrg.atril.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Índice alfabético A-Z + `#` de la Biblioteca.
 *
 * El índice se construye siempre sobre la lista ya filtrada y ordenada que se
 * pinta en pantalla, así que las pruebas pasan listas filtradas y comprueban
 * que el primer índice y los recuentos se calculan dentro de ese subconjunto.
 */
class LetterIndexTest {

    @Test
    fun `the bar is visible only above forty items`() {
        assertEquals(40, LetterIndex.VISIBILITY_THRESHOLD)
        assertFalse(LetterIndex.isVisible(40))
        assertTrue(LetterIndex.isVisible(41))
        assertEquals("#", LetterIndex.OTHER)
        assertEquals(('A'..'Z').map { it.toString() }, LetterIndex.ALPHABET)
    }

    @Test
    fun `accented initials fold to their base letter`() {
        assertEquals("A", LetterIndex.initialOf("Ánima"))
        assertEquals("E", LetterIndex.initialOf("Éclair"))
        assertEquals("N", LetterIndex.initialOf("Ñu"))
        assertEquals("S", LetterIndex.initialOf("ß"))
        assertEquals("A", LetterIndex.initialOf("  ángel"))
    }

    @Test
    fun `digits, symbols, blank and non Latin initials go to the other bucket`() {
        assertEquals("#", LetterIndex.initialOf("007"))
        assertEquals("#", LetterIndex.initialOf("!Bang"))
        assertEquals("#", LetterIndex.initialOf(""))
        assertEquals("#", LetterIndex.initialOf("   "))
        // Símbolo y escritura no latina: fuera de A-Z, siempre a "#".
        assertEquals("#", LetterIndex.initialOf("\u2605 Game"))
        assertEquals("#", LetterIndex.initialOf("\u65E5\u672C\u8A9E Game"))
    }

    @Test
    fun `index orders A to Z then the other bucket last`() {
        val index = LetterIndex.of(listOf("Zelda", "#1 Game", "Mario", "Ape", "!Wow"))

        assertEquals(listOf("A", "M", "Z", "#"), index.letters)
        assertEquals(
            listOf(
                LetterIndexEntry("A", firstIndex = 3, count = 1),
                LetterIndexEntry("M", firstIndex = 2, count = 1),
                LetterIndexEntry("Z", firstIndex = 0, count = 1),
                LetterIndexEntry("#", firstIndex = 1, count = 2),
            ),
            index.entries,
        )
    }

    @Test
    fun `the other bucket is absent when every title folds to a letter`() {
        val index = LetterIndex.of(listOf("Ape", "Banana"))

        assertEquals(listOf("A", "B"), index.letters)
        assertNull(index.firstIndexFor("#"))
    }

    @Test
    fun `letters without items are not jump targets`() {
        val index = LetterIndex.of(listOf("Mario", "Metroid"))

        assertNull(index.firstIndexFor("Q"))
        assertEquals(0, index.firstIndexFor("M"))
    }

    @Test
    fun `first index is computed inside a filtered subset`() {
        val all = listOf("Zelda", "Mario", "Zelda II", "Metroid")
        val searchFiltered = all.filter { it.contains("Zelda") }

        val index = LetterIndex.of(searchFiltered)

        assertEquals(listOf("Z"), index.letters)
        assertEquals(0, index.firstIndexFor("Z"))
        assertEquals(2, index.entries.single().count)
    }

    @Test
    fun `a favourites subset resolves jumps inside the subset`() {
        val favourites = listOf("Metroid", "Mario")

        val index = LetterIndex.of(favourites)

        assertEquals(0, index.firstIndexFor("M"))
        assertEquals(2, index.entries.single().count)
    }

    @Test
    fun `firstIndexFor is case-insensitive and trims`() {
        val index = LetterIndex.of(listOf("Mario"))

        assertEquals(0, index.firstIndexFor("m"))
        assertEquals(0, index.firstIndexFor("  M "))
    }

    @Test
    fun `counts sum to the indexed list size`() {
        val titles = listOf("Ape", "Banana", "#1 Game", "Cherry", "!Wow")

        val index = LetterIndex.of(titles)

        assertEquals(titles.size, index.entries.sumOf { it.count })
    }

    @Test
    fun `an empty list yields an empty index`() {
        assertTrue(LetterIndex.of(emptyList<String>()).entries.isEmpty())
        assertTrue(LetterIndex.of(emptyList<String>()).letters.isEmpty())
    }

    @Test
    fun `the item overload indexes by the displayed title`() {
        data class Game(val name: String)

        val index = LetterIndex.of(listOf(Game("Chrono Trigger"), Game("Chrono Cross"))) { it.name }

        assertEquals(listOf("C"), index.letters)
        assertEquals(0, index.firstIndexFor("C"))
        assertEquals(2, index.entries.single().count)
    }
}
