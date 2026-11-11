package com.davidadrianrg.atril.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale

/** Duration of the crossfade when the selected game changes. */
private const val HERO_CROSSFADE_MS = 350

/**
 * Screenshot-first cinematic backdrop behind the Play launcher and the game
 * detail.
 *
 * Paints only the scrim when [url] is null or the decode fails. Otherwise it
 * crossfades the bounded downscaled bitmap from [produceBackdrop] (longest
 * side at most 128 px) with `FilterQuality.Low` and a token-based vertical
 * scrim. It is decorative: no pointer input and no focus.
 *
 * @param url backdrop URL already resolved by [heroBackdropUrl].
 * @param modifier size and position provided by the caller.
 */
@Composable
fun CinematicBackdrop(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        if (url != null) {
            Crossfade(
                targetState = url,
                animationSpec = tween(HERO_CROSSFADE_MS),
                label = "cinematicBackdrop",
            ) { currentUrl ->
                val bitmap = produceBackdrop(currentUrl)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Low,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.background.copy(alpha = 0.42f),
                        0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                        1f to MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                    ),
                ),
        )
    }
}
