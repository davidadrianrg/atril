package com.davidadrianrg.atril.core.library

import com.davidadrianrg.atril.core.i18n.tr

/**
 * Ordering criteria of the Library, shared by Android and desktop.
 *
 * The [id] is the lowercase constant name so the existing translation keys
 * `state.library_sort.<id>` keep working for every value, including the new
 * [LAST_PLAYED_DESC]. The historical criteria are kept verbatim: nothing is
 * removed, some are simply not offered by the view-options panel.
 *
 * @property id persisted identifier and suffix of its translation key.
 */
enum class LibrarySort(val id: String) {
    NAME_ASC("name_asc"),
    NAME_DESC("name_desc"),
    SIZE_DESC("size_desc"),
    SIZE_ASC("size_asc"),
    YEAR_DESC("year_desc"),
    YEAR_ASC("year_asc"),
    RATING_DESC("rating_desc"),
    RATING_ASC("rating_asc"),
    LAST_PLAYED_DESC("last_played_desc"),
    ;

    /** Translation key of the visible label (`state.library_sort.*`). */
    val labelKey: String get() = "state.library_sort.$id"

    /** Translated label of the criterion. */
    val label: String get() = tr(labelKey)

    companion object {
        /** Ordering used when nothing is persisted or the stored id is unknown. */
        val DEFAULT: LibrarySort = NAME_ASC

        /** Maps a persisted [raw] id to its criterion, falling back to [DEFAULT]. */
        fun fromId(raw: String?): LibrarySort = entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/**
 * Pure ordering of Library items by a [LibrarySort] criterion.
 *
 * Every value is read through an accessor so the same function serves the
 * Android `RomWithStatus` and the desktop `GameCard`, and so it can be tested
 * without Android or Compose types. The comparison semantics reproduce the
 * comparators both platforms used before:
 *
 * - name: case-insensitive.
 * - size: raw bytes.
 * - year: missing year sorts last in both directions (`0` descending,
 *   [Long.MAX_VALUE] ascending).
 * - rating: missing rating sorts last in both directions (`-1.0`).
 * - last played: newest first, never-played items always last.
 *
 * The sort is stable: ties keep their input order. The input list is never
 * mutated.
 */
object LibrarySorting {

    /**
     * Returns [items] ordered by [sort].
     *
     * @param items items to order.
     * @param sort criterion to apply.
     * @param name display name of an item.
     * @param sizeBytes file size in bytes of an item.
     * @param releaseDate release date in epoch ms of an item, null when unknown.
     * @param rating aggregated rating of an item, null when unknown.
     * @param lastPlayedAt end of the last session in epoch ms, null when never played.
     */
    fun <T> sort(
        items: List<T>,
        sort: LibrarySort,
        name: (T) -> String,
        sizeBytes: (T) -> Long,
        releaseDate: (T) -> Long?,
        rating: (T) -> Double?,
        lastPlayedAt: (T) -> Long?,
    ): List<T> = when (sort) {
        LibrarySort.NAME_ASC -> items.sortedBy { name(it).lowercase() }
        LibrarySort.NAME_DESC -> items.sortedByDescending { name(it).lowercase() }
        LibrarySort.SIZE_DESC -> items.sortedByDescending(sizeBytes)
        LibrarySort.SIZE_ASC -> items.sortedBy(sizeBytes)
        LibrarySort.YEAR_DESC -> items.sortedByDescending { releaseDate(it) ?: 0L }
        LibrarySort.YEAR_ASC -> items.sortedBy { releaseDate(it) ?: Long.MAX_VALUE }
        LibrarySort.RATING_DESC -> items.sortedByDescending { rating(it) ?: -1.0 }
        LibrarySort.RATING_ASC -> items.sortedBy { rating(it) ?: -1.0 }
        LibrarySort.LAST_PLAYED_DESC -> items.sortedByDescending { lastPlayedAt(it) ?: Long.MIN_VALUE }
    }
}
