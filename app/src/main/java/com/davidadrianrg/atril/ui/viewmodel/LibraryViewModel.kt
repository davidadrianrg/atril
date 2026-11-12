package com.davidadrianrg.atril.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.core.library.DiscEntry
import com.davidadrianrg.atril.core.library.LibrarySort
import com.davidadrianrg.atril.core.library.LibrarySorting
import com.davidadrianrg.atril.core.library.MultiDiscGrouper
import com.davidadrianrg.atril.core.playstats.PlayStats
import com.davidadrianrg.atril.core.playstats.PlayStatsLogic
import com.davidadrianrg.atril.core.playstats.PlayStatsRepository
import com.davidadrianrg.atril.data.repository.LocateOutcome
import com.davidadrianrg.atril.data.repository.RomFileImportService
import com.davidadrianrg.atril.data.repository.RomRepository
import com.davidadrianrg.atril.data.repository.SettingsRepository
import com.davidadrianrg.atril.domain.model.ApiResult
import com.davidadrianrg.atril.domain.model.DownloadStatus
import com.davidadrianrg.atril.domain.model.ErrorKind
import com.davidadrianrg.atril.domain.model.Rom
import com.davidadrianrg.atril.domain.model.RomWithStatus
import com.davidadrianrg.atril.download.DownloadManager
import com.davidadrianrg.atril.launch.GameLaunchPlan
import com.davidadrianrg.atril.launch.GameLaunchRequest
import com.davidadrianrg.atril.launch.RomLaunchService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import com.davidadrianrg.atril.domain.model.withPhysicalMediaOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val romRepository: RomRepository,
    private val downloadManager: DownloadManager? = null,
    private val playStats: PlayStatsRepository? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val romLaunchService: RomLaunchService? = null,
    private val romFileImportService: RomFileImportService? = null,
) : ViewModel() {

    private val _selectedPlatformId = MutableStateFlow<Int?>(null)
    val selectedPlatformId = _selectedPlatformId.asStateFlow()

    private val _roms = MutableStateFlow<List<Rom>>(emptyList())
    val roms = _roms.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    /** True when actively loading the next page (not the first). */
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()

    /** False when we've fetched all pages for this platform/search. */
    private val _hasMore = MutableStateFlow(true)
    val hasMore = _hasMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** True mientras se mueve un ROM entre almacenamientos (bloquea la UI). */
    private val _isMovingRom = MutableStateFlow(false)
    val isMovingRom: StateFlow<Boolean> = _isMovingRom.asStateFlow()

    /** Progreso del move en curso: nombre del juego a (copiados, total) bytes. */
    private val _movingProgress = MutableStateFlow<Pair<String, Pair<Long, Long>>?>(null)
    val movingProgress: StateFlow<Pair<String, Pair<Long, Long>>?> = _movingProgress.asStateFlow()

    /** Current server-side search term (null = no search, browse all). */
    private var currentSearch: String? = null

    companion object {
        private const val PAGE_SIZE = 50
        private const val TAG = "LibraryViewModel"
    }

    /** One-shot events for UI feedback (snackbars). */
    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    /** Observa la configuración de sync (ruta/exclusión) de un juego descargado. */
    fun observeRomSyncConfig(romId: Int) = romRepository.observeDownloadedRom(romId)

    /** Fija (o limpia con null) el override de ruta de saves de un juego concreto. */
    fun setRomSavesPathOverride(romId: Int, path: String?) {
        viewModelScope.launch {
            romRepository.updateRomSavesPathOverride(romId, path)
        }
    }

    /** Excluye o incluye un juego en la sincronización de partidas. */
    fun setRomExcludedFromSync(romId: Int, excluded: Boolean) {
        viewModelScope.launch {
            romRepository.updateRomExcludedFromSync(romId, excluded)
        }
    }

    val downloadedIds: StateFlow<Set<Int>> = romRepository.getDownloadedRomIds()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet(),
        )

    val manualDownloadedIds: StateFlow<Set<Int>> = romRepository.getManualDownloadedRomIds()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet(),
        )

    /**
     * Live set of ROM IDs currently downloading (ENQUEUED or RUNNING).
     * Driven by WorkManager observeDownloads() — auto-updates when downloads
     * complete or fail, so the card spinner resolves to a checkmark.
     */
    private val activeDownloads: StateFlow<Set<Int>> =
        (downloadManager?.observeDownloads() ?: kotlinx.coroutines.flow.flowOf(emptyList<com.davidadrianrg.atril.domain.model.DownloadTask>()))
            .map { tasks ->
                tasks.filter { it.isRunning || (!it.isCompleted && !it.isFailed) }
                    .map { it.romId }
                    .toSet()
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptySet(),
            )

    // ── Orden y filtro por región (declarados ANTES de romsWithStatus,
    // que los referencia como fuentes del combine) ─────────────────────

    private val _sort = MutableStateFlow(RomSort.NAME_ASC)
    val sort: StateFlow<RomSort> = _sort

    fun setSort(s: RomSort) { _sort.value = s }

    private val _regionFilter = MutableStateFlow<String?>(null)
    val regionFilter: StateFlow<String?> = _regionFilter

    fun setRegionFilter(r: String?) { _regionFilter.value = r }

    /** Regiones distintas presentes en los ROMs cargados (para el selector). */
    val availableRegions: StateFlow<List<String>> = _roms
        .map { roms -> roms.flatMap { it.regions }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Roms de la lista con el medio físico recién scrapeado aplicado. */
    private val effectiveRoms: Flow<List<Rom>> = combine(_roms, romRepository.physicalMediaOverrides) { roms, urls ->
        if (urls.isEmpty()) roms else roms.map { it.withPhysicalMediaOverride(urls) }
    }

    /**
     * ROMs de la plataforma tal como los devuelve la API de RomM, agrupadas
     * con [MultiDiscGrouper]: una card por juego, con los discos sueltos y
     * los supersets/duplicados colapsados (p. ej. MGS Twin Snakes tenía 3
     * entradas en RomM: Disc 1, Disc 2 y el multi-disco completo).
     *
     * NO se agrupa solo por igdbId: Golden Sun 1 y 2 comparten igdb_id y
     * deben seguir siendo juegos distintos.
     *
     * El filtro por región se aplica aquí; el orden elegido se aplica después
     * en [romsWithStatus], porque "última partida" necesita las estadísticas.
     */
    private val groupedRoms: Flow<List<RomWithStatus>> = combine(
        effectiveRoms, downloadedIds, activeDownloads, regionFilter,
    ) { roms, downloaded, downloading, region ->
        val groups = MultiDiscGrouper.group(
            roms.map { rom ->
                DiscEntry(
                    id = rom.id,
                    name = rom.name,
                    platformSlug = rom.platformSlug,
                    igdbId = rom.igdbId,
                    fileNames = rom.files.map { it.filename }.ifEmpty { listOf(rom.fileName) },
                )
            }
        )
        val byId = roms.associateBy { it.id }
        groups.mapNotNull { group ->
            val rom = byId[group.representativeId] ?: return@mapNotNull null
            val status = when {
                group.downloadIds.all { it in downloaded } -> DownloadStatus.DOWNLOADED
                group.downloadIds.any { it in downloading } -> DownloadStatus.DOWNLOADING
                else -> DownloadStatus.NOT_DOWNLOADED
            }
            RomWithStatus(
                rom = rom,
                status = status,
                discCount = group.discCount,
                groupRomIds = group.downloadIds,
            )
        }
            .filter { card ->
                region == null || card.rom.regions.any { it.equals(region, ignoreCase = true) }
            }
    }

    /**
     * Estadísticas de partida por romId. Sin repositorio (tests que solo
     * prueban la carga) todos los juegos cuentan como nunca jugados.
     */
    private val playStatsById: Flow<Map<Int, PlayStats>> =
        playStats?.stats ?: flowOf(emptyMap())

    /**
     * Cards visibles de la biblioteca: agrupadas, filtradas por región y
     * ordenadas con [LibrarySorting] según el criterio persistido. El orden
     * por última partida agrega las estadísticas del grupo completo, igual que
     * las tarjetas y el detalle.
     */
    val romsWithStatus: StateFlow<List<RomWithStatus>> = combine(
        groupedRoms, playStatsById, sort,
    ) { grouped, stats, sortType ->
        LibrarySorting.sort(
            items = grouped,
            sort = sortType,
            name = { it.rom.name },
            sizeBytes = { it.rom.fileSizeBytes },
            releaseDate = { it.rom.igdbMetadata?.firstReleaseDate },
            rating = { it.rom.igdbMetadata?.totalRating },
            lastPlayedAt = {
                PlayStatsLogic.aggregate(it.groupRomIds + it.rom.id, stats).lastPlayedAt
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
    )

    fun selectPlatform(platformId: Int, serverUrl: String, apiKey: String) {
        _selectedPlatformId.value = platformId
        currentSearch = null
        // Sin vaciar, un fallo de carga dejaría los juegos de la plataforma anterior
        // bajo la nueva (y "Descargar faltantes" ofrecería esos juegos).
        _roms.value = emptyList()
        _platformAllRoms.value = emptyList()
        loadRoms(serverUrl, apiKey, reset = true)
    }

    /**
     * Server-side search: fetches ROMs matching [query] from the ENTIRE
     * platform via the RomM API `search` parameter. Replaces the current list.
     * Pass empty string to clear search and reload full list.
     */
    fun searchRoms(query: String, serverUrl: String, apiKey: String) {
        val newSearch = query.ifBlank { null }
        // El LaunchedEffect de la pantalla emite "" al elegir plataforma, justo
        // cuando selectPlatform ya está cargando lo mismo: no duplicar la petición.
        if (!shouldReloadForSearch(
                newSearch = newSearch,
                currentSearch = currentSearch,
                hasPlatform = _selectedPlatformId.value != null,
                isLoading = _isLoading.value,
                hasRoms = _roms.value.isNotEmpty(),
            )
        ) return
        currentSearch = newSearch
        _platformAllRoms.value = emptyList()
        loadRoms(serverUrl, apiKey, reset = true)
    }

    /** Adaptador Rom → DiscEntry para [MultiDiscGrouper]. */
    private fun Rom.toDiscEntry(): DiscEntry = DiscEntry(
        id = id,
        name = name,
        platformSlug = platformSlug,
        igdbId = igdbId,
        fileNames = files.map { it.filename }.ifEmpty { listOf(fileName) },
    )

    /** Called by infinite scroll when user nears the bottom. */
    fun loadMore(serverUrl: String, apiKey: String) {
        if (_isLoadingMore.value || !_hasMore.value || _isLoading.value) return
        loadRoms(serverUrl, apiKey, reset = false)
    }

    /** Called by UI when user taps download. Emits snackbar feedback. */
    fun onDownloadEnqueued(romName: String) {
        viewModelScope.launch {
            _events.emit(LibraryEvent.DownloadStarted(romName))
        }
    }

    /**
     * Elimina una ROM descargada: borra el archivo del disco y la desmarca
     * como descargada. El estado de la UI se actualiza solo porque
     * [downloadedIds] observa Room. Acepta el grupo completo (multi-disc).
     */
    fun deleteDownloadedRom(rom: Rom, groupRomIds: List<Int> = listOf(rom.id)) {
        viewModelScope.launch {
            var anyOk = false
            groupRomIds.forEach { id ->
                if (romRepository.deleteDownloadedRom(id)) anyOk = true
            }
            if (anyOk) {
                _events.emit(LibraryEvent.DownloadDeleted(rom.name))
            } else {
                _events.emit(LibraryEvent.Error(tr("android.library.delete_failed", rom.name)))
            }
        }
    }

    /**
     * Mueve una ROM descargada entre almacenamientos (interna <-> SD).
     * Copia + verifica + borra; el original se conserva si algo falla.
     * El progreso de la copia se expone en [movingProgress].
     */
    fun moveDownloadedRom(rom: Rom, targetRoot: String) {
        viewModelScope.launch {
            _isMovingRom.value = true
            _movingProgress.value = rom.name to (0L to 0L)
            val result = romRepository.moveDownloadedRom(
                romId = rom.id,
                targetRoot = targetRoot,
                onProgress = { copied, total ->
                    _movingProgress.value = rom.name to (copied to total)
                },
            )
            _isMovingRom.value = false
            _movingProgress.value = null
            when (result) {
                is com.davidadrianrg.atril.data.repository.MoveResult.Success ->
                    _events.emit(LibraryEvent.RomMoved(rom.name, result.target.absolutePath))
                com.davidadrianrg.atril.data.repository.MoveResult.NotDownloaded ->
                    _events.emit(LibraryEvent.Error(tr("android.library.move_not_downloaded", rom.name)))
                com.davidadrianrg.atril.data.repository.MoveResult.SourceMissing ->
                    _events.emit(LibraryEvent.Error(tr("android.library.move_source_missing", rom.name)))
                is com.davidadrianrg.atril.data.repository.MoveResult.Error ->
                    _events.emit(LibraryEvent.Error(tr("android.library.move_error", rom.name, result.message)))
            }
        }
    }

    /**
     * Batch download: enqueues every ROM in [roms] that is not already downloading.
     * Used by the "Descargar faltantes" action in the library toolbar.
     *
     * @param romsRootPath raíz destino (modo dos rutas); null = la principal.
     */
    fun enqueueBatchDownload(roms: List<Rom>, serverUrl: String, romsRootPath: String? = null) {
        val manager = downloadManager ?: run {
            viewModelScope.launch { _events.emit(LibraryEvent.Error(tr("android.library.download_manager_unavailable"))) }
            return
        }
        // Skip ROMs that are already enqueued/running to avoid duplicate work.
        val currentlyDownloading = activeDownloads.value
        val toEnqueue = roms.filter { it.id !in currentlyDownloading }
        if (toEnqueue.isEmpty()) {
            viewModelScope.launch { _events.emit(LibraryEvent.Error(tr("android.library.no_new_roms"))) }
            return
        }
        viewModelScope.launch {
            toEnqueue.forEach { rom ->
                manager.enqueueDownload(rom = rom, serverUrl = serverUrl, romsRootPath = romsRootPath)
            }
            _events.emit(LibraryEvent.BatchDownloadStarted(toEnqueue.size))
        }
    }

    private val _platformAllRoms = MutableStateFlow<List<Rom>>(emptyList())
    private val _allPlatformLoading = MutableStateFlow(false)
    val allPlatformLoading: StateFlow<Boolean> = _allPlatformLoading

    /** ROMs de TODA la plataforma que aún no están descargadas (todas las páginas). */
    val platformMissingRoms: StateFlow<List<Rom>> = combine(_platformAllRoms, downloadedIds) { all, downloaded ->
        all.filter { it.id !in downloaded }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Carga todas las páginas de la plataforma seleccionada (para el batch). */
    fun loadAllPlatformRoms() {
        val platformId = _selectedPlatformId.value ?: return
        // Una carga nueva sustituye a la que esté en curso: si se ignorase la nueva, la
        // plataforma recién elegida se quedaría con las ROMs de la anterior.
        val generation = ++allRomsGeneration
        viewModelScope.launch {
            _allPlatformLoading.value = true
            val result = romRepository.fetchAllRomsForPlatform(platformId, currentSearch)
            if (generation != allRomsGeneration) return@launch
            _allPlatformLoading.value = false
            if (platformId != _selectedPlatformId.value) return@launch
            when (result) {
                is ApiResult.Success -> _platformAllRoms.value = result.data
                is ApiResult.Error -> {
                    // Solo avisa si la lista principal cargó bien; si no, ese fallo ya se ha notificado.
                    if (_roms.value.isNotEmpty() && !_isLoading.value && _error.value == null) {
                        _events.emit(LibraryEvent.Error(result.kind.toUserMessage()))
                    }
                }
            }
        }
    }

    /** Token de la última carga completa de la plataforma (guard anti-respuesta-obsoleta). */
    private var allRomsGeneration = 0

    /** Cards de TODO lo descargado de la plataforma (no solo las páginas cargadas). */
    val platformDownloadedCards: StateFlow<List<RomWithStatus>> =
        combine(_platformAllRoms, downloadedIds) { all, downloaded ->
            val groups = MultiDiscGrouper.group(all.map { it.toDiscEntry() })
            val byId = all.associateBy { it.id }
            groups.mapNotNull { group ->
                val rom = byId[group.representativeId] ?: return@mapNotNull null
                if (group.downloadIds.none { it in downloaded }) return@mapNotNull null
                RomWithStatus(
                    rom = rom,
                    status = DownloadStatus.DOWNLOADED,
                    discCount = group.discCount,
                    groupRomIds = group.downloadIds,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Marca el juego (y su grupo) como descargado y, si el fichero ya está en
     * `<romsRoot>/<plataforma>`, guarda su ruta para que quede lanzable. Sin
     * raíz configurada o sin coincidencia mantiene la marca manual (`""`).
     */
    fun markDownloadedManually(rom: Rom, groupRomIds: List<Int>) {
        viewModelScope.launch {
            val path = resolveManualPath(rom)
            groupRomIds.forEach { id ->
                romRepository.markRomDownloaded(
                    romId = id,
                    name = rom.name,
                    fileName = rom.fileName,
                    platformId = rom.platformId,
                    platformSlug = rom.platformSlug,
                    localPath = path ?: "",
                    fileSizeBytes = rom.fileSizeBytes,
                )
            }
            _events.emit(
                LibraryEvent.Error(
                    if (path != null) tr("library.file_linked")
                    else tr("library.marked_downloaded", rom.name),
                ),
            )
        }
    }

    /** Busca el fichero del marcado manual bajo la raíz principal y la secundaria. */
    private suspend fun resolveManualPath(rom: Rom): String? {
        val settings = settingsRepository ?: return null
        return try {
            val config = settings.settings.first()
            val roots = buildList {
                add(config.romsRootPath.trim())
                if (config.dualRomsPathsEnabled) add(config.secondaryRomsPath.trim())
            }.filter { it.isNotBlank() }
            if (roots.isEmpty()) null
            else romRepository.locateLocalFile(roots, rom.platformSlug, rom.fileName)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sin configuración legible se conserva la marca manual (""), pero
            // se deja rastro del motivo para diagnosticar el "no lo encuentra".
            // Aislado: `android.util.Log` no está mockeado en los tests JVM.
            runCatching { Log.w(TAG, "Couldn't read ROM roots (${rom.id}): ${e.javaClass.simpleName}") }
            null
        }
    }

    /** Quita la marca manual (no borra ficheros: no hay ruta gestionada). */
    fun unmarkDownloadedManually(rom: Rom, groupRomIds: List<Int>) {
        viewModelScope.launch {
            groupRomIds.forEach { id -> romRepository.removeDownloadedRom(id) }
            _events.emit(LibraryEvent.Error(tr("library.unmarked_downloaded", rom.name)))
        }
    }

    /** Asocia el documento elegido con el selector de ficheros al juego y su grupo. */
    suspend fun locateFile(
        rom: Rom,
        groupRomIds: List<Int>,
        uri: Uri,
        overwrite: Boolean,
    ): LocateOutcome {
        val service = romFileImportService ?: return LocateOutcome.CopyFailed
        return service.locate(
            romId = rom.id,
            groupRomIds = groupRomIds,
            platformSlug = rom.platformSlug,
            fileName = rom.fileName,
            uri = uri,
            overwrite = overwrite,
        )
    }

    /**
     * Resuelve de forma perezosa la ruta que falte o haya desaparecido y
     * prepara el lanzamiento (emulador o APK). Mismo camino que la pantalla Jugar.
     */
    suspend fun prepareLaunch(
        rom: Rom,
        localPath: String,
        platformLabel: String,
        platformEmulatorId: String?,
        overrideFamilyId: String?,
    ): GameLaunchPlan {
        val service = romLaunchService ?: return GameLaunchPlan.Failed(tr("launch.error.manual"))
        // La hoja puede abrirse antes de que el Flow de sync haya entregado la
        // ruta: se prioriza la que ya está persistida en Room.
        val effectivePath = localPath.takeIf { it.isNotBlank() } ?: romRepository.getLocalPath(rom.id)
        return service.prepare(
            GameLaunchRequest(
                romId = rom.id,
                platformSlug = rom.platformSlug,
                platformLabel = platformLabel,
                localPath = effectivePath,
                fileName = rom.fileName,
                knownFileNames = rom.files.map { it.filename },
                overrideFamilyId = overrideFamilyId,
                saveEmulatorId = platformEmulatorId,
                retroArchCore = settingsRepository?.getRetroArchCoreBlocking(rom.platformSlug),
            ),
        )
    }

    /** Arranca un plan ya preparado; null = aceptado o mensaje de error. */
    suspend fun startLaunch(context: Context, plan: GameLaunchPlan): String? =
        romLaunchService?.startSuspending(context, plan)
            ?: (plan as? GameLaunchPlan.Failed)?.message

    /**
     * Pull-to-refresh: reloads the first page for the currently selected
     * platform keeping the active search term (if any), and the whole-platform
     * list behind "Download missing" (it may have failed along with the first page).
     */
    fun refresh(serverUrl: String, apiKey: String) {
        if (_selectedPlatformId.value == null) return
        loadRoms(serverUrl, apiKey, reset = true)
        loadAllPlatformRoms()
    }

    private fun loadRoms(serverUrl: String, apiKey: String, reset: Boolean) {
        val platformId = _selectedPlatformId.value ?: return
        // Guard de generación: con el debounce de búsqueda pueden solaparse
        // dos loadRoms (p. ej. "mar" y "mario"); si la respuesta VIEJA llega
        // después que la nueva, pisaría la lista con resultados obsoletos —
        // la causa intermitente de los "Sin resultados" falsos.
        val generation = ++loadGeneration
        viewModelScope.launch {
            if (reset) {
                _isLoading.value = true
                _hasMore.value = true
            } else {
                _isLoadingMore.value = true
            }
            _error.value = null
            romRepository.configureApi(serverUrl, apiKey)
            val offset = if (reset) 0 else _roms.value.size
            var fetched = romRepository.fetchRoms(
                platformId,
                offset = offset,
                search = currentSearch,
            )
            // Un 5xx o un corte de red puntual no debe enseñar un error si el
            // segundo intento funciona (RomM devuelve 500 de forma intermitente).
            if (fetched is ApiResult.Error && fetched.kind.isTransient() && generation == loadGeneration) {
                delay(RETRY_DELAY_MS)
                if (generation == loadGeneration) {
                    fetched = romRepository.fetchRoms(
                        platformId,
                        offset = offset,
                        search = currentSearch,
                    )
                }
            }
            when (val result = fetched) {
                is ApiResult.Success -> {
                    if (generation == loadGeneration) {
                        _roms.value = if (reset) result.data else _roms.value + result.data
                        _hasMore.value = result.data.size >= PAGE_SIZE
                    }
                }
                is ApiResult.Error -> {
                    if (generation == loadGeneration) {
                        val userMessage = result.kind.toUserMessage()
                        _error.value = userMessage
                        // Con la lista vacía ya lo cuenta el estado de error; un snackbar
                        // repetiría el texto y taparía "Reintentar" en horizontal.
                        if (_roms.value.isNotEmpty()) _events.emit(LibraryEvent.Error(userMessage))
                    }
                }
            }
            if (generation == loadGeneration) {
                _isLoading.value = false
                _isLoadingMore.value = false
            }
        }
    }

    /** Token de la última carga lanzada (guard anti-respuesta-obsoleta). */
    private var loadGeneration = 0
}

/** Pausa antes del reintento automático de una carga fallida de forma transitoria. */
private const val RETRY_DELAY_MS = 600L

/** Errores que merece la pena reintentar una vez: servidor (5xx) o red. */
internal fun ErrorKind.isTransient(): Boolean = this == ErrorKind.SERVER || this == ErrorKind.NETWORK

/**
 * Decide si una búsqueda debe lanzar una nueva carga. Se evita cuando pide lo
 * mismo que ya está cargado o en curso; si la carga previa falló y no hay
 * ROMs, sí se vuelve a intentar.
 */
internal fun shouldReloadForSearch(
    newSearch: String?,
    currentSearch: String?,
    hasPlatform: Boolean,
    isLoading: Boolean,
    hasRoms: Boolean,
): Boolean {
    if (!hasPlatform) return true
    if (newSearch != currentSearch) return true
    return !(isLoading || hasRoms)
}

/**
 * Maps [ErrorKind] to user-facing Spanish messages.
 */
private fun ErrorKind.toUserMessage(): String = when (this) {
    ErrorKind.NETWORK -> tr("android.library.error_network")
    ErrorKind.AUTH -> tr("android.library.error_auth")
    ErrorKind.NOT_FOUND -> tr("android.library.error_not_found")
    ErrorKind.SERVER -> tr("android.library.error_server")
    ErrorKind.UNKNOWN -> tr("android.library.error_unknown")
}

/** One-shot UI events emitted by the ViewModel. */
sealed class LibraryEvent {
    data class DownloadStarted(val romName: String) : LibraryEvent()
    data class BatchDownloadStarted(val count: Int) : LibraryEvent()
    data class DownloadDeleted(val romName: String) : LibraryEvent()
    /** ROM movido entre almacenamientos con éxito; [targetPath] es la nueva ruta. */
    data class RomMoved(val romName: String, val targetPath: String) : LibraryEvent()
    data class Error(val message: String) : LibraryEvent()
}

/**
 * Criterios de ordenación de la biblioteca: un alias del enum compartido de
 * :core, para que Android y desktop no puedan divergir. El alias mantiene
 * todos los puntos de llamada existentes (`RomSort.entries`, `s.labelKey`).
 */
typealias RomSort = LibrarySort
