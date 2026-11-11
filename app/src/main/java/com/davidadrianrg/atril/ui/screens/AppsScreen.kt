package com.davidadrianrg.atril.ui.screens

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.davidadrianrg.atril.RomMSyncApplication
import com.davidadrianrg.atril.apps.InstalledApp
import com.davidadrianrg.atril.apps.InstalledAppsRepository
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.core.launch.LaunchTargetResolver
import com.davidadrianrg.atril.ui.components.pageBackground
import com.davidadrianrg.atril.ui.components.PageHeader
import com.davidadrianrg.atril.ui.components.Panel
import kotlinx.coroutines.launch

/**
 * Apps Android instaladas: cualquier app con actividad de lanzamiento, con
 * filtro Juegos/Todas. Tocar abre la app; mantener pulsado la añade a la
 * biblioteca como juego local (`package:<name>`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(compact: Boolean = false) {
    val context = LocalContext.current
    val container = (context.applicationContext as RomMSyncApplication).container
    val repository = remember { InstalledAppsRepository(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var gamesOnly by remember { mutableStateOf(false) }
    var pendingAdd by remember { mutableStateOf<InstalledApp?>(null) }

    LaunchedEffect(Unit) { apps = repository.list() }

    val filtered = remember(apps, query, gamesOnly) {
        apps.orEmpty()
            .filter { !gamesOnly || it.isGame }
            .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = { PageHeader(title = tr("nav.apps"), compact = compact) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().pageBackground().padding(padding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(tr("apps.search")) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(50),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    FilterChip(
                        selected = !gamesOnly,
                        onClick = { gamesOnly = false },
                        label = { Text(tr("apps.filter.all")) },
                    )
                    FilterChip(
                        selected = gamesOnly,
                        onClick = { gamesOnly = true },
                        label = { Text(tr("apps.filter.games")) },
                    )
                }

                when {
                    apps == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(tr("apps.loading"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(tr("apps.empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = if (compact) 104.dp else 120.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(filtered, key = { it.packageName }) { app ->
                            AppTile(
                                app = app,
                                onClick = {
                                    val intent = context.packageManager
                                        .getLaunchIntentForPackage(app.packageName)
                                    if (intent != null) {
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        runCatching { context.startActivity(intent) }
                                            .onFailure {
                                                scope.launch { snackbar.showSnackbar(tr("apps.launch_failed")) }
                                            }
                                    } else {
                                        scope.launch { snackbar.showSnackbar(tr("apps.launch_failed")) }
                                    }
                                },
                                onLongClick = { pendingAdd = app },
                            )
                        }
                    }
                }
            }
    }

    pendingAdd?.let { app ->
        AlertDialog(
            onDismissRequest = { pendingAdd = null },
            title = { Text(app.label) },
            text = { Text(tr("apps.add_prompt")) },
            confirmButton = {
                TextButton(onClick = {
                    pendingAdd = null
                    scope.launch {
                        container.localGamesRepository.add(
                            title = app.label,
                            platformSlug = "android",
                            path = LaunchTargetResolver.PACKAGE_PREFIX + app.packageName,
                        )
                        snackbar.showSnackbar(tr("apps.added"))
                    }
                }) { Text(tr("apps.add")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAdd = null }) { Text(tr("common.cancel")) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: InstalledApp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Panel(modifier = Modifier.size(64.dp), padding = 8) {
            if (app.icon != null) {
                Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { Text(app.label.take(1).uppercase(), fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
