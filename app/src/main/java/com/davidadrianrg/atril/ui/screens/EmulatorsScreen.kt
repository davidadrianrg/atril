package com.davidadrianrg.atril.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.davidadrianrg.atril.RomMSyncApplication
import com.davidadrianrg.atril.core.emulators.EmulatorEntry
import com.davidadrianrg.atril.core.emulators.PackParser
import com.davidadrianrg.atril.core.emulators.VersionCompare
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.data.emulators.EmulatorRepository
import com.davidadrianrg.atril.data.update.InstallLaunchResult
import com.davidadrianrg.atril.ui.components.Panel
import com.davidadrianrg.atril.ui.components.PageHeader
import com.davidadrianrg.atril.ui.components.pageBackground
import kotlinx.coroutines.launch

/**
 * Catálogo de emuladores del pack de Obtainium: importa el JSON del repositorio,
 * elige variante single/dual y consulta las releases de GitHub bajo demanda.
 */
@Composable
fun EmulatorsScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as RomMSyncApplication).container
    val repository = remember { EmulatorRepository(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var entries by remember { mutableStateOf<List<EmulatorEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dual by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("Emulator") }
    var query by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var latest by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var checking by remember { mutableStateOf(false) }

    fun refreshInstalled(list: List<EmulatorEntry>) {
        installed = list.associate { it.id to repository.installedVersion(it.id) }
    }

    LaunchedEffect(Unit) {
        entries = runCatching { repository.loadPack() }
            .onFailure { error = it.message }
            .getOrDefault(emptyList())
            .also { refreshInstalled(it) }
    }

    val visible = remember(entries, dual, category, query) {
        val profile = PackParser.forProfile(entries.orEmpty(), dual)
        profile
            .filter { category == "All" || it.categories.contains(category) }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }

    fun install(entry: EmulatorEntry) {
        if (busyId != null) return
        busyId = entry.id
        progress = 0f
        scope.launch {
            try {
                val release = repository.latestRelease(entry, dualScreen = dual)
                if (release == null) {
                    snackbar.showSnackbar(tr("emulators.no_release", entry.name))
                    return@launch
                }
                latest = latest + (entry.id to release.version)
                val file = repository.download(release) { progress = it }
                when (val result = container.apkInstallHelper.launchInstaller(file)) {
                    InstallLaunchResult.Started ->
                        snackbar.showSnackbar(tr("emulators.installer_opened", entry.name, release.version))
                    InstallLaunchResult.NeedsPermission ->
                        snackbar.showSnackbar(tr("emulators.needs_install_permission"))
                    is InstallLaunchResult.Error ->
                        snackbar.showSnackbar(result.message)
                }
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: tr("emulators.error"))
            } finally {
                busyId = null
                refreshInstalled(entries.orEmpty())
            }
        }
    }

    fun checkUpdates() {
        val list = entries.orEmpty()
        checking = true
        scope.launch {
            try {
                for (entry in list) {
                    val version = repository.installedVersion(entry.id) ?: continue
                    val release = repository.latestRelease(entry, dualScreen = dual) ?: continue
                    if (VersionCompare.isNewer(release.version, version)) {
                        latest = latest + (entry.id to release.version)
                    }
                }
                snackbar.showSnackbar(tr("emulators.updates_checked"))
            } finally {
                checking = false
            }
        }
    }

    Scaffold(
        topBar = {
            PageHeader(
                title = tr("nav.emulators"),
                compact = false,
                actions = {
                    IconButton(onClick = { checkUpdates() }, enabled = !checking && entries != null) {
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
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(tr("emulators.search")) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                TextButton(onClick = {
                    scope.launch {
                        entries = runCatching { repository.loadPack(force = true) }
                            .onFailure { error = it.message }
                            .getOrDefault(emptyList())
                            .also { refreshInstalled(it) }
                    }
                }) { Text(tr("emulators.refresh")) }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Emulator" to "emulators.filter.emulators", "Utilities" to "emulators.filter.utilities", "All" to "emulators.filter.all")
                    .forEach { (key, label) ->
                        FilterChip(
                            selected = category == key,
                            onClick = { category = key },
                            label = { Text(tr(label)) },
                        )
                    }
                Spacer(Modifier.width(12.dp))
                FilterChip(
                    selected = !dual,
                    onClick = { dual = false },
                    label = { Text(tr("emulators.profile.single")) },
                )
                FilterChip(
                    selected = dual,
                    onClick = { dual = true },
                    label = { Text(tr("emulators.profile.dual")) },
                )
            }

            when {
                entries == null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                visible.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(tr("emulators.empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
                            busy = busyId == entry.id,
                            progress = progress,
                            onInstall = { install(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmulatorRow(
    entry: EmulatorEntry,
    installedVersion: String?,
    latestVersion: String?,
    busy: Boolean,
    progress: Float,
    onInstall: () -> Unit,
) {
    val update = VersionCompare.isNewer(latestVersion, installedVersion)
    Panel(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        entry.settings.about.ifBlank { entry.repoUrl },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            installedVersion == null -> tr("emulators.not_installed")
                            update -> tr("emulators.update_available", installedVersion, latestVersion ?: "")
                            else -> tr("emulators.installed", installedVersion)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (update) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                when {
                    busy -> CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 3.dp,
                    )
                    installedVersion == null -> Button(onClick = onInstall) {
                        Text(tr("emulators.install"))
                    }
                    update -> Button(onClick = onInstall) { Text(tr("emulators.update")) }
                    else -> OutlinedButton(onClick = onInstall) { Text(tr("emulators.reinstall")) }
                }
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
