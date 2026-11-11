package com.davidadrianrg.atril.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Elección del fondo cinematográfico: primera captura no vacía, luego la
 * carátula grande, nunca la pequeña y, si no hay nada, null (solo el scrim).
 */
class HeroBackdropTest {

    @Test
    fun `first non-blank screenshot wins over the large cover`() {
        assertEquals(
            "https://cdn.atril.test/shot-1.png",
            CoverArtwork.heroBackdropUrl(
                screenshots = listOf(
                    "https://cdn.atril.test/shot-1.png",
                    "https://cdn.atril.test/shot-2.png",
                ),
                coverUrlLarge = "https://cdn.atril.test/large.png",
            ),
        )
    }

    @Test
    fun `blank first screenshot is skipped in favor of the next non-blank one`() {
        assertEquals(
            "https://cdn.atril.test/shot-2.png",
            CoverArtwork.heroBackdropUrl(
                screenshots = listOf("", "   ", "https://cdn.atril.test/shot-2.png"),
                coverUrlLarge = "https://cdn.atril.test/large.png",
            ),
        )
    }

    @Test
    fun `empty or all-blank screenshots fall back to the large cover`() {
        assertEquals(
            "https://cdn.atril.test/large.png",
            CoverArtwork.heroBackdropUrl(emptyList(), "https://cdn.atril.test/large.png"),
        )
        assertEquals(
            "https://cdn.atril.test/large.png",
            CoverArtwork.heroBackdropUrl(listOf(" ", "\t"), "https://cdn.atril.test/large.png"),
        )
    }

    @Test
    fun `no screenshot and no large cover means no hero`() {
        // coverUrlSmall is deliberately not an input: it can never be upscaled
        // to a hero, so the only possible fallback here is null.
        assertNull(
            CoverArtwork.heroBackdropUrl(
                screenshots = emptyList(),
                coverUrlLarge = null,
            ),
        )
    }

    @Test
    fun `everything absent returns null`() {
        assertNull(CoverArtwork.heroBackdropUrl(emptyList(), null))
        assertNull(CoverArtwork.heroBackdropUrl(listOf("", "  "), "   "))
    }

    @Test
    fun `returned url is trimmed`() {
        assertEquals(
            "https://cdn.atril.test/shot-1.png",
            CoverArtwork.heroBackdropUrl(listOf("  https://cdn.atril.test/shot-1.png "), null),
        )
        assertEquals(
            "https://cdn.atril.test/large.png",
            CoverArtwork.heroBackdropUrl(emptyList(), "  https://cdn.atril.test/large.png  "),
        )
    }
}
