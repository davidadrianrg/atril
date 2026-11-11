package com.davidadrianrg.atril.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Formato de la línea de metadatos: año (UTC, epoch en segundos), hasta tres
 * géneros, plataforma y nota 0-100 redondeada, unidos por [GameMetadataLine.SEPARATOR].
 */
class GameMetadataLineTest {

    @Test
    fun `separator is space middle dot space and at most three genres`() {
        assertEquals(" \u00B7 ", GameMetadataLine.SEPARATOR)
        assertEquals(3, GameMetadataLine.MAX_GENRES)
    }

    @Test
    fun `formats year genres platform and rounded rating in that order`() {
        assertEquals(
            "2020 \u00B7 RPG \u00B7 Adventure \u00B7 SNES \u00B7 85",
            GameMetadataLine.format(
                releaseEpochSeconds = 1577836800L,
                genres = listOf("RPG", "Adventure"),
                platform = "SNES",
                rating = 84.5,
            ),
        )
    }

    @Test
    fun `year always precedes genres platform and rating`() {
        assertEquals(
            "2021 \u00B7 Action \u00B7 PC \u00B7 90",
            GameMetadataLine.format(1609459200L, listOf("Action"), "PC", 90.0),
        )
    }

    @Test
    fun `a single present part is rendered without a separator`() {
        assertEquals("SNES", GameMetadataLine.format(null, emptyList(), "SNES", null))
    }

    @Test
    fun `all inputs absent yields null rather than an empty string`() {
        assertNull(GameMetadataLine.format(null, emptyList(), null, null))
        assertNull(GameMetadataLine.format(null, emptyList(), "   ", null))
    }

    @Test
    fun `zero and negative epochs omit the year without throwing`() {
        assertEquals("SNES", GameMetadataLine.format(0L, emptyList(), "SNES", null))
        assertEquals("SNES", GameMetadataLine.format(-1L, emptyList(), "SNES", null))
        assertNull(GameMetadataLine.format(-42L, emptyList(), null, null))
    }

    @Test
    fun `epochs outside the supported range omit the year without throwing`() {
        assertEquals("SNES", GameMetadataLine.format(253402300800L, emptyList(), "SNES", null))
        assertNull(GameMetadataLine.format(Long.MAX_VALUE, emptyList(), null, null))
    }

    @Test
    fun `the supported epoch boundaries map to 1970 and 9999 in UTC`() {
        assertEquals("1970", GameMetadataLine.format(1L, emptyList(), null, null))
        assertEquals("9999", GameMetadataLine.format(253402300799L, emptyList(), null, null))
    }

    @Test
    fun `genres are trimmed, blanks are dropped and duplicates removed`() {
        assertEquals(
            "RPG \u00B7 Adventure",
            GameMetadataLine.format(
                releaseEpochSeconds = null,
                genres = listOf(" RPG ", "", "RPG", "   ", "Adventure"),
                platform = null,
                rating = null,
            ),
        )
    }

    @Test
    fun `at most three genres are kept in list order`() {
        assertEquals(
            "Action \u00B7 RPG \u00B7 Strategy",
            GameMetadataLine.format(
                releaseEpochSeconds = null,
                genres = listOf("Action", "RPG", "Strategy", "Puzzle"),
                platform = null,
                rating = null,
            ),
        )
    }

    @Test
    fun `blank platform is omitted`() {
        assertEquals("Action", GameMetadataLine.format(null, listOf("Action"), "   ", null))
        assertNull(GameMetadataLine.format(null, emptyList(), "", null))
    }

    @Test
    fun `null zero negative nan and infinities omit the rating`() {
        assertNull(GameMetadataLine.format(null, emptyList(), null, null))
        assertNull(GameMetadataLine.format(null, emptyList(), null, 0.0))
        assertNull(GameMetadataLine.format(null, emptyList(), null, -5.0))
        assertNull(GameMetadataLine.format(null, emptyList(), null, Double.NaN))
        assertNull(GameMetadataLine.format(null, emptyList(), null, Double.POSITIVE_INFINITY))
        assertNull(GameMetadataLine.format(null, emptyList(), null, Double.NEGATIVE_INFINITY))
        assertEquals("Action", GameMetadataLine.format(null, listOf("Action"), null, 0.0))
    }

    @Test
    fun `ratings round half-up on the 0-100 scale`() {
        assertEquals("84", GameMetadataLine.format(null, emptyList(), null, 84.4))
        assertEquals("85", GameMetadataLine.format(null, emptyList(), null, 84.5))
        assertEquals("1", GameMetadataLine.format(null, emptyList(), null, 1.4))
        assertEquals("1", GameMetadataLine.format(null, emptyList(), null, 0.5))
        assertEquals("0", GameMetadataLine.format(null, emptyList(), null, 0.4))
        assertEquals("100", GameMetadataLine.format(null, emptyList(), null, 100.0))
    }
}
