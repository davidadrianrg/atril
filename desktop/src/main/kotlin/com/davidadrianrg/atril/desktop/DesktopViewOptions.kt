package com.davidadrianrg.atril.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.core.library.CoverStyle
import com.davidadrianrg.atril.core.library.LibraryCardSize
import com.davidadrianrg.atril.core.library.LibrarySort
import com.davidadrianrg.atril.core.library.LibrarySource
import com.davidadrianrg.atril.core.library.LibraryViewMode
import com.davidadrianrg.atril.core.library.LibraryViewPreferences

/** Criterios que ofrece el panel: los históricos siguen en el enum, pero no aquí. */
private val SORT_CHOICES = listOf(
    LibrarySort.NAME_ASC,
    LibrarySort.LAST_PLAYED_DESC,
    LibrarySort.YEAR_DESC,
)

/**
 * Panel flotante de opciones de la Biblioteca, anclado al botón de ajustes.
 *
 * Contiene las mismas cinco secciones y las mismas cadenas que la hoja de
 * Android y, igual que ella, no guarda estado: cada chip refleja la preferencia
 * persistida y emite el cambio al llamador. Es modal (se cierra al pulsar
 * fuera) y consume Escape para cerrarse antes que el detalle de juego.
 *
 * @param preferences preferencias actuales (dirigen la selección de cada chip).
 * @param showYearSort muestra el criterio por año solo si hay algún año cargado.
 * @param onSort nuevo criterio de orden.
 * @param onSource nuevo origen de la lista.
 * @param onViewMode nueva vista (rejilla / cover flow).
 * @param onCardSize nuevo tamaño de tarjeta.
 * @param onCoverStyle nuevo estilo de carátula.
 * @param onDismiss cierra el panel.
 */
@Composable
fun DesktopViewOptions(
    preferences: LibraryViewPreferences,
    showYearSort: Boolean,
    onSort: (LibrarySort) -> Unit,
    onSource: (LibrarySource) -> Unit,
    onViewMode: (LibraryViewMode) -> Unit,
    onCardSize: (LibraryCardSize) -> Unit,
    onCoverStyle: (CoverStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    // Desplazamiento en píxeles: el panel cuelga justo debajo del botón que lo abre.
    val offsetY = with(LocalDensity.current) { 52.dp.roundToPx() }
    // El panel toma el foco al abrirse para que Escape llegue aquí (y no al
    // detalle que hay detrás) aunque aún no se haya pulsado ningún chip.
    val panelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { panelFocus.requestFocus() } }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .width(370.dp)
                .focusRequester(panelFocus)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tr("library.view_options.title"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(AppIcons.Close, contentDescription = tr("common.close"))
                    }
                }
                Spacer(Modifier.height(6.dp))

                ViewOptionsRow(label = tr("library.view_options.sort")) {
                    val choices = if (showYearSort) SORT_CHOICES else SORT_CHOICES - LibrarySort.YEAR_DESC
                    choices.forEach { sort ->
                        OptionChip(
                            text = tr(sort.labelKey),
                            selected = preferences.sort == sort,
                            onClick = { onSort(sort) },
                        )
                    }
                }

                ViewOptionsRow(label = tr("library.view_options.source")) {
                    LibrarySource.entries.forEach { source ->
                        OptionChip(
                            text = tr(source.labelKey),
                            selected = preferences.source == source,
                            onClick = { onSource(source) },
                        )
                    }
                }

                ViewOptionsRow(label = tr("library.view_options.view")) {
                    LibraryViewMode.entries.forEach { mode ->
                        OptionChip(
                            text = tr(mode.labelKey),
                            selected = preferences.viewMode == mode,
                            onClick = { onViewMode(mode) },
                        )
                    }
                }

                ViewOptionsRow(label = tr("library.view_options.size")) {
                    LibraryCardSize.entries.forEach { size ->
                        OptionChip(
                            text = tr(size.labelKey),
                            selected = preferences.cardSize == size,
                            onClick = { onCardSize(size) },
                        )
                    }
                }

                ViewOptionsRow(label = tr("library.view_options.cover_style")) {
                    CoverStyle.entries.forEach { style ->
                        OptionChip(
                            text = tr(style.labelKey),
                            selected = preferences.coverStyle == style,
                            onClick = { onCoverStyle(style) },
                        )
                    }
                }
            }
        }
    }
}

/** Sección del panel: título y una fila de chips desplazable si no cabe. */
@Composable
private fun ViewOptionsRow(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            content()
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Chip de opción con el mismo estilo que los filtros de la Biblioteca. */
@Composable
private fun OptionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}
