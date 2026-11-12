package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.library.CoverArtwork
import com.davidadrianrg.atril.core.library.CoverStyle
import com.davidadrianrg.atril.core.library.GameMetadataLine
import com.davidadrianrg.atril.core.library.PhysicalMedia
import com.davidadrianrg.atril.core.remote.dto.RomDto

/**
 * Desktop-only mapping from [RomDto] to the pure inputs of `:core`.
 *
 * Absolute URLs are built here, outside `:core`: [resolveMediaUrl] handles the
 * RomM relative paths and [PhysicalMedia.resourceUrl] the physical media. The
 * returned URL is final, so callers must not resolve it again.
 */

/** Cover URL for [style], with the documented fallback chain. */
internal fun coverUrlFor(rep: RomDto, serverUrl: String, style: CoverStyle): String? {
    val coverUrlLarge = resolveMediaUrl(rep.urlCover, rep.pathCoverLarge, serverUrl)
    val coverUrlSmall = resolveMediaUrl(rep.urlCover, rep.pathCoverSmall, serverUrl)
    val physicalMediaUrl = PhysicalMedia.resourceUrl(serverUrl, rep.physicalMediaPath)
    val screenshots = rep.mergedScreenshots
        .map { resolveMediaUrl(null, it, serverUrl) }
        .mapNotNull { it }
    return CoverArtwork.coverUrl(
        style = style,
        coverUrlLarge = coverUrlLarge,
        coverUrlSmall = coverUrlSmall,
        physicalMediaUrl = physicalMediaUrl,
        screenshots = screenshots,
    )
}

/** Backdrop URL for the cinematic hero: first screenshot only, never the cover. */
internal fun heroBackdropUrl(rep: RomDto, serverUrl: String): String? {
    val screenshots = rep.mergedScreenshots
        .map { resolveMediaUrl(null, it, serverUrl) }
        .mapNotNull { it }
    return CoverArtwork.heroBackdropUrl(screenshots)
}

/**
 * Metadata line shown under the title, with [platformName] as the display name.
 *
 * @param serverUrl kept for call-site symmetry with the other adapter
 *   functions; the formatted values do not depend on the server.
 * @return the joined line, or null when the game has no metadata to show.
 */
internal fun metadataLine(rep: RomDto, platformName: String?, serverUrl: String): String? =
    GameMetadataLine.format(
        releaseEpochSeconds = rep.igdbMetadata?.firstReleaseDate,
        genres = rep.genres,
        platform = platformName,
        rating = rep.igdbMetadata?.totalRating?.toDoubleOrNull(),
    )
