package com.davidadrianrg.atril.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Preferencias de vista de la Biblioteca: ids persistidos canónicos, valores
 * por defecto y decodificación tolerante a claves corruptas.
 */
class LibraryViewPreferencesTest {

    @Test
    fun `defaults reproduce the current layout and ordering`() {
        assertEquals(LibraryViewMode.GRID, LibraryViewMode.DEFAULT)
        assertEquals(LibrarySort.NAME_ASC, LibrarySort.DEFAULT)
        assertEquals(LibrarySource.ALL, LibrarySource.DEFAULT)
        assertEquals(LibraryCardSize.MEDIUM, LibraryCardSize.DEFAULT)
        assertEquals(CoverStyle.CASE, CoverStyle.DEFAULT)
        assertEquals(CoverStyle.CASE, LibraryViewPreferences.DEFAULT.coverStyle)
        assertEquals(
            LibraryViewPreferences(
                viewMode = LibraryViewMode.GRID,
                sort = LibrarySort.NAME_ASC,
                source = LibrarySource.ALL,
                cardSize = LibraryCardSize.MEDIUM,
            ),
            LibraryViewPreferences.DEFAULT,
        )
        assertEquals(LibraryViewPreferences(), LibraryViewPreferences.DEFAULT)
    }

    @Test
    fun `persisted ids are the canonical strings`() {
        assertEquals(listOf("grid", "cover_flow"), LibraryViewMode.entries.map { it.id })
        assertEquals(listOf("all", "local", "romm"), LibrarySource.entries.map { it.id })
        assertEquals(listOf("small", "medium", "large"), LibraryCardSize.entries.map { it.id })
        assertEquals(listOf(0.85f, 1f, 1.25f), LibraryCardSize.entries.map { it.scale })
        assertEquals(listOf("case", "physical", "screenshot"), CoverStyle.entries.map { it.id })
        assertEquals(
            listOf(
                "library.cover_style.case",
                "library.cover_style.physical",
                "library.cover_style.screenshot",
            ),
            CoverStyle.entries.map { it.labelKey },
        )
        assertEquals(
            listOf(
                "name_asc",
                "name_desc",
                "size_desc",
                "size_asc",
                "year_desc",
                "year_asc",
                "rating_desc",
                "rating_asc",
                "last_played_desc",
            ),
            LibrarySort.entries.map { it.id },
        )
    }

    @Test
    fun `unknown, blank and case-mismatched ids fall back to the documented default`() {
        assertEquals(LibraryViewMode.GRID, LibraryViewMode.fromId(null))
        assertEquals(LibraryViewMode.GRID, LibraryViewMode.fromId(""))
        assertEquals(LibraryViewMode.GRID, LibraryViewMode.fromId("Cover_Flow"))
        assertEquals(LibraryViewMode.COVER_FLOW, LibraryViewMode.fromId("cover_flow"))

        assertEquals(LibrarySource.ALL, LibrarySource.fromId("ROMM"))
        assertEquals(LibrarySource.LOCAL, LibrarySource.fromId("local"))

        assertEquals(LibraryCardSize.MEDIUM, LibraryCardSize.fromId("huge"))
        assertEquals(LibraryCardSize.LARGE, LibraryCardSize.fromId("large"))

        assertEquals(LibrarySort.NAME_ASC, LibrarySort.fromId("bogus"))
        assertEquals(LibrarySort.LAST_PLAYED_DESC, LibrarySort.fromId("last_played_desc"))
    }

    @Test
    fun `fromIds applies each fallback without clobbering the other three`() {
        val preferences = LibraryViewPreferences.fromIds(
            viewMode = "cover_flow",
            sort = "bogus",
            source = "local",
            cardSize = "huge",
        )

        assertEquals(
            LibraryViewPreferences(
                viewMode = LibraryViewMode.COVER_FLOW,
                sort = LibrarySort.NAME_ASC,
                source = LibrarySource.LOCAL,
                cardSize = LibraryCardSize.MEDIUM,
            ),
            preferences,
        )
    }

    @Test
    fun `every preference combination round-trips through its ids`() {
        for (viewMode in LibraryViewMode.entries) {
            for (sort in LibrarySort.entries) {
                for (source in LibrarySource.entries) {
                    for (cardSize in LibraryCardSize.entries) {
                        val preferences = LibraryViewPreferences(viewMode, sort, source, cardSize)
                        assertEquals(
                            preferences,
                            LibraryViewPreferences.fromIds(
                                preferences.viewMode.id,
                                preferences.sort.id,
                                preferences.source.id,
                                preferences.cardSize.id,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `unknown cover style falls back to case without clobbering the other four`() {
        val preferences = LibraryViewPreferences.fromIds(
            viewMode = "cover_flow",
            sort = "last_played_desc",
            source = "local",
            cardSize = "large",
            coverStyle = "hologram",
        )

        assertEquals(
            LibraryViewPreferences(
                viewMode = LibraryViewMode.COVER_FLOW,
                sort = LibrarySort.LAST_PLAYED_DESC,
                source = LibrarySource.LOCAL,
                cardSize = LibraryCardSize.LARGE,
                coverStyle = CoverStyle.CASE,
            ),
            preferences,
        )
    }

    @Test
    fun `a valid cover style survives while another corrupted dimension falls back`() {
        val preferences = LibraryViewPreferences.fromIds(
            viewMode = "grid",
            sort = "not_a_sort",
            source = "romm",
            cardSize = "small",
            coverStyle = "screenshot",
        )

        assertEquals(
            LibraryViewPreferences(
                viewMode = LibraryViewMode.GRID,
                sort = LibrarySort.NAME_ASC,
                source = LibrarySource.ROMM,
                cardSize = LibraryCardSize.SMALL,
                coverStyle = CoverStyle.SCREENSHOT,
            ),
            preferences,
        )
    }

    @Test
    fun `every preference combination round-trips through its five ids`() {
        for (viewMode in LibraryViewMode.entries) {
            for (sort in LibrarySort.entries) {
                for (source in LibrarySource.entries) {
                    for (cardSize in LibraryCardSize.entries) {
                        for (coverStyle in CoverStyle.entries) {
                            val preferences = LibraryViewPreferences(
                                viewMode,
                                sort,
                                source,
                                cardSize,
                                coverStyle,
                            )
                            assertEquals(
                                preferences,
                                LibraryViewPreferences.fromIds(
                                    preferences.viewMode.id,
                                    preferences.sort.id,
                                    preferences.source.id,
                                    preferences.cardSize.id,
                                    preferences.coverStyle.id,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `legacy four-argument fromIds keeps compiling and yields the case default`() {
        val preferences = LibraryViewPreferences.fromIds(
            viewMode = "cover_flow",
            sort = "year_asc",
            source = "romm",
            cardSize = "small",
        )

        assertEquals(CoverStyle.CASE, preferences.coverStyle)
        assertEquals(
            LibraryViewPreferences(
                viewMode = LibraryViewMode.COVER_FLOW,
                sort = LibrarySort.YEAR_ASC,
                source = LibrarySource.ROMM,
                cardSize = LibraryCardSize.SMALL,
                coverStyle = CoverStyle.CASE,
            ),
            preferences,
        )
    }

    @Test
    fun `preference keys are the canonical names shared by both platforms`() {
        assertEquals("library_view_mode", LibraryPreferenceKeys.VIEW_MODE)
        assertEquals("library_sort", LibraryPreferenceKeys.SORT)
        assertEquals("library_source", LibraryPreferenceKeys.SOURCE)
        assertEquals("library_card_size", LibraryPreferenceKeys.CARD_SIZE)
        assertEquals("library_cover_style", LibraryPreferenceKeys.COVER_STYLE)
        assertEquals("library_local_discs", LibraryPreferenceKeys.LOCAL_DISCS)
    }

    @Test
    fun `sort labels derive from the persisted id`() {
        assertEquals("state.library_sort.name_asc", LibrarySort.NAME_ASC.labelKey)
        assertEquals("state.library_sort.last_played_desc", LibrarySort.LAST_PLAYED_DESC.labelKey)
    }
}
