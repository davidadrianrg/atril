package com.davidadrianrg.atril.ui.screens

import com.davidadrianrg.atril.core.library.CoverArtwork
import com.davidadrianrg.atril.core.library.CoverStyle
import com.davidadrianrg.atril.core.library.GameMetadataLine
import com.davidadrianrg.atril.domain.model.Rom

/**
 * App-only mapping from [Rom] to the pure inputs of `:core`.
 *
 * This file is the single place where the app resolves artwork and metadata:
 * the library, the play screen and the detail sheet all use these helpers, so
 * a new media field only needs to be wired here.
 */

/** Cover URL for [style], with the documented fallback chain. */
internal fun Rom.coverUrlFor(style: CoverStyle): String? =
    CoverArtwork.coverUrl(
        style = style,
        coverUrlLarge = coverUrlLarge,
        coverUrlSmall = coverUrlSmall,
        physicalMediaUrl = physicalMediaUrl,
        screenshots = screenshots,
    )

/** Backdrop URL for the cinematic hero: first screenshot, then the large cover. */
internal fun Rom.heroBackdropUrl(): String? =
    CoverArtwork.heroBackdropUrl(screenshots, coverUrlLarge)

/**
 * Metadata line shown under the title, with [platform] as the display name.
 *
 * @return the joined line, or null when the game has no metadata to show.
 */
internal fun Rom.metadataLine(platform: String?): String? =
    GameMetadataLine.format(
        releaseEpochSeconds = igdbMetadata?.firstReleaseDate,
        genres = genres,
        platform = platform,
        rating = igdbMetadata?.totalRating,
    )
