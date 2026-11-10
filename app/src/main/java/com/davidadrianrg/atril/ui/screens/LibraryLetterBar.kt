package com.davidadrianrg.atril.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.core.library.LetterIndex
import kotlinx.coroutines.delay

/**
 * Espacio reservado en el borde final de la lista (24 dp) para que la barra
 * alfabética nunca tape una tarjeta de forma permanente (D8/AC7).
 */
val LETTER_BAR_TRAILING_INSET: Dp = 24.dp

/** Tiempo sin interacción tras el cual la barra se atenúa (D8). */
private const val IDLE_FADE_MS = 3_000L

/** Opacidad de la barra cuando está inactiva; nunca desaparece del todo. */
private const val IDLE_ALPHA = 0.35f

/**
 * Barra alfabética A-Z + `#` superpuesta en el borde final de la Biblioteca.
 *
 * Se pinta como una columna estrecha con un único nodo enfocable: la entrada y
 * la salida del foco son explícitas (Compose no puede alcanzar una superposición
 * del borde derecho por búsqueda de foco), y el resto de letras responden a
 * toques sin convertirse en destinos de foco, así que nunca atrapan el mando.
 *
 * D8: la barra se atenúa a [IDLE_ALPHA] tras [IDLE_FADE_MS] sin interacción y
 * vuelve a opacidad plena con el foco, una tecla o un toque; el espacio que
 * ocupa está reservado por [LETTER_BAR_TRAILING_INSET], de modo que ninguna
 * tarjeta queda oculta de forma permanente.
 *
 * @param index índice construido sobre la MISMA lista filtrada que se pinta.
 * @param currentLetter letra del elemento visible/centrado, para resaltarla.
 * @param focusRequester foco de la barra, pedido explícitamente por la pantalla.
 * @param onJump salta al primer índice de la letra (nunca se llama con una
 *   letra sin elementos).
 * @param onExit devuelve el foco a la lista (Arriba).
 */
@Composable
fun LibraryLetterBar(
    index: LetterIndex,
    currentLetter: String?,
    focusRequester: FocusRequester,
    onJump: (String) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allLetters = remember { LetterIndex.ALPHABET + LetterIndex.OTHER }
    var focused by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableStateOf(0L) }
    var idle by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(initialSelection(allLetters, index, currentLetter)) }

    // El resaltado sigue a la lista mientras la barra no tiene el foco; con el
    // foco dentro manda la selección del usuario.
    LaunchedEffect(currentLetter, focused) {
        if (!focused) selected = initialSelection(allLetters, index, currentLetter)
    }

    // D8: atenuar tras 3 s sin interacción; con foco nunca se atenúa.
    LaunchedEffect(focused, lastInteraction) {
        idle = false
        if (!focused) {
            delay(IDLE_FADE_MS)
            idle = true
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (focused) 1f else if (idle) IDLE_ALPHA else 1f,
        label = "letterBarAlpha",
    )

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        // La columna se adapta a la altura disponible: en ventanas compactas
        // reduce fila y tipografía en vez de desbordar la pantalla.
        val itemHeight = (maxHeight * 0.94f / allLetters.size).coerceIn(9.dp, 18.dp)
        val fontSize = (itemHeight.value * 0.72f).coerceIn(7f, 11f).sp

        Column(
            modifier = Modifier
                // Mismo ancho que el hueco reservado (24 dp): nunca tapa una tarjeta.
                .width(LETTER_BAR_TRAILING_INSET)
                .alpha(alpha)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(13.dp),
                )
                .focusRequester(focusRequester)
                .focusable()
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    lastInteraction = System.currentTimeMillis()
                    when (ev.key) {
                        Key.DirectionLeft -> {
                            selected = moveSelection(allLetters, index, selected, -1)
                            true
                        }
                        Key.DirectionRight -> {
                            selected = moveSelection(allLetters, index, selected, +1)
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            val letter = allLetters[selected]
                            if (index.firstIndexFor(letter) != null) onJump(letter)
                            true
                        }
                        Key.DirectionUp -> {
                            onExit()
                            true
                        }
                        else -> false
                    }
                }
                .semantics { contentDescription = tr("library.letter_bar.hint") },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            allLetters.forEachIndexed { position, letter ->
                val enabled = index.firstIndexFor(letter) != null
                LetterCell(
                    letter = letter,
                    enabled = enabled,
                    highlighted = position == selected,
                    height = itemHeight,
                    fontSize = fontSize,
                    onClick = {
                        if (enabled) {
                            lastInteraction = System.currentTimeMillis()
                            selected = position
                            onJump(letter)
                        }
                    },
                )
            }
        }
    }
}

/** Una letra de la barra. El toque no crea destino de foco (sin trampas). */
@Composable
private fun LetterCell(
    letter: String,
    enabled: Boolean,
    highlighted: Boolean,
    height: Dp,
    fontSize: TextUnit,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(letter, enabled) {
                detectTapGestures { if (enabled) onClick() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            fontSize = fontSize,
            lineHeight = fontSize,
            maxLines = 1,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            color = when {
                highlighted -> MaterialTheme.colorScheme.primary
                enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            },
        )
    }
}

/** Posición inicial: la letra visible si tiene elementos; si no, la primera. */
private fun initialSelection(
    allLetters: List<String>,
    index: LetterIndex,
    currentLetter: String?,
): Int {
    val current = currentLetter?.let { allLetters.indexOf(it) } ?: -1
    if (current >= 0 && index.firstIndexFor(allLetters[current]) != null) return current
    val first = allLetters.indexOfFirst { index.firstIndexFor(it) != null }
    return if (first >= 0) first else 0
}

/**
 * Siguiente posición con elementos en la dirección [step], de forma circular.
 * Las letras vacías se saltan para que el mando no se detenga en ellas.
 */
private fun moveSelection(
    allLetters: List<String>,
    index: LetterIndex,
    from: Int,
    step: Int,
): Int {
    var position = from
    repeat(allLetters.size) {
        position = (position + step + allLetters.size) % allLetters.size
        if (index.firstIndexFor(allLetters[position]) != null) return position
    }
    return from
}
