package com.davidadrianrg.atril.ui.screens

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.davidadrianrg.atril.RomMSyncApplication
import com.davidadrianrg.atril.core.emulators.EmulatorEntry
import com.davidadrianrg.atril.core.emulators.VersionCompare
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.data.emulators.EmulatorJob
import com.davidadrianrg.atril.data.emulators.EmulatorJobState
import com.davidadrianrg.atril.ui.components.PageHeader
import com.davidadrianrg.atril.ui.components.Panel
import com.davidadrianrg.atril.ui.components.pageBackground
import com.davidadrianrg.atril.ui.input.focusRing
import com.davidadrianrg.atril.ui.viewmodel.EmulatorsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Catálogo de emuladores del pack de Obtainium + emuladores manuales.
 * Instalar/actualizar añade a una cola persistente que descarga en segundo
 * plano ([com.davidadrianrg.atril.emulators.EmulatorDownloadWorker]); al
 * volver aquí se abre el instalador de cada APK listo.
 */
@Composable
fun EmulatorsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as RomMSyncApplication
    val viewModel: EmulatorsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                EmulatorsViewModel(
                    app = app as Application,
                    saved = createSavedStateHandle(),
                    launchInstaller = app.container.apkInstallHelper::launchInstaller,
                )
            }
        },
    )
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val searchOpen by viewModel.searchOpen.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val jobsById = remember(jobs) { jobs.associateBy { it.entryId } }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    // Al volver del instalador del sistema: refleja lo instalado y abre el
    // siguiente APK listo. En pausa no se abre nada solo.
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onResume()
                Lifecycle.Event.ON_PAUSE -> viewModel.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onPause()
        }
    }

    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            kotlinx.coroutines.delay(100)
            runCatching { searchFocus.requestFocus() }
        }
    }

    Scaffold(
        topBar = {
            PageHeader(
                title = tr("nav.emulators"),
                compact = false,
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.Add, contentDescription = tr("emulators.add"))
                    }
                    IconButton(onClick = { viewModel.checkUpdates() }, enabled = !checking && entries != null) {
                        if (checking) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = tr("emulators.check_updates"))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pageBackground()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    onClick = { viewModel.reload(force = true) },
                    modifier = Modifier.focusRing(RoundedCornerShape(50)),
                ) { Text(tr("emulators.refresh")) }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { viewModel.toggleSearch() },
                    modifier = Modifier.focusRing(CircleShape),
                ) {
                    Icon(
                        if (searchOpen) Icons.Filled.Close else Icons.Outlined.Search,
                        contentDescription = tr("emulators.search"),
                    )
                }
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = filters.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .focusRequester(searchFocus),
                    placeholder = { Text(tr("emulators.search")) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // `focusRing` es `composed`: cada chip materializa su propio estado.
                val chipRing = Modifier.focusRing(RoundedCornerShape(8.dp))
                listOf(
                    "Emulator" to "emulators.filter.emulators",
                    "Utilities" to "emulators.filter.utilities",
                    "All" to "emulators.filter.all",
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = filters.category == key,
                        onClick = { viewModel.setCategory(key) },
                        label = { Text(tr(label)) },
                        modifier = chipRing,
                    )
                }
                FilterChip(
                    selected = filters.installedOnly,
                    onClick = { viewModel.toggleInstalledOnly() },
                    label = { Text(tr("emulators.filter.installed")) },
                    modifier = chipRing,
                )
                Spacer(Modifier.width(12.dp))
                FilterChip(
                    selected = !filters.dual,
                    onClick = { viewModel.setDual(false) },
                    label = { Text(tr("emulators.profile.single")) },
                    modifier = chipRing,
                )
                FilterChip(
                    selected = filters.dual,
                    onClick = { viewModel.setDual(true) },
                    label = { Text(tr("emulators.profile.dual")) },
                    modifier = chipRing,
                )
            }

            if (selected.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(onClick = { viewModel.installSelected() }) {
                        Text(tr("emulators.install_selected", selected.size))
                    }
                    TextButton(onClick = { viewModel.clearSelection() }) {
                        Text(tr("emulators.clear_selection"))
                    }
                }
            }

            QueueSummary(jobs = jobs, onCancelAll = viewModel::cancelAll)

            when {
                entries == null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                // Sin catálogo por un fallo (sin red, GitHub limitado): error
                // explicado y reintento, no un «no hay emuladores» engañoso.
                entries.isNullOrEmpty() && error != null -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        tr("emulators.error"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    val retryFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
                    Button(
                        onClick = { viewModel.reload(force = true) },
                        modifier = Modifier
                            .focusRequester(retryFocus)
                            .focusRing(RoundedCornerShape(50)),
                    ) { Text(tr("common.retry")) }
                }

                visible.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(tr("emulators.empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visible, key = { it.id }) { entry ->
                        EmulatorRow(
                            entry = entry,
                            installedVersion = installed[entry.id],
                            latestVersion = latest[entry.id],
                            job = jobsById[entry.id],
                            installedPackage = if (installed[entry.id] != null) viewModel.packageNameOf(entry) else null,
                            checked = entry.id in selected,
                            onCheckedChange = { checked -> viewModel.setSelected(entry.id, checked) },
                            onInstall = { viewModel.install(entry) },
                            onCancel = { viewModel.cancel(entry.id) },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddEmulatorDialog(
            onDismiss = { showAdd = false },
            onAdd = { url, name, regex, prereleases ->
                if (viewModel.addCustom(url, name, regex, prereleases)) showAdd = false
            },
        )
    }
}

/** Resumen de la cola de descargas (visible aunque el filtro oculte sus filas). */
@Composable
private fun QueueSummary(jobs: List<EmulatorJob>, onCancelAll: () -> Unit) {
    val pending = jobs.count { it.state == EmulatorJobState.QUEUED || it.state == EmulatorJobState.DOWNLOADING }
    val ready = jobs.count { it.state == EmulatorJobState.READY }
    if (pending == 0 && ready == 0) return
    val current = jobs.firstOrNull { it.state == EmulatorJobState.DOWNLOADING }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("emulators.queue.summary", pending, ready),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancelAll, modifier = Modifier.focusRing(RoundedCornerShape(50))) {
                Text(tr("emulators.queue.cancel_all"))
            }
        }
        if (current != null) {
            Text(
                tr("emulators.downloads.progress", current.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AddEmulatorDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, Boolean) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf("") }
    var prereleases by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("emulators.add.title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(tr("emulators.add.url")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr("emulators.add.name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = regex,
                    onValueChange = { regex = it },
                    label = { Text(tr("emulators.add.regex")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = prereleases, onCheckedChange = { prereleases = it })
                    Spacer(Modifier.width(10.dp))
                    Text(tr("emulators.add.prereleases"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(url, name, regex, prereleases) }) {
                Text(tr("emulators.add.confirm"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("common.cancel")) } },
    )
}

@Composable
private fun EmulatorRow(
    entry: EmulatorEntry,
    installedVersion: String?,
    latestVersion: String?,
    job: EmulatorJob?,
    installedPackage: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val update = VersionCompare.isNewer(latestVersion, installedVersion)
    val pending = job?.state == EmulatorJobState.QUEUED || job?.state == EmulatorJobState.DOWNLOADING
    // El anillo rodea la fila entera cuando el mando enfoca su casilla o su botón.
    Panel(modifier = Modifier.fillMaxWidth().focusRing(RoundedCornerShape(20.dp))) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onCheckedChange)
                EmulatorIcon(entry = entry, installedPackage = installedPackage)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        entry.settings.about.ifBlank { entry.repoUrl },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    val failed = job?.state == EmulatorJobState.FAILED
                    Text(
                        when {
                            failed -> tr("emulators.queue.failed", job?.error.orEmpty())
                            job?.state == EmulatorJobState.READY -> tr("emulators.queue.ready", job.version.orEmpty())
                            job?.state == EmulatorJobState.QUEUED -> tr("emulators.queue.queued")
                            job?.state == EmulatorJobState.DOWNLOADING -> tr("common.downloading")
                            installedVersion == null -> tr("emulators.not_installed")
                            update -> tr("emulators.update_available", installedVersion, latestVersion ?: "")
                            else -> tr("emulators.installed", installedVersion)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            failed -> MaterialTheme.colorScheme.error
                            update || job?.state == EmulatorJobState.READY -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (installedVersion != null && !pending) {
                        StatusChip(update = update)
                        Spacer(Modifier.height(6.dp))
                    }
                    when {
                        pending -> Row(verticalAlignment = Alignment.CenterVertically) {
                            if (job?.state == EmulatorJobState.DOWNLOADING) {
                                CircularProgressIndicator(
                                    progress = { job.progress },
                                    modifier = Modifier.size(30.dp),
                                    strokeWidth = 3.dp,
                                )
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.focusRing(CircleShape)) {
                                Icon(Icons.Filled.Close, contentDescription = tr("emulators.queue.cancel"))
                            }
                        }
                        job?.state == EmulatorJobState.READY || job?.state == EmulatorJobState.FAILED ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = onInstall) {
                                    Text(
                                        tr(
                                            if (job.state == EmulatorJobState.FAILED) "common.retry" else "emulators.install",
                                        ),
                                    )
                                }
                                IconButton(onClick = onCancel, modifier = Modifier.focusRing(CircleShape)) {
                                    Icon(Icons.Filled.Close, contentDescription = tr("emulators.queue.cancel"))
                                }
                            }
                        installedVersion == null -> Button(onClick = onInstall) { Text(tr("emulators.install")) }
                        update -> Button(onClick = onInstall) { Text(tr("emulators.update")) }
                        else -> OutlinedButton(onClick = onInstall) { Text(tr("emulators.reinstall")) }
                    }
                }
            }
            if (job?.state == EmulatorJobState.DOWNLOADING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StatusChip(update: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = tr(if (update) "emulators.chip.update" else "emulators.chip.installed"),
        style = MaterialTheme.typography.labelSmall,
        color = if (update) scheme.onPrimaryContainer else scheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (update) scheme.primaryContainer else scheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun EmulatorIcon(entry: EmulatorEntry, installedPackage: String?) {
    val context = LocalContext.current
    var icon by remember(entry.id, installedPackage) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(entry.id, installedPackage) {
        icon = withContext(Dispatchers.IO) {
            runCatching {
                if (installedPackage != null) {
                    context.packageManager.getApplicationIcon(installedPackage)
                        .toBitmap(96, 96).asImageBitmap()
                } else {
                    null
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(bitmap = icon!!, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            // Como Obtainium: sin app instalada no hay icono que mostrar.
            Icon(
                Icons.Outlined.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

