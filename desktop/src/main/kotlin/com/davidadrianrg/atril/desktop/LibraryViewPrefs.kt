package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.library.CoverStyle
import com.davidadrianrg.atril.core.library.LibraryCardSize
import com.davidadrianrg.atril.core.library.LibrarySort
import com.davidadrianrg.atril.core.library.LibrarySource
import com.davidadrianrg.atril.core.library.LibraryViewMode
import com.davidadrianrg.atril.core.library.LibraryViewPreferences

/**
 * Typed load/save of the five Library preferences over [DesktopConfig].
 *
 * It is the only place on desktop that maps the persisted strings to the
 * shared enums of `:core`, so an absent or unknown value always falls back to
 * the documented default of that dimension instead of failing.
 */
object DesktopLibraryViewPrefs {

    /** Reads the five preferences, validating each persisted id. */
    fun load(): LibraryViewPreferences = LibraryViewPreferences.fromIds(
        viewMode = DesktopConfig.libraryViewMode,
        sort = DesktopConfig.librarySort,
        source = DesktopConfig.librarySource,
        cardSize = DesktopConfig.libraryCardSize,
        coverStyle = DesktopConfig.libraryCoverStyle,
    )

    /** Persists the view mode (grid / cover flow). */
    fun setViewMode(mode: LibraryViewMode) {
        DesktopConfig.libraryViewMode = mode.id
    }

    /** Persists the ordering criterion. */
    fun setSort(sort: LibrarySort) {
        DesktopConfig.librarySort = sort.id
    }

    /** Persists the source (all / local / RomM). */
    fun setSource(source: LibrarySource) {
        DesktopConfig.librarySource = source.id
    }

    /** Persists the card size. */
    fun setCardSize(size: LibraryCardSize) {
        DesktopConfig.libraryCardSize = size.id
    }

    /** Persists the cover style (case / physical / screenshot). */
    fun setCoverStyle(style: CoverStyle) {
        DesktopConfig.libraryCoverStyle = style.id
    }
}
