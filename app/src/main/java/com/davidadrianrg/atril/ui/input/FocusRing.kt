package com.davidadrianrg.atril.ui.input

import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Anillo de foco visible para navegar con mando o teclado. Va ANTES de `clickable`
 * (el clickable es quien toma el foco y queda como hijo de este modificador).
 */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(16.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.primary
    this
        .onFocusChanged { focused = it.hasFocus }
        .then(
            if (focused) {
                Modifier
                    .shadow(16.dp, shape, ambientColor = ring, spotColor = ring)
                    .border(3.dp, ring, shape)
            } else {
                Modifier
            },
        )
}
