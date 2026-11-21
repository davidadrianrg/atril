package com.davidadrianrg.atril.ui.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.davidadrianrg.atril.core.emulators.EmulatorEntry
import com.davidadrianrg.atril.core.emulators.PackParser
import com.davidadrianrg.atril.core.emulators.VersionCompare
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.data.emulators.EmulatorInstallFlow
import com.davidadrianrg.atril.data.emulators.EmulatorInstallQueue
import com.davidadrianrg.atril.data.emulators.EmulatorJob
import com.davidadrianrg.atril.data.emulators.EmulatorJobState
import com.davidadrianrg.atril.data.emulators.EmulatorRepository
import com.davidadrianrg.atril.data.emulators.EmulatorUpdatesStore
import com.davidadrianrg.atril.data.emulators.GitHubRateLimitException
import com.davidadrianrg.atril.data.update.InstallLaunchResult
import com.davidadrianrg.atril.emulators.EmulatorDownloadWorker
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Filtros de la pantalla; sobreviven a rotación y muerte del proceso. */
data class EmulatorFilters(
    val dual: Boolean = false,
    val category: String = "Emulator",
    val query: String = "",
    val installedOnly: Boolean = false,
)

/**
 * Estado de la pantalla de Emuladores. Las descargas viven en
 * [EmulatorInstallQueue] + [EmulatorDownloadWorker], así que siguen al salir
 * de la pantalla; aquí solo se observa la cola y se abre el instalador del
 * sistema cuando hay un APK listo y la pantalla está en primer plano.
 */
class EmulatorsViewModel(
    private val app: Application,
    private val saved: SavedStateHandle,
    private val launchInstaller: (File) -> InstallLaunchResult,
    private val repository: EmulatorRepository = EmulatorRepository(app),
    private val queue: EmulatorInstallQueue = EmulatorInstallQueue.get(app),
) : ViewModel() {

    private val _entries = MutableStateFlow<List<EmulatorEntry>?>(null)
    val entries: StateFlow<List<EmulatorEntry>?> = _entries.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _installed = MutableStateFlow<Map<String, String?>>(emptyMap())
    val installed: StateFlow<Map<String, String?>> = _installed.asStateFlow()

    private val _latest = MutableStateFlow<Map<String, String>>(emptyMap())
    val latest: StateFlow<Map<String, String>> = _latest.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    val filters: StateFlow<EmulatorFilters> = combine(
        saved.getStateFlow(KEY_DUAL, false),
        saved.getStateFlow(KEY_CATEGORY, "Emulator"),
        saved.getStateFlow(KEY_QUERY, ""),
        saved.getStateFlow(KEY_INSTALLED_ONLY, false),
    ) { dual, category, query, installedOnly -> EmulatorFilters(dual, category, query, installedOnly) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, currentFilters())

    val searchOpen: StateFlow<Boolean> = saved.getStateFlow(KEY_SEARCH_OPEN, false)
    val selected: StateFlow<Set<String>> = saved.getStateFlow(KEY_SELECTED, ArrayList<String>())
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val jobs: StateFlow<List<EmulatorJob>> = queue.jobs

    val visible: StateFlow<List<EmulatorEntry>> = combine(_entries, filters, _installed) { list, f, inst ->
        PackParser.forProfile(list.orEmpty(), f.dual)
            .filter { f.category == "All" || it.categories.contains(f.category) }
            .filter { f.query.isBlank() || it.name.contains(f.query, ignoreCase = true) }
            .filter { !f.installedOnly || inst[it.id] != null }
            .distinctBy { it.id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Pantalla en primer plano: solo entonces se abre el instalador solo. */
    private var resumed = false
    /** Trabajo cuyo instalador está abierto y versión instalada antes de abrirlo. */
    private var awaiting: Pair<String, String?>? = null
    private val attempted = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            _latest.value = withContext(Dispatchers.IO) {
                EmulatorUpdatesStore(File(app.filesDir, "emulator_updates.json")).read()
            }
            reload(force = false)
        }
        viewModelScope.launch {
            queue.jobs.collect { list ->
                // Una versión recién descargada también es «la última» conocida.
                val ready = list.filter { it.state == EmulatorJobState.READY && it.version != null }
                if (ready.isNotEmpty()) _latest.value = _latest.value + ready.associate { it.entryId to it.version!! }
                maybeAutoInstall()
            }
        }
        // Tras la muerte del proceso, WorkManager reanuda el trabajo; esto
        // cubre el caso de una cola persistida sin trabajo programado.
        if (queue.hasQueued()) EmulatorDownloadWorker.enqueue(app)
    }

    private fun currentFilters() = EmulatorFilters(
        saved[KEY_DUAL] ?: false,
        saved[KEY_CATEGORY] ?: "Emulator",
        saved[KEY_QUERY] ?: "",
        saved[KEY_INSTALLED_ONLY] ?: false,
    )

    // ── Filtros ─────────────────────────────────────────────────────────

    fun setDual(value: Boolean) { saved[KEY_DUAL] = value }
    fun setCategory(value: String) { saved[KEY_CATEGORY] = value }
    fun setQuery(value: String) { saved[KEY_QUERY] = value }
    fun toggleInstalledOnly() { saved[KEY_INSTALLED_ONLY] = !(saved[KEY_INSTALLED_ONLY] ?: false) }
    fun toggleSearch() { saved[KEY_SEARCH_OPEN] = !(saved[KEY_SEARCH_OPEN] ?: false) }

    fun setSelected(id: String, checked: Boolean) {
        val current = selected.value
        saved[KEY_SELECTED] = ArrayList(if (checked) current + id else current - id)
    }

    fun clearSelection() { saved[KEY_SELECTED] = ArrayList<String>() }

    // ── Catálogo ────────────────────────────────────────────────────────

    /**
     * Carga (o recarga) el catálogo. Un fallo no sustituye la lista que se
     * tenía por una vacía, y un éxito limpia el error anterior.
     */
    fun reload(force: Boolean) {
        viewModelScope.launch {
            runCatching { repository.loadPack(force = force) }
                .onSuccess { pack ->
                    _error.value = null
                    _entries.value = pack
                    if (force) clearSelection()
                    refreshInstalled()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _error.value = e.message?.takeIf { it.isNotBlank() } ?: tr("emulators.error")
                    if (_entries.value == null) _entries.value = emptyList()
                    if (!_entries.value.isNullOrEmpty()) _messages.trySend(tr("emulators.error"))
                }
        }
    }

    // PackageManager fuera del hilo principal: con decenas de entradas se nota.
    private suspend fun refreshInstalled() {
        val list = _entries.value.orEmpty()
        _installed.value = withContext(Dispatchers.IO) { list.associate { it.id to repository.installedVersion(it) } }
    }

    fun packageNameOf(entry: EmulatorEntry): String = repository.packageNameOf(entry)

    /** Valida y añade un emulador manual; true si se añadió (cierra el diálogo). */
    fun addCustom(url: String, name: String, regex: String, prereleases: Boolean): Boolean {
        val valid = url.contains("github.com/") && url.substringAfter("github.com/").contains('/')
        if (!valid) {
            _messages.trySend(tr("emulators.invalid_url"))
            return false
        }
        val added = repository.addCustom(url, name, regex, prereleases)
        _entries.value = (_entries.value.orEmpty() + added).distinctBy { it.id }
        viewModelScope.launch { refreshInstalled() }
        _messages.trySend(tr("emulators.added", added.name))
        return true
    }

    // ── Cola de descargas ───────────────────────────────────────────────

    fun install(entry: EmulatorEntry) {
        val job = queue.get(entry.id)
        if (job?.state == EmulatorJobState.READY) {
            installReady(entry.id)
        } else {
            // Como Obtainium: si la release tiene varios APKs, se pregunta.
            installAsking(listOf(entry))
        }
    }

    fun installSelected() {
        val ids = selected.value
        val chosen = visible.value.filter { it.id in ids }
        clearSelection()
        installAsking(chosen)
    }

    /** Resuelve cada entrada y aparta las que tienen varios APKs para preguntar. */
    private fun installAsking(entries: List<EmulatorEntry>) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            val dual = filters.value.dual
            val automatic = mutableListOf<EmulatorEntry>()
            val ambiguous = mutableListOf<Pair<EmulatorEntry, EmulatorRepository.ReleaseOptions>>()
            for (entry in entries) {
                val job = queue.get(entry.id)
                if (job?.state == EmulatorJobState.READY) {
                    installReady(entry.id)
                    continue
                }
                val options = runCatching { repository.releaseOptions(entry, dual) }.getOrNull()
                if (options != null && options.alternatives.size > 1) {
                    ambiguous += entry to options
                } else {
                    automatic += entry
                }
            }
            if (automatic.isNotEmpty()) enqueue(automatic)
            pendingChoices.addAll(ambiguous)
            if (pendingChoices.isNotEmpty()) {
                val (entry, options) = pendingChoices.first()
                _assetChoice.value = entry to options
            }
        }
    }

    /** El usuario eligió un APK concreto (como Obtainium). */
    fun chooseAsset(entry: EmulatorEntry, release: com.davidadrianrg.atril.core.emulators.SelectedRelease) {
        popChoice()
        val ok = queue.enqueueWithAsset(
            entryId = entry.id,
            name = entry.name,
            dualScreen = filters.value.dual,
            assetUrl = release.downloadUrl,
            assetName = release.assetName,
            assetVersion = release.version,
        )
        if (ok) {
            EmulatorDownloadWorker.enqueue(app)
            _messages.trySend(tr("emulators.queue.added", 1))
        }
    }

    /** Salta la pregunta y deja que la app elija automático. */
    fun skipAssetChoice() = popChoice()

    private fun popChoice() {
        if (pendingChoices.isEmpty()) return
        pendingChoices.removeAt(0)
        _assetChoice.value = pendingChoices.firstOrNull()?.let { it.first to it.second }
    }

    /** Preguntas pendientes de «¿qué APK quieres?» (entrada + opciones). */
    private val pendingChoices = mutableListOf<Pair<EmulatorEntry, EmulatorRepository.ReleaseOptions>>()
    val assetChoice: StateFlow<Pair<EmulatorEntry, EmulatorRepository.ReleaseOptions>?> =
        MutableStateFlow<Pair<EmulatorEntry, EmulatorRepository.ReleaseOptions>?>(null)
            .asStateFlow()
    private val _assetChoice = MutableStateFlow<Pair<EmulatorEntry, EmulatorRepository.ReleaseOptions>?>(null)

    private fun enqueue(entries: List<EmulatorEntry>) {
        val added = queue.enqueue(entries.map { it.id to it.name }, filters.value.dual)
        if (added.isEmpty()) return
        attempted.removeAll(added.toSet())
        EmulatorDownloadWorker.enqueue(app)
        _messages.trySend(tr("emulators.queue.added", added.size))
    }

    /** Quita un emulador de la cola; si se estaba descargando, la corta. */
    fun cancel(entryId: String) {
        val removed = queue.remove(entryId) ?: return
        if (removed.state == EmulatorJobState.DOWNLOADING) EmulatorDownloadWorker.restart(app, queue)
        EmulatorDownloadWorker.syncReadyNotification(app, queue)
    }

    fun cancelAll() {
        queue.jobs.value.map { it.entryId }.forEach { queue.remove(it) }
        EmulatorDownloadWorker.restart(app, queue)
        EmulatorDownloadWorker.syncReadyNotification(app, queue)
    }

    /** Abre el instalador del sistema con un APK ya descargado. */
    fun installReady(entryId: String) {
        val job = queue.get(entryId)?.takeIf { it.state == EmulatorJobState.READY } ?: return
        attempted += entryId
        if (!EmulatorDownloadWorker.apkExists(job.apkPath)) {
            // La caché del sistema se lo llevó: se vuelve a descargar.
            queue.requeue(entryId)
            attempted -= entryId
            EmulatorDownloadWorker.enqueue(app)
            return
        }
        // Síncrono a propósito: `awaiting` queda fijado antes de que otra
        // emisión de la cola pueda abrir un segundo instalador.
        val before = _installed.value[entryId]
        when (val result = launchInstaller(File(job.apkPath!!))) {
            InstallLaunchResult.Started -> {
                awaiting = entryId to before
                _messages.trySend(tr("emulators.installer_opened", job.name, job.version.orEmpty()))
            }
            InstallLaunchResult.NeedsPermission ->
                _messages.trySend(tr("emulators.needs_install_permission"))
            is InstallLaunchResult.Error -> _messages.trySend(result.message)
        }
    }

    // ── Ciclo de vida ───────────────────────────────────────────────────

    fun onResume() {
        resumed = true
        viewModelScope.launch {
            refreshInstalled()
            awaiting?.let { (id, before) ->
                awaiting = null
                val job = queue.get(id)
                val entry = _entries.value?.firstOrNull { it.id == id }
                val after = entry?.let { _installed.value[it.id] }
                if (job != null && EmulatorInstallFlow.installFinished(before, after, job.version)) {
                    queue.remove(id)
                }
            }
            EmulatorDownloadWorker.syncReadyNotification(app, queue)
            maybeAutoInstall()
        }
    }

    fun onPause() {
        resumed = false
    }

    private fun maybeAutoInstall() {
        if (!resumed || awaiting != null) return
        val next = EmulatorInstallFlow.nextAutoInstall(queue.jobs.value, attempted) ?: return
        installReady(next.entryId)
    }

    // ── Comprobar actualizaciones ───────────────────────────────────────

    fun checkUpdates() {
        if (_checking.value) return
        _checking.value = true
        viewModelScope.launch {
            try {
                // Los fallos no se tragan: sin red o con el límite de GitHub
                // agotado se dice, en vez de «actualizaciones comprobadas».
                var failures = 0
                var lastError: Throwable? = null
                for (entry in _entries.value.orEmpty()) {
                    val version = withContext(Dispatchers.IO) { repository.installedVersion(entry) } ?: continue
                    val release = try {
                        repository.latestRelease(entry, dualScreen = filters.value.dual)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: GitHubRateLimitException) {
                        lastError = e
                        failures++
                        break
                    } catch (e: Exception) {
                        lastError = e
                        failures++
                        null
                    } ?: continue
                    if (VersionCompare.isNewer(release.version, version)) {
                        _latest.value = _latest.value + (entry.id to release.version)
                    }
                }
                val error = lastError
                _messages.trySend(
                    when {
                        error is GitHubRateLimitException -> error.message.orEmpty()
                        error != null -> tr("emulators.updates_failed", failures, error.message ?: "")
                        else -> tr("emulators.updates_checked")
                    },
                )
            } finally {
                _checking.value = false
            }
        }
    }

    private companion object {
        const val KEY_DUAL = "emu_dual"
        const val KEY_CATEGORY = "emu_category"
        const val KEY_QUERY = "emu_query"
        const val KEY_INSTALLED_ONLY = "emu_installed_only"
        const val KEY_SEARCH_OPEN = "emu_search_open"
        const val KEY_SELECTED = "emu_selected"
    }
}
