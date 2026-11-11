package com.davidadrianrg.atril.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Resolución de la carátula visible por estilo, con su cadena de fallback:
 * CASE usa grande y luego pequeña; PHYSICAL y SCREENSHOT solo ganan cuando su
 * medio existe y, si no, caen a CASE; sin ningún candidato el resultado es null
 * (la UI pinta el placeholder existente).
 */
class CoverArtworkTest {

    @Test
    fun `case prefers the large cover over the small one`() {
        assertEquals(
            "https://cdn.atril.test/large.png",
            CoverArtwork.coverUrl(
                style = CoverStyle.CASE,
                coverUrlLarge = "https://cdn.atril.test/large.png",
                coverUrlSmall = "https://cdn.atril.test/small.png",
                physicalMediaUrl = "https://cdn.atril.test/cart.png",
                screenshots = listOf("https://cdn.atril.test/shot-1.png"),
            ),
        )
    }

    @Test
    fun `case falls back to the small cover when large is null or blank`() {
        assertEquals(
            "https://cdn.atril.test/small.png",
            CoverArtwork.coverUrl(
                CoverStyle.CASE,
                coverUrlLarge = null,
                coverUrlSmall = "https://cdn.atril.test/small.png",
                physicalMediaUrl = null,
                screenshots = emptyList(),
            ),
        )
        assertEquals(
            "https://cdn.atril.test/small.png",
            CoverArtwork.coverUrl(
                CoverStyle.CASE,
                coverUrlLarge = "   ",
                coverUrlSmall = "https://cdn.atril.test/small.png",
                physicalMediaUrl = null,
                screenshots = emptyList(),
            ),
        )
    }

    @Test
    fun `case returns null when both covers are absent`() {
        assertNull(
            CoverArtwork.coverUrl(CoverStyle.CASE, null, null, null, emptyList()),
        )
        assertNull(
            CoverArtwork.coverUrl(CoverStyle.CASE, "  ", "\t", null, emptyList()),
        )
    }

    @Test
    fun `physical returns the physical media and ignores the covers`() {
        assertEquals(
            "https://cdn.atril.test/cart.png",
            CoverArtwork.coverUrl(
                CoverStyle.PHYSICAL,
                coverUrlLarge = "https://cdn.atril.test/large.png",
                coverUrlSmall = "https://cdn.atril.test/small.png",
                physicalMediaUrl = "https://cdn.atril.test/cart.png",
                screenshots = listOf("https://cdn.atril.test/shot-1.png"),
            ),
        )
    }

    @Test
    fun `physical falls back to the case chain when its media is absent`() {
        assertEquals(
            "https://cdn.atril.test/large.png",
            CoverArtwork.coverUrl(
                CoverStyle.PHYSICAL,
                coverUrlLarge = "https://cdn.atril.test/large.png",
                coverUrlSmall = "https://cdn.atril.test/small.png",
                physicalMediaUrl = null,
                screenshots = emptyList(),
            ),
        )
        assertEquals(
            "https://cdn.atril.test/small.png",
            CoverArtwork.coverUrl(
                CoverStyle.PHYSICAL,
                coverUrlLarge = null,
                coverUrlSmall = "https://cdn.atril.test/small.png",
                physicalMediaUrl = "   ",
                screenshots = emptyList(),
            ),
        )
        assertNull(
            CoverArtwork.coverUrl(CoverStyle.PHYSICAL, null, null, null, emptyList()),
        )
    }

    @Test
    fun `screenshot returns the first non-blank screenshot even when covers exist`() {
        assertEquals(
            "https://cdn.atril.test/shot-1.png",
            CoverArtwork.coverUrl(
                CoverStyle.SCREENSHOT,
                coverUrlLarge = "https://cdn.atril.test/large.png",
                coverUrlSmall = "https://cdn.atril.test/small.png",
                physicalMediaUrl = "https://cdn.atril.test/cart.png",
                screenshots = listOf(
                    "https://cdn.atril.test/shot-1.png",
                    "https://cdn.atril.test/shot-2.png",
                ),
            ),
        )
    }

    @Test
    fun `screenshot skips blank entries and uses the next non-blank one`() {
        assertEquals(
            "https://cdn.atril.test/shot-2.png",
            CoverArtwork.coverUrl(
                CoverStyle.SCREENSHOT,
                coverUrlLarge = "https://cdn.atril.test/large.png",
                coverUrlSmall = null,
                physicalMediaUrl = null,
                screenshots = listOf("", "   ", "https://cdn.atril.test/shot-2.png"),
            ),
        )
    }

    @Test
    fun `screenshot falls back to the case chain when there is no screenshot`() {
        assertEquals(
            "https://cdn.atril.test/large.png",
            CoverArtwork.coverUrl(
                CoverStyle.SCREENSHOT,
                coverUrlLarge = "https://cdn.atril.test/large.png",
                coverUrlSmall = "https://cdn.atril.test/small.png",
                physicalMediaUrl = null,
                screenshots = emptyList(),
            ),
        )
        assertEquals(
            "https://cdn.atril.test/small.png",
            CoverArtwork.coverUrl(
                CoverStyle.SCREENSHOT,
                coverUrlLarge = null,
                coverUrlSmall = "https://cdn.atril.test/small.png",
                physicalMediaUrl = null,
                screenshots = listOf(" ", "\t"),
            ),
        )
    }

    @Test
    fun `screenshot returns null when screenshots and covers are all absent`() {
        assertNull(
            CoverArtwork.coverUrl(
                CoverStyle.SCREENSHOT,
                coverUrlLarge = null,
                coverUrlSmall = "  ",
                physicalMediaUrl = null,
                screenshots = listOf("", "   "),
            ),
        )
    }

    @Test
    fun `returned urls are trimmed`() {
        assertEquals(
            "https://cdn.atril.test/large.png",
            CoverArtwork.coverUrl(
                CoverStyle.CASE,
                coverUrlLarge = "  https://cdn.atril.test/large.png  ",
                coverUrlSmall = null,
                physicalMediaUrl = null,
                screenshots = emptyList(),
            ),
        )
        assertEquals(
            "https://cdn.atril.test/cart.png",
            CoverArtwork.coverUrl(
                CoverStyle.PHYSICAL,
                coverUrlLarge = null,
                coverUrlSmall = null,
                physicalMediaUrl = "\nhttps://cdn.atril.test/cart.png\t",
                screenshots = emptyList(),
            ),
        )
        assertEquals(
            "https://cdn.atril.test/shot-1.png",
            CoverArtwork.coverUrl(
                CoverStyle.SCREENSHOT,
                coverUrlLarge = null,
                coverUrlSmall = null,
                physicalMediaUrl = null,
                screenshots = listOf("  https://cdn.atril.test/shot-1.png  "),
            ),
        )
    }
}
