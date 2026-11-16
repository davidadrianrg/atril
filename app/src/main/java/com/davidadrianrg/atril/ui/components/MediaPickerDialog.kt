package com.davidadrianrg.atril.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.ui.input.focusRing
import com.davidadrianrg.atril.core.localgames.ScraperCredentials
import com.davidadrianrg.atril.data.media.MediaCandidate
import com.davidadrianrg.atril.data.media.MediaCatalog

/**
 * Selector con previsualización: busca en ScreenScraper y SteamGridDB y deja
 * elegir la imagen que más guste (carátula o fondo).
 */
@Composable
fun MediaPickerDialog(
    title: String,
    platformSlug: String,
    creds: ScraperCredentials,
    kinds: Set<String>,
    onSelect: (MediaCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    var candidates by remember { mutableStateOf<List<MediaCandidate>?>(null) }
    var kindFilter by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(title, platformSlug) {
        candidates = com.davidadrianrg.atril.data.media.MediaCatalog()
            .search(title, platformSlug, creds)
            .filter { it.kind in kinds }
            .also { if (it.isEmpty()) error = tr("media.picker.empty") }
    }

    val shown = remember(candidates, kindFilter) {
        candidates.orEmpty().filter { kindFilter == null || it.kind == kindFilter }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("media.picker.title", title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (kinds.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = kindFilter == null,
                            onClick = { kindFilter = null },
                            label = { Text(tr("media.picker.all")) },
                        )
                        if ("cover" in kinds) {
                            FilterChip(
                                selected = kindFilter == "cover",
                                onClick = { kindFilter = "cover" },
                                label = { Text(tr("media.picker.covers")) },
                            )
                        }
                        if ("background" in kinds) {
                            FilterChip(
                                selected = kindFilter == "background",
                                onClick = { kindFilter = "background" },
                                label = { Text(tr("media.picker.backgrounds")) },
                            )
                        }
                    }
                }
                when {
                    candidates == null -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                    shown.isEmpty() -> Text(
                        error ?: tr("media.picker.empty"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(shown, key = { it.url }) { candidate ->
                            Column(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .focusRing(RoundedCornerShape(10.dp))
                                    .clickable { onSelect(candidate) },
                            ) {
                                AsyncImage(
                                    model = candidate.url,
                                    contentDescription = candidate.provider,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(if (candidate.kind == "cover") 0.7f else 1.6f)
                                        .clip(RoundedCornerShape(10.dp)),
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    candidate.provider,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("common.close")) } },
    )
}
