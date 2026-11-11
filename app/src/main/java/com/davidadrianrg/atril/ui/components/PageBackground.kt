package com.davidadrianrg.atril.ui.components

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

/**
 * Fondo común de las pantallas de contenido (Ajustes, Saves, Descargas): un
 * degradado vertical suave en vez del color plano, para que las tarjetas se
 * separen del fondo y la pantalla no se vea vacía.
 */
@Composable
fun Modifier.pageBackground(): Modifier {
    val scheme = MaterialTheme.colorScheme
    return background(
        Brush.verticalGradient(
            listOf(
                scheme.surface,
                scheme.background,
                scheme.surfaceVariant.copy(alpha = 0.55f),
            ),
        ),
    )
}
