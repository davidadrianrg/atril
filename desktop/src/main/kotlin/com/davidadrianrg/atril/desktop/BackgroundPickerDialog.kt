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

/** Qué medio del juego elige [BackgroundPickerDialog]. */
internal enum class DesktopMediaTarget { BACKGROUND, COVER }

/**
 * Selector del fondo (o la carátula, según [target]) de un juego, como el de
 * Android: candidatos de SteamGridDB/ScreenScraper con previsualización, un
 * fichero local o quitar el que haya.
 */
@Composable
internal fun BackgroundPickerDialog(
    romId: Int,
    title: String,
    platformSlug: String,
    onDismiss: () -> Unit,
    target: DesktopMediaTarget = DesktopMediaTarget.BACKGROUND,
) {
    val scope = rememberCoroutineScope()
    val current by (if (target == DesktopMediaTarget.COVER) DesktopCovers.covers else DesktopBackgrounds.games)
        .collectAsState()
    var candidates by remember { mutableStateOf<List<MediaCandidate>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val creds = DesktopConfig.scraperCredentials()
    val hasAnyCredentials = creds.steamGridDbKey.isNotBlank() || creds.screenScraper() != null

    LaunchedEffect(title, platformSlug, hasAnyCredentials) {
        if (!hasAnyCredentials) {
            error = tr("media.picker.no_credentials")
            return@LaunchedEffect
        }
        candidates = withContext(Dispatchers.IO) {
            runCatching {
                MediaCatalog().search(title, platformSlug, creds)
                    .filter { it.kind == if (target == DesktopMediaTarget.COVER) "cover" else "background" }
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
            if (ok) {
                onDismiss()
            } else {
                error = tr(if (target == DesktopMediaTarget.COVER) "launcher.cover.failed" else "media.background.failed")
            }
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
                    !hasAnyCredentials -> Text(
                        error ?: tr("media.picker.no_credentials"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                                    .aspectRatio(if (target == DesktopMediaTarget.COVER) 0.7f else 16f / 9f)
                                    .focusHighlight(RoundedCornerShape(10.dp))
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        apply {
                                            if (target == DesktopMediaTarget.COVER) {
                                                DesktopCovers.setFromUrl(romId, candidate.url)
                                            } else {
                                                DesktopBackgrounds.setFromUrl(romId, candidate.url)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                bitmap?.let {
                                    Image(
                                        it,
                                        contentDescription = candidate.provider,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(
                                            if (target == DesktopMediaTarget.COVER) 0.7f else 16f / 9f,
                                        ),
                                    )
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
                        val dialogTitle = if (target == DesktopMediaTarget.COVER) "launcher.cover.pick" else "launcher.media.background"
                        val file = pickLocalGameFile(tr(dialogTitle)) ?: return@launch
                        apply {
                            if (target == DesktopMediaTarget.COVER) {
                                DesktopCovers.setFromFile(romId, java.io.File(file))
                            } else {
                                DesktopBackgrounds.setFromFile(romId, java.io.File(file))
                            }
                        }
                    }
                }) { Text(if (target == DesktopMediaTarget.COVER) tr("launcher.cover.pick") else tr("media.background.from_file")) }
                val currentPath = current[romId]
                if (currentPath != null) {
                    TextButton(enabled = !busy, onClick = {
                        if (target == DesktopMediaTarget.COVER) {
                            apply { DesktopCovers.clear(romId); true }
                        } else {
                            DesktopBackgrounds.clear(romId)
                            onDismiss()
                        }
                    }) { Text(if (target == DesktopMediaTarget.COVER) tr("launcher.cover.clear") else tr("media.background.clear")) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("common.close")) } },
    )
}
