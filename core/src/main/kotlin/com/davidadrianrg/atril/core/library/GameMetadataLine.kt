package com.davidadrianrg.atril.core.library

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * Pure formatter of the metadata line shown under a game title.
 *
 * The line joins year, genres, platform and rating with [SEPARATOR], in that
 * order, skipping any absent part. It is deterministic: no clock, locale,
 * network or context is read.
 */
object GameMetadataLine {

    /** Separator between parts: space, middle dot (U+00B7), space. */
    const val SEPARATOR: String = " \u00B7 "

    /** Maximum number of genres rendered on the line. */
    const val MAX_GENRES: Int = 3

    /** Oldest accepted release date: 1970-01-01T00:00:00Z. */
    private const val MIN_RELEASE_EPOCH_SECONDS = 1L

    /** Newest accepted release date: 9999-12-31T23:59:59Z. */
    private const val MAX_RELEASE_EPOCH_SECONDS = 253402300799L

    /**
     * Builds the metadata line from plain inputs.
     *
     * @param releaseEpochSeconds release date in epoch seconds, interpreted as
     *   UTC; null, zero and out-of-range values omit the year.
     * @param genres genre names in display order; each is trimmed, blanks are
     *   dropped, duplicates are removed and at most [MAX_GENRES] are kept.
     * @param platform platform name; trimmed, omitted when blank.
     * @param rating IGDB rating on the 0-100 scale; null, zero, negative and
     *   non-finite values omit it, otherwise it is rounded to an integer.
     * @return the joined line, or null when every part is absent.
     */
    fun format(
        releaseEpochSeconds: Long?,
        genres: List<String>,
        platform: String?,
        rating: Double?,
    ): String? {
        val parts = buildList {
            releaseYear(releaseEpochSeconds)?.let { add(it.toString()) }
            addAll(cleanGenres(genres))
            platform?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            cleanRating(rating)?.let { add(it.toString()) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    /** UTC year of a plausible epoch, or null when the value is out of range. */
    private fun releaseYear(epochSeconds: Long?): Int? {
        val epoch = epochSeconds ?: return null
        if (epoch < MIN_RELEASE_EPOCH_SECONDS || epoch > MAX_RELEASE_EPOCH_SECONDS) return null
        return Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).year
    }

    /** Trimmed, non-blank, de-duplicated genres limited to [MAX_GENRES]. */
    private fun cleanGenres(genres: List<String>): List<String> =
        genres.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_GENRES)
            .toList()

    /** Rounded 0-100 rating, or null when absent or invalid. Half-up: 84.5 -> 85, 0.5 -> 1. */
    private fun cleanRating(rating: Double?): Int? {
        val value = rating ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        return value.roundToInt()
    }
}
