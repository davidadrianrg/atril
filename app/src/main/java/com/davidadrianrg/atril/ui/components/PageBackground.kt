package com.davidadrianrg.atril.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Fondo común de las pantallas de contenido (Ajustes, Saves, Descargas): un
 * degradado vertical suave más un halo cálido en la parte superior, para que
 * las tarjetas se separen del fondo y la pantalla no se vea vacía.
 */
@Composable
fun Modifier.pageBackground(): Modifier {
    val scheme = MaterialTheme.colorScheme
    val top = scheme.surface
    val middle = scheme.background
    val bottom = scheme.surfaceVariant
    val glow = scheme.primary
    return drawBehind {
        drawRect(Brush.verticalGradient(listOf(top, middle, bottom.copy(alpha = 0.55f))))
        drawRect(
            Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.07f), Color.Transparent),
                center = Offset(size.width * 0.5f, 0f),
                radius = size.width * 0.65f,
            ),
        )
    }
}
