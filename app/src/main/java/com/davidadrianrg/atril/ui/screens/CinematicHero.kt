package com.davidadrianrg.atril.ui.screens

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision

/** Small request size of the hero backdrop: it is never drawn at full size. */
private const val HERO_REQUEST_SIZE = 160

/** Blur radius of the hero backdrop on devices that support RenderEffect. */
private val HERO_BLUR_RADIUS = 28.dp

/** Duration of the crossfade when the selected game changes. */
private const val HERO_CROSSFADE_MS = 350

/**
 * Screenshot-first cinematic backdrop behind the Play screens.
 *
 * Renders only the scrim when [url] is null. Otherwise it requests a small
 * 160x160 bitmap with [Precision.INEXACT] (never the original), draws it
 * cropped with `FilterQuality.Low` and, on API 31+, blurs the layer. A
 * token-based vertical scrim keeps the foreground text readable.
 *
 * It is decorative and non-interactive: no pointer input, no focus, no click
 * and no semantics, so key and focus ownership of the screen is untouched.
 *
 * @param url backdrop URL already resolved by the platform adapter.
 * @param modifier size and position provided by the caller.
 */
@Composable
internal fun CinematicHero(
    url: String?,
    modifier: Modifier = Modifier,
    blurRadius: Dp = HERO_BLUR_RADIUS,
) {
    Box(modifier.fillMaxSize()) {
        if (url != null) {
            Crossfade(
                targetState = url,
                animationSpec = tween(HERO_CROSSFADE_MS),
                label = "cinematicHero",
            ) { currentUrl ->
                val context = LocalContext.current
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(currentUrl)
                        .size(HERO_REQUEST_SIZE, HERO_REQUEST_SIZE)
                        .precision(Precision.INEXACT)
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadius > 0.dp) {
                                Modifier.blur(blurRadius)
                            } else {
                                Modifier
                            },
                        )
                        .clearAndSetSemantics {},
                )
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
