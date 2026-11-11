package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.library.CoverStyle
import com.davidadrianrg.atril.core.remote.dto.IgdbMetadataDto
import com.davidadrianrg.atril.core.remote.dto.PhysicalMetadataDto
import com.davidadrianrg.atril.core.remote.dto.RomDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Adaptador de escritorio: convierte un [RomDto] en las entradas planas de
 * `:core` construyendo las URLs absolutas fuera del módulo puro. No toca red ni
 * disco: la decisión (estilo y cadena de fallback) es lo que se comprueba.
 */
class DesktopCoverPresentationTest {

    private val server = "http://romm.local:8080"

    private fun rom(
        urlCover: String? = null,
        pathCoverLarge: String? = null,
        pathCoverSmall: String? = null,
        screenshots: List<String> = emptyList(),
        physicalPath: String? = null,
        genres: List<String> = emptyList(),
        igdb: IgdbMetadataDto? = null,
    ): RomDto = RomDto(
        id = 7,
        name = "Chrono Trigger",
        fileName = "Chrono Trigger.sfc",
        platformId = 13,
        urlCover = urlCover,
        pathCoverLarge = pathCoverLarge,
        pathCoverSmall = pathCoverSmall,
        mergedScreenshots = screenshots,
        genres = genres,
        igdbMetadata = igdb,
        ssMetadata = physicalPath?.let { PhysicalMetadataDto(physicalPath = it) },
    )

    @Test
    fun `case resolves the relative large path against the server before the absolute cover`() {
        val dto = rom(
            urlCover = "https://cdn.atril.test/cover.png",
            pathCoverLarge = "/assets/romm/resources/roms/7/cover/big.png",
        )

        assertEquals(
            "http://romm.local:8080/assets/romm/resources/roms/7/cover/big.png",
            coverUrlFor(dto, server, CoverStyle.CASE),
        )
    }

    @Test
    fun `case uses the absolute cover when there is no relative path`() {
        val dto = rom(urlCover = "https://cdn.atril.test/cover.png")

        assertEquals("https://cdn.atril.test/cover.png", coverUrlFor(dto, server, CoverStyle.CASE))
    }

    @Test
    fun `case falls back to the small relative path when the large one is absent`() {
        val dto = rom(pathCoverSmall = "/assets/romm/resources/roms/7/cover/small.png")

        assertEquals(
            "http://romm.local:8080/assets/romm/resources/roms/7/cover/small.png",
            coverUrlFor(dto, server, CoverStyle.CASE),
        )
    }

    @Test
    fun `physical resolves the physical media path and ignores the covers`() {
        val dto = rom(
            pathCoverLarge = "/assets/romm/resources/roms/7/cover/big.png",
            physicalPath = "/assets/romm/resources/roms/7/physical/cart.png",
        )

        assertEquals(
            "http://romm.local:8080/assets/romm/resources/roms/7/physical/cart.png",
            coverUrlFor(dto, server, CoverStyle.PHYSICAL),
        )
    }

    @Test
    fun `physical falls back to the case chain when the media is absent`() {
        val dto = rom(pathCoverLarge = "/assets/romm/resources/roms/7/cover/big.png")

        assertEquals(
            "http://romm.local:8080/assets/romm/resources/roms/7/cover/big.png",
            coverUrlFor(dto, server, CoverStyle.PHYSICAL),
        )
    }

    @Test
    fun `screenshot resolves the merged screenshots and falls back to case`() {
        val withScreenshot = rom(
            pathCoverLarge = "/assets/romm/resources/roms/7/cover/big.png",
            screenshots = listOf(
                "/assets/romm/resources/roms/7/screenshots/1.png",
                "/assets/romm/resources/roms/7/screenshots/2.png",
            ),
        )
        assertEquals(
            "http://romm.local:8080/assets/romm/resources/roms/7/screenshots/1.png",
            coverUrlFor(withScreenshot, server, CoverStyle.SCREENSHOT),
        )

        val withoutScreenshot = rom(
            pathCoverLarge = "/assets/romm/resources/roms/7/cover/big.png",
        )
        assertEquals(
            "http://romm.local:8080/assets/romm/resources/roms/7/cover/big.png",
            coverUrlFor(withoutScreenshot, server, CoverStyle.SCREENSHOT),
        )
    }

    @Test
    fun `screenshot skips a blank first entry and uses the next one`() {
        val dto = rom(
            screenshots = listOf("", "/assets/romm/resources/roms/7/screenshots/2.png"),
        )

        assertEquals(
            "http://romm.local:8080/assets/romm/resources/roms/7/screenshots/2.png",
            coverUrlFor(dto, server, CoverStyle.SCREENSHOT),
        )
    }

    @Test
    fun `hero prefers the first screenshot and never the small cover`() {
        val withScreenshot = rom(
            pathCoverLarge = "/assets/romm/resources/roms/7/cover/big.png",
            pathCoverSmall = "/assets/romm/resources/roms/7/cover/small.png",
            screenshots = listOf("/assets/romm/resources/roms/7/screenshots/1.png"),
        )
        assertEquals(
            "http://romm.local:8080/assets/romm/resources/roms/7/screenshots/1.png",
            heroBackdropUrl(withScreenshot, server),
        )

        val smallOnly = rom(pathCoverSmall = "/assets/romm/resources/roms/7/cover/small.png")
        assertNull(heroBackdropUrl(smallOnly, server))
    }

    @Test
    fun `hero falls back to the large cover when there is no screenshot`() {
        val dto = rom(pathCoverLarge = "/assets/romm/resources/roms/7/cover/big.png")

        assertEquals(
            "http://romm.local:8080/assets/romm/resources/roms/7/cover/big.png",
            heroBackdropUrl(dto, server),
        )
    }

    @Test
    fun `metadata line uses the year genres platform and rounded rating from the dto`() {
        val dto = rom(
            genres = listOf("RPG", "Adventure", "Strategy", "Puzzle"),
            igdb = IgdbMetadataDto(
                totalRating = "84.5",
                firstReleaseDate = 1577836800L,
            ),
        )

        assertEquals(
            "2020 \u00B7 RPG \u00B7 Adventure \u00B7 Strategy \u00B7 Super Nintendo \u00B7 85",
            metadataLine(dto, "Super Nintendo", server),
        )
    }

    @Test
    fun `metadata line omits a zero rating`() {
        val dto = rom(
            genres = listOf("RPG"),
            igdb = IgdbMetadataDto(totalRating = "0.0"),
        )

        assertEquals("RPG", metadataLine(dto, null, server))
    }

    @Test
    fun `a local-game shaped dto resolves its file url for cover and hero`() {
        val dto = rom(urlCover = "file:///roms/local-game.zip")

        assertEquals("file:///roms/local-game.zip", coverUrlFor(dto, server, CoverStyle.CASE))
        assertEquals("file:///roms/local-game.zip", heroBackdropUrl(dto, server))
        assertEquals("Mega Drive", metadataLine(dto, "Mega Drive", server))
        assertNull(metadataLine(dto, null, server))
    }
}
