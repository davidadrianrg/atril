package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.library.CoverStyle
import com.davidadrianrg.atril.core.library.LibraryCardSize
import com.davidadrianrg.atril.core.library.LibraryPreferenceKeys
import com.davidadrianrg.atril.core.library.LibrarySort
import com.davidadrianrg.atril.core.library.LibrarySource
import com.davidadrianrg.atril.core.library.LibraryViewMode
import com.davidadrianrg.atril.core.library.LibraryViewPreferences
import java.util.prefs.Preferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persistencia de vista, orden, origen, tamaño y estilo de carátula en el
 * escritorio.
 *
 * Se ejecuta contra el `user.home` y el `java.util.prefs.userRoot` aislados de
 * `build/test-home` que fija desktop/build.gradle.kts. Los cinco valores crudos
 * se guardan y se restauran en cada test para no contaminar a otros.
 */
class DesktopLibraryViewPrefsTest {

    private val prefs = Preferences.userNodeForPackage(DesktopConfig::class.java)
    private val keys = listOf(
        LibraryPreferenceKeys.VIEW_MODE,
        LibraryPreferenceKeys.SORT,
        LibraryPreferenceKeys.SOURCE,
        LibraryPreferenceKeys.CARD_SIZE,
        LibraryPreferenceKeys.COVER_STYLE,
    )
    private val previous = mutableMapOf<String, String?>()

    @Before
    fun requireIsolatedConfigAndClearKeys() {
        assertTrue(System.getProperty("java.util.prefs.userRoot").orEmpty().contains("test-home"))
        for (key in keys) {
            previous[key] = prefs.get(key, null)
            prefs.remove(key)
        }
    }

    @After
    fun restorePreviousValues() {
        for (key in keys) {
            val value = previous[key]
            if (value == null) prefs.remove(key) else prefs.put(key, value)
        }
    }

    @Test
    fun `absent keys load the current defaults`() {
        assertEquals(LibraryViewPreferences.DEFAULT, DesktopLibraryViewPrefs.load())
    }

    @Test
    fun `the four preferences round-trip through the isolated store`() {
        DesktopLibraryViewPrefs.setViewMode(LibraryViewMode.COVER_FLOW)
        DesktopLibraryViewPrefs.setSort(LibrarySort.LAST_PLAYED_DESC)
        DesktopLibraryViewPrefs.setSource(LibrarySource.LOCAL)
        DesktopLibraryViewPrefs.setCardSize(LibraryCardSize.LARGE)

        assertEquals(
            LibraryViewPreferences(
                viewMode = LibraryViewMode.COVER_FLOW,
                sort = LibrarySort.LAST_PLAYED_DESC,
                source = LibrarySource.LOCAL,
                cardSize = LibraryCardSize.LARGE,
            ),
            DesktopLibraryViewPrefs.load(),
        )
    }

    @Test
    fun `values are written under the canonical core keys`() {
        DesktopLibraryViewPrefs.setViewMode(LibraryViewMode.COVER_FLOW)
        DesktopLibraryViewPrefs.setSort(LibrarySort.YEAR_DESC)
        DesktopLibraryViewPrefs.setSource(LibrarySource.ROMM)
        DesktopLibraryViewPrefs.setCardSize(LibraryCardSize.SMALL)

        assertEquals("cover_flow", prefs.get(LibraryPreferenceKeys.VIEW_MODE, null))
        assertEquals("year_desc", prefs.get(LibraryPreferenceKeys.SORT, null))
        assertEquals("romm", prefs.get(LibraryPreferenceKeys.SOURCE, null))
        assertEquals("small", prefs.get(LibraryPreferenceKeys.CARD_SIZE, null))
    }

    @Test
    fun `absent cover style loads case`() {
        assertEquals(CoverStyle.CASE, DesktopLibraryViewPrefs.load().coverStyle)
    }

    @Test
    fun `the cover style round-trips through the isolated store`() {
        DesktopLibraryViewPrefs.setCoverStyle(CoverStyle.SCREENSHOT)

        assertEquals(CoverStyle.SCREENSHOT, DesktopLibraryViewPrefs.load().coverStyle)
        assertEquals("screenshot", prefs.get(LibraryPreferenceKeys.COVER_STYLE, null))
    }

    @Test
    fun `a corrupted cover style falls back to case without changing the other four`() {
        DesktopLibraryViewPrefs.setViewMode(LibraryViewMode.COVER_FLOW)
        DesktopLibraryViewPrefs.setSort(LibrarySort.YEAR_DESC)
        DesktopLibraryViewPrefs.setSource(LibrarySource.ROMM)
        DesktopLibraryViewPrefs.setCardSize(LibraryCardSize.SMALL)
        prefs.put(LibraryPreferenceKeys.COVER_STYLE, "hologram")

        assertEquals(
            LibraryViewPreferences(
                viewMode = LibraryViewMode.COVER_FLOW,
                sort = LibrarySort.YEAR_DESC,
                source = LibrarySource.ROMM,
                cardSize = LibraryCardSize.SMALL,
                coverStyle = CoverStyle.CASE,
            ),
            DesktopLibraryViewPrefs.load(),
        )
    }

    @Test
    fun `a corrupted key falls back without changing the other three`() {
        DesktopLibraryViewPrefs.setViewMode(LibraryViewMode.COVER_FLOW)
        DesktopLibraryViewPrefs.setSort(LibrarySort.LAST_PLAYED_DESC)
        DesktopLibraryViewPrefs.setSource(LibrarySource.LOCAL)
        DesktopLibraryViewPrefs.setCardSize(LibraryCardSize.LARGE)
        prefs.put(LibraryPreferenceKeys.SORT, "not_a_sort")

        assertEquals(
            LibraryViewPreferences(
                viewMode = LibraryViewMode.COVER_FLOW,
                sort = LibrarySort.NAME_ASC,
                source = LibrarySource.LOCAL,
                cardSize = LibraryCardSize.LARGE,
            ),
            DesktopLibraryViewPrefs.load(),
        )
    }
}
