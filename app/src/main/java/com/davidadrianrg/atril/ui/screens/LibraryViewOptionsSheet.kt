package com.davidadrianrg.atril.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.davidadrianrg.atril.core.i18n.tr
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
 * Panel contextual de opciones de la Biblioteca.
 *
 * Es una hoja modal con cuatro secciones (orden, origen, vista y tamaño) que
 * emite la elección al llamador: no guarda estado propio, así el valor pintado
 * siempre viene de las preferencias persistidas. Cambiar una opción no cierra
 * la hoja ni la pantalla.
 *
 * @param preferences preferencias actuales (dirigen la selección de cada chip).
 * @param showYearSort muestra el criterio por año solo si hay algún año cargado.
 * @param onSort nuevo criterio de orden.
 * @param onSource nuevo origen de la lista.
 * @param onViewMode nueva vista (rejilla / cover flow).
 * @param onCardSize nuevo tamaño de tarjeta.
 * @param onDismiss cierra la hoja.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryViewOptionsSheet(
    preferences: LibraryViewPreferences,
    showYearSort: Boolean,
    onSort: (LibrarySort) -> Unit,
    onSource: (LibrarySource) -> Unit,
    onViewMode: (LibraryViewMode) -> Unit,
    onCardSize: (LibraryCardSize) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = tr("library.view_options.title"),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))

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
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            content()
        }
        Spacer(Modifier.height(14.dp))
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
