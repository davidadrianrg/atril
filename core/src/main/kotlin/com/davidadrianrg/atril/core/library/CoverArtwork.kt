package com.davidadrianrg.atril.core.library

/**
 * Pure selection of the artwork a game should show for a [CoverStyle].
 *
 * Both functions are total: they never throw and treat a null, blank or
 * whitespace-only value as absent, returning a trimmed URL or null.
 */
object CoverArtwork {

    /**
     * Cover URL for [style].
     *
     * - [CoverStyle.CASE] uses [coverUrlLarge] and falls back to
     *   [coverUrlSmall].
     * - [CoverStyle.PHYSICAL] uses [physicalMediaUrl] and falls back to the
     *   [CoverStyle.CASE] chain.
     * - [CoverStyle.SCREENSHOT] uses the first non-blank screenshot in list
     *   order and falls back to the [CoverStyle.CASE] chain.
     *
     * @return the trimmed URL, or null when every candidate is absent.
     */
    fun coverUrl(
        style: CoverStyle,
        coverUrlLarge: String?,
        coverUrlSmall: String?,
        physicalMediaUrl: String?,
        screenshots: List<String>,
    ): String? = when (style) {
        CoverStyle.CASE -> caseChain(coverUrlLarge, coverUrlSmall)
        CoverStyle.PHYSICAL -> nonBlank(physicalMediaUrl) ?: caseChain(coverUrlLarge, coverUrlSmall)
        CoverStyle.SCREENSHOT -> firstNonBlank(screenshots) ?: caseChain(coverUrlLarge, coverUrlSmall)
    }

    /**
     * Backdrop URL for the cinematic hero.
     *
     * Strictly the first non-blank screenshot, then [coverUrlLarge], then null.
     * [coverUrlSmall] is never a hero fallback: it is too small to upscale.
     *
     * @return the trimmed URL, or null when both are absent.
     */
    fun heroBackdropUrl(screenshots: List<String>, coverUrlLarge: String?): String? =
        firstNonBlank(screenshots) ?: nonBlank(coverUrlLarge)

    /** Large cover first, then the small one; absent when both are blank. */
    private fun caseChain(coverUrlLarge: String?, coverUrlSmall: String?): String? =
        nonBlank(coverUrlLarge) ?: nonBlank(coverUrlSmall)

    /** The trimmed value, or null when it is null or whitespace-only. */
    private fun nonBlank(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** First non-blank, trimmed screenshot in list order. */
    private fun firstNonBlank(screenshots: List<String>): String? =
        screenshots.firstNotNullOfOrNull { nonBlank(it) }
}
