package com.davidadrianrg.atril.core.library

import java.text.Normalizer
import java.util.Locale

/**
 * One bucket of the alphabetical index.
 *
 * @property letter a single character `A`..`Z`, or [#OTHER] for everything
 *   that does not fold to an ASCII letter.
 * @property firstIndex index, in the exact list passed to [LetterIndex.of], of
 *   the first item of this bucket.
 * @property count number of items with this initial; the counts of every
 *   bucket sum to the size of the indexed list.
 */
data class LetterIndexEntry(val letter: String, val firstIndex: Int, val count: Int)

/**
 * Alphabetical index of a list of display names: A..Z plus a final `#` bucket.
 *
 * It is a pure value built from the list that is already filtered and sorted
 * and rendered on screen, so search, status chips and region filters are
 * respected automatically: this type never filters, fetches or sorts anything.
 *
 * The buckets are ordered `A`..`Z` and `#` last; a letter with no items is not
 * reported (the bar renders it disabled and a jump to it is a no-op).
 *
 * ### Fold policy
 *
 * The initial of a title is computed deterministically, without locale
 * surprises:
 * 1. trim the title and take its first code point only;
 * 2. decompose it with Unicode NFD and strip every combining mark, so `Á`,
 *    `É`, `Í`, `Ó`, `Ú`, `Ü` and `Ñ` fold to `A`, `E`, `I`, `O`, `U`, `U` and
 *    `N`;
 * 3. uppercase the result with [Locale.ROOT] (never the device locale, so the
 *    Turkish dotted/dotless `i` cannot change the bucket). A case fold that
 *    expands to several characters (`ß` -> `SS`) keeps its first character;
 * 4. return it when it is `A`..`Z`, otherwise [#OTHER]. Blank titles and
 *    titles starting with a digit, a symbol or a non-Latin script go to `#`.
 *
 * Leading articles are never stripped: `The Legend of Zelda` is indexed under
 * `T`, exactly as it is displayed.
 *
 * @property entries buckets in bar order (`A`..`Z`, then `#` when present).
 */
data class LetterIndex(val entries: List<LetterIndexEntry>) {

    /** Letters that have at least one item, in bar order. */
    val letters: List<String>
        get() = entries.map { it.letter }

    /**
     * First index of [letter] in the indexed list, or `null` when the bucket is
     * empty. The lookup is case-insensitive and trims surrounding spaces.
     */
    fun firstIndexFor(letter: String): Int? {
        val key = letter.trim().uppercase(Locale.ROOT)
        if (key.isEmpty()) return null
        return entries.firstOrNull { it.letter == key }?.firstIndex
    }

    companion object {
        /** Minimum number of items for the bar to be worth showing ("~40"). */
        const val VISIBILITY_THRESHOLD = 40

        /** Bucket of every title that does not fold to an ASCII letter. */
        const val OTHER = "#"

        /** The 26 letters of the bar, in order, before [#OTHER]. */
        val ALPHABET: List<String> = ('A'..'Z').map { it.toString() }

        private val COMBINING_MARKS = Regex("\\p{M}+")

        /**
         * Whether the bar should be visible for [itemCount] items: strictly
         * more than [threshold] (40 items -> false, 41 -> true).
         */
        fun isVisible(itemCount: Int, threshold: Int = VISIBILITY_THRESHOLD): Boolean =
            itemCount > threshold

        /**
         * Bucket of [title] following the fold policy documented on
         * [LetterIndex]. Never throws: any input yields a letter or [#OTHER].
         */
        fun initialOf(title: String): String {
            val trimmed = title.trim()
            if (trimmed.isEmpty()) return OTHER
            // Primer punto de código, no el primer Char: no parte emojis.
            val firstCodePoint = String(Character.toChars(trimmed.codePointAt(0)))
            val folded = Normalizer.normalize(firstCodePoint, Normalizer.Form.NFD)
                .replace(COMBINING_MARKS, "")
                .uppercase(Locale.ROOT)
            val letter = folded.firstOrNull() ?: return OTHER
            return if (letter in 'A'..'Z') letter.toString() else OTHER
        }

        /** Builds the index of [titles] in their current order. */
        fun of(titles: List<String>): LetterIndex = of(titles) { it }

        /**
         * Builds the index of [items] using [title] as the displayed name,
         * keeping the input order and recording the first index per initial.
         */
        fun <T> of(items: List<T>, title: (T) -> String): LetterIndex {
            if (items.isEmpty()) return LetterIndex(emptyList())
            val firstIndex = HashMap<String, Int>()
            val counts = HashMap<String, Int>()
            items.forEachIndexed { index, item ->
                val letter = initialOf(title(item))
                firstIndex.getOrPut(letter) { index }
                counts[letter] = (counts[letter] ?: 0) + 1
            }
            val entries = buildList {
                for (letter in ALPHABET) {
                    val count = counts[letter] ?: continue
                    add(LetterIndexEntry(letter, firstIndex.getValue(letter), count))
                }
                counts[OTHER]?.let { count ->
                    add(LetterIndexEntry(OTHER, firstIndex.getValue(OTHER), count))
                }
            }
            return LetterIndex(entries)
        }
    }
}
