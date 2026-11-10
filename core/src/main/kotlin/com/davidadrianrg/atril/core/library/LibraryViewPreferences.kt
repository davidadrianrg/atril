package com.davidadrianrg.atril.core.library

/**
 * How the Library lists its games.
 *
 * The [id] is what gets persisted; it is stable and never derived from the
 * enum name or the translated label.
 *
 * @property id persisted identifier.
 * @property labelKey translation key of the visible label.
 */
enum class LibraryViewMode(val id: String, val labelKey: String) {
    GRID("grid", "library.view.grid"),
    COVER_FLOW("cover_flow", "library.view.cover_flow"),
    ;

    companion object {
        /** Mode used when nothing is persisted or the stored id is unknown. */
        val DEFAULT: LibraryViewMode = GRID

        /** Maps a persisted [raw] id to its mode, falling back to [DEFAULT]. */
        fun fromId(raw: String?): LibraryViewMode = entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/**
 * Which games the Library lists.
 *
 * Per the binding decision the Library content is RomM-only: [LOCAL] shows the
 * explicit "local games live in Play" empty state, while [ALL] and [ROMM] are
 * content-equivalent. Source is a filter over the existing cards, not a new
 * data source.
 *
 * @property id persisted identifier.
 * @property labelKey translation key of the visible label.
 */
enum class LibrarySource(val id: String, val labelKey: String) {
    ALL("all", "library.source.all"),
    LOCAL("local", "library.source.local"),
    ROMM("romm", "library.source.romm"),
    ;

    companion object {
        /** Source used when nothing is persisted or the stored id is unknown. */
        val DEFAULT: LibrarySource = ALL

        /** Maps a persisted [raw] id to its source, falling back to [DEFAULT]. */
        fun fromId(raw: String?): LibrarySource = entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/**
 * Scale of the Library cards in the grid and in the cover flow.
 *
 * [scale] multiplies the base card size of each platform, so [MEDIUM] keeps the
 * layout the app had before this preference existed.
 *
 * @property id persisted identifier.
 * @property labelKey translation key of the visible label.
 * @property scale multiplier applied to the base card size.
 */
enum class LibraryCardSize(val id: String, val labelKey: String, val scale: Float) {
    SMALL("small", "library.size.small", 0.85f),
    MEDIUM("medium", "library.size.medium", 1f),
    LARGE("large", "library.size.large", 1.25f),
    ;

    companion object {
        /** Size used when nothing is persisted or the stored id is unknown. */
        val DEFAULT: LibraryCardSize = MEDIUM

        /** Maps a persisted [raw] id to its size, falling back to [DEFAULT]. */
        fun fromId(raw: String?): LibraryCardSize = entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/**
 * The four Library preferences, decoded and validated.
 *
 * Use [fromIds] to build it from persisted values: each dimension applies its
 * own fallback, so one corrupted key never invalidates the other three.
 *
 * @property viewMode grid or cover flow.
 * @property sort ordering criterion.
 * @property source which games are listed.
 * @property cardSize card scale in the grid and the cover flow.
 */
data class LibraryViewPreferences(
    val viewMode: LibraryViewMode = LibraryViewMode.DEFAULT,
    val sort: LibrarySort = LibrarySort.DEFAULT,
    val source: LibrarySource = LibrarySource.DEFAULT,
    val cardSize: LibraryCardSize = LibraryCardSize.DEFAULT,
) {
    companion object {
        /** Absent keys reproduce the layout and ordering the app had before. */
        val DEFAULT: LibraryViewPreferences = LibraryViewPreferences()

        /**
         * Builds the preferences from the persisted ids of each dimension.
         * Null, blank and unknown ids fall back to the documented default of
         * that dimension only.
         */
        fun fromIds(
            viewMode: String?,
            sort: String?,
            source: String?,
            cardSize: String?,
        ): LibraryViewPreferences = LibraryViewPreferences(
            viewMode = LibraryViewMode.fromId(viewMode),
            sort = LibrarySort.fromId(sort),
            source = LibrarySource.fromId(source),
            cardSize = LibraryCardSize.fromId(cardSize),
        )
    }
}

/**
 * Canonical persistence keys of the Library preferences.
 *
 * Android stores them as DataStore preference names and desktop as
 * `java.util.prefs` / properties keys; neither platform may invent its own
 * spelling.
 */
object LibraryPreferenceKeys {
    /** Persisted [LibraryViewMode] id. */
    const val VIEW_MODE = "library_view_mode"

    /** Persisted [LibrarySort] id. */
    const val SORT = "library_sort"

    /** Persisted [LibrarySource] id. */
    const val SOURCE = "library_source"

    /** Persisted [LibraryCardSize] id. */
    const val CARD_SIZE = "library_card_size"

    /** Persisted group key -> chosen disc id map for local multidisc launches. */
    const val LOCAL_DISCS = "library_local_discs"
}
