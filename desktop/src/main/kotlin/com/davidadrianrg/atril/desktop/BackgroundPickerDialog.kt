package com.davidadrianrg.atril.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.core.media.MediaCandidate
import com.davidadrianrg.atril.core.media.MediaCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Selector del fondo de un juego (como el de Android): fondos y capturas de
 * SteamGridDB/ScreenScraper con previsualización, un fichero local o quitar
 * el que haya.
 */
@Composable
internal fun BackgroundPickerDialog(
    romId: Int,
    title: String,
    platformSlug: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val current by DesktopBackgrounds.games.collectAsState()
    var candidates by remember { mutableStateOf<List<MediaCandidate>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(title, platformSlug) {
        candidates = withContext(Dispatchers.IO) {
            runCatching {
                MediaCatalog().search(title, platformSlug, DesktopConfig.scraperCredentials())
                    .filter { it.kind == "background" }
            }.getOrDefault(emptyList())
        }
    }

    fun apply(action: suspend () -> Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val ok = action()
            busy = false
            if (ok) onDismiss() else error = tr("media.background.failed")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("media.picker.title", title)) },
        text = {
            Column(Modifier.widthIn(min = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val list = candidates
                when {
                    list == null || busy -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    list.isEmpty() -> Text(
                        tr("media.picker.empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        modifier = Modifier.heightIn(max = 420.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list, key = { it.url }) { candidate ->
                            val bitmap = produceCover(candidate.url)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .focusHighlight(RoundedCornerShape(10.dp))
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { apply { DesktopBackgrounds.setFromUrl(romId, candidate.url) } },
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                bitmap?.let {
                                    Image(it, contentDescription = candidate.provider, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                                }
                                Text(
                                    candidate.provider,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        val file = pickLocalGameFile(tr("launcher.media.background")) ?: return@launch
                        apply { DesktopBackgrounds.setFromFile(romId, java.io.File(file)) }
                    }
                }) { Text(tr("media.background.from_file")) }
                if (current[romId] != null) {
                    TextButton(enabled = !busy, onClick = { DesktopBackgrounds.clear(romId); onDismiss() }) {
                        Text(tr("media.background.clear"))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("common.close")) } },
    )
}
