package com.davidadrianrg.atril.desktop.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Glass surface for desktop detail panels: same tokens as Android.
 *
 * Fills with the theme surface at 78 % and draws a 1 dp border of the theme
 * outline at 10 %, with 22 dp rounded corners. Every value comes from
 * [MaterialTheme]; no literal white is used.
 */
@Composable
fun Modifier.glassPanel(
    shape: Shape = RoundedCornerShape(22.dp),
    fillAlpha: Float = 0.78f,
    borderAlpha: Float = 0.10f,
): Modifier = this
    .clip(shape)
    .background(MaterialTheme.colorScheme.surface.copy(alpha = fillAlpha))
    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = borderAlpha), shape)
