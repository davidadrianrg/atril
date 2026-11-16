package com.davidadrianrg.atril.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.davidadrianrg.atril.core.launch.LaunchPlatforms
import com.davidadrianrg.atril.core.library.DiscEntry
import com.davidadrianrg.atril.core.library.PhysicalMedia
import com.davidadrianrg.atril.core.library.MultiDiscGrouper
import com.davidadrianrg.atril.core.remote.NetworkModule
import com.davidadrianrg.atril.core.remote.dto.IgdbMetadataDto
import com.davidadrianrg.atril.core.remote.dto.RomDto
import com.davidadrianrg.atril.core.sync.platform.SaveHandlerRegistry
import com.davidadrianrg.atril.data.local.dao.PlatformDao
import com.davidadrianrg.atril.data.local.dao.RomDao
import com.davidadrianrg.atril.data.local.entity.DownloadedRomEntity
import com.davidadrianrg.atril.data.repository.LauncherDisplayMode
import com.davidadrianrg.atril.data.repository.LocateOutcome
import com.davidadrianrg.atril.data.repository.OfflineShelfCache
import com.davidadrianrg.atril.data.repository.OfflineShelfCache.Companion.withOfflineCover
import com.davidadrianrg.atril.core.remote.RomMApiService
import kotlinx.coroutines.flow.flowOf
import com.davidadrianrg.atril.data.repository.PhysicalMediaRepository
import com.davidadrianrg.atril.data.repository.RomFileImportService
import com.davidadrianrg.atril.data.repository.RomRepository
import com.davidadrianrg.atril.data.repository.SettingsRepository
import com.davidadrianrg.atril.domain.model.IgdbMetadata
import com.davidadrianrg.atril.domain.model.Rom
import com.davidadrianrg.atril.domain.model.withCoverOverride
import com.davidadrianrg.atril.domain.model.RomFile
import com.davidadrianrg.atril.launch.EmulatorLauncher
import com.davidadrianrg.atril.launch.GameLaunchPlan
import com.davidadrianrg.atril.launch.GameLaunchRequest
import com.davidadrianrg.atril.launch.LaunchPlan
import com.davidadrianrg.atril.launch.LaunchRequest
import com.davidadrianrg.atril.launch.RomLaunchService
import com.davidadrianrg.atril.core.i18n.tr
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.davidadrianrg.atril.domain.model.withPhysicalMediaOverride
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Un juego descargado listo para pintar en la estantería y abrir su detalle. */
data class ShelfItem(
    val rom: Rom,
    val platformLabel: String,
    val groupRomIds: List<Int>,
    val discCount: Int,
    val savesPathOverride: String?,
    val excludedFromSync: Boolean,
    val platformSavesPath: String,
    val isManualDownloaded: Boolean,
    val fileSizeBytes: Long,
    /** Ruta local del representante (vacía si se marcó a mano). */
    val localPath: String = "",
    /** Ficheros conocidos del juego en RomM (para resolver zip-streams). */
    val knownFileNames: List<String> = emptyList(),
    /** Emulador de saves configurado explícitamente para la plataforma. */
    val platformEmulatorId: String? = null,
    /** Preferencia del juego; null = usar el valor global. */
    val displayModePreference: LauncherDisplayMode? = null,
    /** Imagen del medio físico cacheada en el dispositivo (existe). */
    val physicalMediaFile: String? = null,
    /** Modo efectivo: medio físico solo si se eligió y está cacheado. */
    val showPhysical: Boolean = false,
    /** Override del emulador de lanzamiento para la plataforma (id de familia). */
    val launchOverride: String? = null,
    /** Juego añadido a mano (NO está en RomM): sin descargas, saves ni medio físico de RomM. */
    val isLocalSource: Boolean = false,
    val localGame: com.davidadrianrg.atril.core.localgames.LocalGame? = null,
    /** Grupo multidisco local (solo cuando el juego local tiene más de un disco). */
    val localDiscGroup: com.davidadrianrg.atril.core.localgames.LocalDiscGroup? = null,
    /** Disco/playlist elegido dentro del grupo local (null = juego de un solo fichero). */
    val localDiscChoice: com.davidadrianrg.atril.core.localgames.LocalDiscChoice? = null,
) {
    /** Ruta que se entrega al emulador: el disco elegido o la ruta del juego. */
    val localLaunchPath: String get() = localDiscChoice?.launchPath ?: localPath
    val launchable: Boolean get() = localLaunchPath.isNotBlank()
    val hasRemotePhysicalMedia: Boolean get() = rom.physicalMediaUrl != null
    val isOptical: Boolean get() = LaunchPlatforms.isOptical(rom.platformSlug)
}

/**
 * Estantería: todos los juegos descargados (Room), agrupados por juego
 * (multidisco) y enriquecidos con los metadatos del servidor (carátula).
 *
 * Si un ROM ya no existe en el servidor o la petición falla, se pinta igual
 * con los datos locales: la estantería nunca oculta un juego descargado.
 */
class ShelfViewModel(
    private val romRepository: RomRepository,
    private val romDao: RomDao,
    private val platformDao: PlatformDao,
    private val settingsRepository: SettingsRepository,
    private val physicalMediaRepository: PhysicalMediaRepository,
    private val emulatorLauncher: EmulatorLauncher,
    private val localGames: com.davidadrianrg.atril.core.localgames.LocalGamesRepository,
    private val playStatsRepository: com.davidadrianrg.atril.core.playstats.PlayStatsRepository,
    private val preLaunchSaveSync: com.davidadrianrg.atril.launch.PreLaunchSaveSync? = null,
    private val romLaunchService: RomLaunchService? = null,
    private val romFileImportService: RomFileImportService? = null,
    /** Metadatos y carátulas persistidos: Play completo sin red (F1). */
    private val offlineCache: OfflineShelfCache? = null,
    private val coverOverrides: com.davidadrianrg.atril.core.library.CoverOverrides? = null,
    /** Cliente de metadatos (timeout corto); inyectable para tests sin red. */
    private val metadataApi: (serverUrl: String, apiKey: String) -> RomMApiService =
        { url, key -> NetworkModule.createMetadataApiService(url, key) },
) : ViewModel() {

    /** Se escribe desde varias corrutinas de IO a la vez: tiene que ser concurrente. */
    private val dtoCache = ConcurrentHashMap<Int, RomDto>()

    /** Juegos hidratados (Room + servidor), sin la capa de presentación. */
    private val _items = MutableStateFlow<List<ShelfItem>>(emptyList())

    /** Estadísticas por juego: favoritos, última partida y tiempo jugado. */
    val playStats: StateFlow<Map<Int, com.davidadrianrg.atril.core.playstats.PlayStats>> =
        playStatsRepository.stats

    /**
     * Juegos listos para el launcher: se combina la hidratación con la
     * presentación por juego, el valor global y los overrides de emulador
     * (cambiar el modo no vuelve a pedir nada al servidor).
     */
    /** Juegos de RomM descargados + juegos locales, en una sola lista. */
    private val allItems: kotlinx.coroutines.flow.Flow<List<ShelfItem>> =
        combine(
            _items,
            localGames.games,
            settingsRepository.localDiscChoices,
            offlineCache?.entries ?: flowOf(emptyMap()),
        ) { hydrated, local, discChoices, cached ->
            // Carátula de disco si es la misma que la remota: sin red y sin
            // repetir la descarga. Llega como Flow aparte para que guardar una
            // carátula no relance la hidratación contra el servidor.
            val remote = if (cached.isEmpty()) {
                hydrated
            } else {
                hydrated.map { item ->
                    val rom = item.rom.withOfflineCover(cached[item.rom.id])
                    if (rom === item.rom) item else item.copy(rom = rom)
                }
            }
            if (local.isEmpty()) {
                remote
            } else {
                // Sin RomM la tabla está vacía: el catálogo integrado pone nombre.
                val labels = com.davidadrianrg.atril.core.localgames.LocalPlatformChoices.BUILTIN.toMap() +
                    runCatching { platformDao.getAllPlatformsBlocking() }
                        .getOrDefault(emptyList()).associate { it.slug to it.name }
                remote + localShelfItems(local, labels, discChoices)
            }
        }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    val items: StateFlow<List<ShelfItem>> = combine(
        allItems,
        physicalMediaRepository.observe(),
        settingsRepository.launcherDefaultDisplay,
        settingsRepository.launchEmulatorOverrides,
        romRepository.physicalMediaOverrides,
    ) { base, presentation, defaultMode, overrides, scraped ->
        val globalDefault = LauncherDisplayMode.fromId(defaultMode) ?: LauncherDisplayMode.CARD
        base.map { item ->
            val row = presentation[item.rom.id]
            val cached = row?.physicalMediaPath?.takeIf { File(it).isFile }
            val pref = LauncherDisplayMode.fromId(row?.displayMode)
            // Un medio físico remoto (URL de RomM) también sirve para mostrarlo:
            // no hace falta tenerlo cacheado para poder elegir la vista física.
            val hasPhysical = cached != null || item.rom.physicalMediaUrl != null || scraped[item.rom.id] != null
            item.copy(
                rom = item.rom.withPhysicalMediaOverride(scraped),
                displayModePreference = pref,
                physicalMediaFile = cached,
                showPhysical = hasPhysical && (pref ?: globalDefault) == LauncherDisplayMode.PHYSICAL,
                launchOverride = overrides[LaunchPlatforms.canonical(item.rom.platformSlug)],
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())


    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            combine(
                romDao.observeAllDownloadedRoms(),
                settingsRepository.settings,
                settingsRepository.retroArchBasePath,
            ) { downloaded, cfg, retroBase -> Triple(downloaded, cfg, retroBase) }
                .distinctUntilChanged()
                .collectLatest { (downloaded, cfg, retroBase) ->
                    // Lo que ya no está descargado sale de la caché offline.
                    offlineCache?.let { cache ->
                        val ids = downloaded.map { it.romId }.toSet()
                        viewModelScope.launch { cache.retain(ids) }
                    }
                    if (downloaded.isEmpty()) {
                        _items.value = emptyList()
                        _loading.value = false
                        return@collectLatest
                    }

                    val platforms = withContext(Dispatchers.IO) {
                        runCatching { platformDao.getAllPlatformsBlocking() }
                            .getOrDefault(emptyList())
                            .associateBy { it.slug }
                    }
                    val baseCoverUrl = cfg.serverUrl.trimEnd('/') + "/"
                    val cached = offlineCache?.let { runCatching { it.snapshot() }.getOrNull() }.orEmpty()

                    // 1) Publicar YA lo que hay en Room (+ caché offline + lo ya
                    //    traído del servidor en esta sesión): Play nunca espera
                    //    a la red para enseñar los juegos descargados.
                    _items.value = buildItems(
                        downloaded,
                        buildRoms(downloaded, HashMap(dtoCache), cached, baseCoverUrl),
                        platforms,
                        retroBase,
                    )
                    _loading.value = false

                    val api = if (cfg.serverUrl.isNotBlank() && cfg.apiKey.isNotBlank()) {
                        runCatching { metadataApi(cfg.serverUrl, cfg.apiKey) }.getOrNull()
                    } else null
                    if (api == null) return@collectLatest
                    val semaphore = Semaphore(4)

                    // 2) Enriquecer en segundo plano. Hidratación por LISTADO de
                    //    plataforma (la misma fuente que usa la Biblioteca): así
                    //    las carátulas coinciden siempre. Luego, para ids que no
                    //    aparezcan, se pide el detalle.
                    val dtoById = ConcurrentHashMap<Int, RomDto>(dtoCache)
                    val reachable = AtomicBoolean(false)
                    val platformIds = downloaded
                        .mapNotNull { platforms[it.platformSlug]?.id }
                        .distinct()
                    coroutineScope {
                        platformIds.map { platformId ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    var offset = 0
                                    val pageSize = 500
                                    while (true) {
                                        val response = runCatching {
                                            api.getRoms(
                                                mapOf(
                                                    "platform_ids" to platformId.toString(),
                                                    "limit" to pageSize.toString(),
                                                    "offset" to offset.toString(),
                                                    "order_by" to "name",
                                                    "order_dir" to "asc",
                                                ),
                                            )
                                        }.getOrNull() ?: break
                                        reachable.set(true)
                                        response.items.forEach { dto ->
                                            dtoById[dto.id] = dto
                                            dtoCache[dto.id] = dto
                                        }
                                        if (response.items.size < pageSize) break
                                        offset += pageSize
                                        if (offset >= 10_000) break
                                    }
                                }
                            }
                        }.awaitAll()
                    }

                    // Si ningún listado respondió, el servidor no está al
                    // alcance: no se pide el detalle juego a juego (serían N
                    // timeouts más para nada).
                    val missing = downloaded.map { it.romId }.filter { !dtoById.containsKey(it) }
                    if (missing.isNotEmpty() && (reachable.get() || platformIds.isEmpty())) {
                        coroutineScope {
                            missing.map { romId ->
                                async(Dispatchers.IO) {
                                    semaphore.withPermit {
                                        val dto = runCatching { api.getRom(romId) }.getOrNull()
                                        if (dto != null) {
                                            dtoById[romId] = dto
                                            dtoCache[romId] = dto
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                    }

                    val roms = buildRoms(downloaded, dtoById, cached, baseCoverUrl)
                    _items.value = buildItems(downloaded, roms, platforms, retroBase)

                    // 3) Persistir lo que ha llegado del servidor (metadatos +
                    //    carátula) para el próximo arranque sin red.
                    offlineCache?.rememberAsync(roms.filterKeys { dtoById.containsKey(it) }.values)
                }
        }
    }

    /** Rom por descargado: del servidor si respondió; si no, de Room + caché offline. */
    private fun buildRoms(
        downloaded: List<DownloadedRomEntity>,
        dtoById: Map<Int, RomDto>,
        cached: Map<Int, com.davidadrianrg.atril.data.local.entity.RomMetadataCacheEntity>,
        baseCoverUrl: String,
    ): Map<Int, Rom> = downloaded.associate { entity ->
        val dto = dtoById[entity.romId]
        val base = if (dto != null) {
            buildRom(entity, dto, baseCoverUrl)
        } else {
            OfflineShelfCache.offlineRom(entity, cached[entity.romId])
        }
        val override = coverOverrides?.get(entity.romId)
        entity.romId to base.withCoverOverride(override)
    }

    /** Agrupa los descargados (multidisco) y los convierte en ítems de la estantería. */
    private fun buildItems(
        downloaded: List<DownloadedRomEntity>,
        romsById: Map<Int, Rom>,
        platforms: Map<String, com.davidadrianrg.atril.data.local.entity.PlatformEntity>,
        retroBase: String,
    ): List<ShelfItem> {
                    val discEntries = romsById.values.map { rom ->
                        DiscEntry(
                            id = rom.id,
                            name = rom.name,
                            platformSlug = rom.platformSlug,
                            igdbId = rom.igdbId,
                            fileNames = rom.files.map { it.filename }
                                .ifEmpty { listOf(rom.fileName) },
                        )
                    }
                    val downloadedIds = downloaded.map { it.romId }.toSet()

                    return MultiDiscGrouper.group(discEntries).mapNotNull { group ->
                        val repEntity = downloaded.firstOrNull { it.romId == group.representativeId }
                            ?: return@mapNotNull null
                        val repRom = romsById[group.representativeId] ?: return@mapNotNull null
                        val platform = platforms[repRom.platformSlug]
                        val emulatorId = platform?.emulatorId
                            ?: SaveHandlerRegistry.getDefaultEmulator(repRom.platformSlug).id
                        val platformSavesPath = platform?.savesPathOverride
                            ?.takeIf { it.isNotBlank() }
                            ?: SaveHandlerRegistry.getDefaultSavesPath(
                                emulatorId = emulatorId,
                                platformSlug = repRom.platformSlug,
                                retroArchBase = retroBase,
                            )
                        ShelfItem(
                            rom = repRom,
                            platformLabel = platform?.name ?: repRom.platformSlug.uppercase(),
                            groupRomIds = group.allIds.filter { it in downloadedIds },
                            discCount = group.discCount,
                            savesPathOverride = repEntity.savesPathOverride,
                            excludedFromSync = repEntity.excludedFromSync,
                            platformSavesPath = platformSavesPath,
                            isManualDownloaded = repEntity.localPath.isBlank(),
                            fileSizeBytes = downloaded
                                .filter { it.romId in group.allIds }
                                .sumOf { it.fileSizeBytes },
                            localPath = repEntity.localPath,
                            knownFileNames = repRom.files.map { it.filename },
                            platformEmulatorId = platform?.emulatorId,
                        )
                    }.sortedBy { it.rom.name.lowercase() }
    }

    fun setSavesPathOverride(romId: Int, path: String?) {
        viewModelScope.launch { romDao.updateSavesPathOverride(romId, path) }
    }

    fun setExcludedFromSync(romId: Int, excluded: Boolean) {
        viewModelScope.launch { romDao.updateExcludedFromSync(romId, excluded) }
    }

    /** Borra del dispositivo todos los ficheros del grupo (multidisco incluido). */
    fun delete(item: ShelfItem) {
        viewModelScope.launch {
            var any = false
            item.groupRomIds.forEach { id ->
                if (romRepository.deleteDownloadedRom(id)) any = true
            }
            if (!any) {
                _messages.tryEmit(tr("android.library.delete_failed", item.rom.name))
            } else {
                physicalMediaRepository.remove((item.groupRomIds + item.rom.id).distinct())
            }
        }
    }

    // ── Favoritos y tiempo jugado ──────────────────────────────────────

    /**
     * Invierte el favorito del juego completo (todos los ids del grupo, para
     * que el agregado del multidisco siga siendo exacto).
     */
    fun toggleFavorite(item: ShelfItem) {
        playStatsRepository.toggleFavorite(item.groupRomIds + item.rom.id)
    }

    /** Empieza a contar la partida: solo cuando el lanzamiento se ha aceptado. */
    fun onGameLaunched(item: ShelfItem) {
        playStatsRepository.beginSession(item.rom.id)
    }

    // ── Lanzamiento ────────────────────────────────────────────────────

    fun currentEmulatorName(item: ShelfItem): String? = emulatorLauncher
        .currentFamily(item.rom.platformSlug, item.launchOverride, item.platformEmulatorId)
        ?.displayName

    /** Id estable, único y NEGATIVO para un juego local (persistido, ver LocalRomIds). */
    private fun com.davidadrianrg.atril.core.localgames.LocalGame.syntheticRomId(): Int =
        com.davidadrianrg.atril.core.localgames.LocalRomIds.of(this)

    private fun com.davidadrianrg.atril.core.localgames.LocalGame.toShelfItem(
        platformLabel: String,
        discGroup: com.davidadrianrg.atril.core.localgames.LocalDiscGroup? = null,
        discChoice: com.davidadrianrg.atril.core.localgames.LocalDiscChoice? = null,
        memberRomIds: List<Int> = listOf(syntheticRomId()),
        discCount: Int = 1,
    ): ShelfItem {
        val isPackage = path.startsWith(
            com.davidadrianrg.atril.core.launch.LaunchTargetResolver.PACKAGE_PREFIX,
        )
        val file = if (isPackage) null else File(path)
        val exists = isPackage || file?.isFile == true
        val rom = Rom(
            id = syntheticRomId(),
            name = title,
            fileName = file?.name ?: title,
            fileSizeBytes = if (exists && file != null) file.length() else 0L,
            platformId = 0,
            platformSlug = platformSlug,
            coverUrlSmall = coverPath?.let { "file://$it" },
            coverUrlLarge = coverPath?.let { "file://$it" },
            files = emptyList(),
            isMulti = false,
            revision = null,
            regions = emptyList(),
            summary = summary,
        )
        return ShelfItem(
            rom = rom,
            platformLabel = "$platformLabel · ${tr("local.badge")}",
            groupRomIds = memberRomIds,
            discCount = discCount,
            savesPathOverride = null,
            excludedFromSync = true,
            platformSavesPath = "",
            isManualDownloaded = false,
            fileSizeBytes = rom.fileSizeBytes,
            localPath = if (exists) path else "",
            knownFileNames = listOfNotNull(file?.name),
            isLocalSource = true,
            localGame = this,
            localDiscGroup = discGroup,
            localDiscChoice = discChoice,
        )
    }

    /**
     * Un ítem por juego local, agrupando los discos del mismo juego con
     * [LocalDiscGrouper]. La carta muestra el representante (disco 1) y lleva
     * todos los ids del grupo para que favoritos y tiempo jugado agreguen.
     */
    private fun localShelfItems(
        games: List<com.davidadrianrg.atril.core.localgames.LocalGame>,
        labels: Map<String, String>,
        discChoices: Map<String, String>,
    ): List<ShelfItem> {
        val byId = games.associateBy { it.id }
        val entries = games.map {
            com.davidadrianrg.atril.core.localgames.LocalDiscEntry(
                id = it.id,
                title = it.title,
                path = it.path,
                platformSlug = it.platformSlug,
            )
        }
        return com.davidadrianrg.atril.core.localgames.LocalDiscGrouper.group(entries).mapNotNull { group ->
            val representative = byId[group.representativeId] ?: return@mapNotNull null
            val isGroup = group.discCount > 1
            val choice = if (isGroup) {
                com.davidadrianrg.atril.core.localgames.LocalDiscGrouper
                    .choiceFor(group, discChoices[group.key])
            } else {
                null
            }
            representative.toShelfItem(
                platformLabel = labels[representative.platformSlug]
                    ?: representative.platformSlug.uppercase(),
                discGroup = group.takeIf { isGroup },
                discChoice = choice,
                memberRomIds = group.members.mapNotNull { byId[it.id]?.syntheticRomId() },
                discCount = group.discCount,
            )
        }
    }

    /** Cambia el disco elegido de un grupo local sin lanzar nada. */
    fun setLocalDiscChoice(groupKey: String, choiceId: String) {
        viewModelScope.launch { settingsRepository.setLocalDiscChoice(groupKey, choiceId) }
    }

    // ── Acciones sobre juegos locales ─────────────────────────────────
    fun addLocalGame(title: String, platformSlug: String, path: String) {
        viewModelScope.launch {
            val game = localGames.add(title, platformSlug, path)
            scrapeLocal(game.id)
        }
    }

    /** Guarda una imagen de fondo elegida a mano para el juego. */
    fun setGameBackground(romId: Int, path: String?) {
        viewModelScope.launch { settingsRepository.setGameBackground(romId, path) }
    }

    fun scrapeLocal(id: String) {
        viewModelScope.launch {
            _messages.tryEmit(
                when (val out = localGames.scrape(id)) {
                    is com.davidadrianrg.atril.core.localgames.ScrapeOutcome.Found -> tr("local.scrape.done", out.game.source)
                    com.davidadrianrg.atril.core.localgames.ScrapeOutcome.NotFound -> tr("local.scrape.not_found")
                    is com.davidadrianrg.atril.core.localgames.ScrapeOutcome.BadCredentials -> tr("local.scrape.bad_credentials", out.provider)
                    is com.davidadrianrg.atril.core.localgames.ScrapeOutcome.Retryable -> tr("local.scrape.failed", out.provider)
                    com.davidadrianrg.atril.core.localgames.ScrapeOutcome.NotConfigured -> tr("local.scrape.not_configured")
                },
            )
        }
    }

    fun renameLocal(id: String, title: String) { viewModelScope.launch { localGames.rename(id, title) } }
    fun removeLocal(id: String) { viewModelScope.launch { localGames.remove(id) } }

    /**
     * Quita de Play un juego local. Si es un grupo multidisco se borran todos
     * sus discos (dejar medio grupo crearía discos huérfanos) y se olvida la
     * elección guardada del grupo.
     */
    fun removeLocal(item: ShelfItem) {
        viewModelScope.launch {
            val group = item.localDiscGroup
            val ids = group?.members?.map { it.id } ?: listOfNotNull(item.localGame?.id)
            ids.forEach { localGames.remove(it) }
            group?.let { settingsRepository.setLocalDiscChoice(it.key, null) }
        }
    }
    /** [input] lo cierra el repositorio al terminar: el llamante no debe envolverlo en `use`. */
    fun setLocalCover(id: String, input: java.io.InputStream) { viewModelScope.launch { localGames.setManualCover(id, input) } }

    /**
     * Copia una portada ya descargada y espera al resultado. Envolver
     * [setLocalCover] en `use {}` cerraba el stream antes de que la corrutina
     * de IO lo leyera: la portada no se guardaba y la UI decía que sí.
     */
    suspend fun setLocalCoverFromFile(id: String, file: File): Boolean =
        localGames.setManualCover(id, file.inputStream())

    suspend fun prepareLaunch(item: ShelfItem): GameLaunchPlan {
        // Pull de saves con tope de 8 s: nunca impide ni retrasa más el lanzamiento.
        val sync = preLaunchSaveSync
        if (sync != null && item.rom.id > 0) {
            val result = sync.run(item.rom.id) {
                _messages.tryEmit(tr("sync.before_launch.updating"))
            }
            if (result is com.davidadrianrg.atril.core.sync.SavePullResult.Failed ||
                result is com.davidadrianrg.atril.core.sync.SavePullResult.TimedOut
            ) {
                _messages.tryEmit(tr("sync.before_launch.failed"))
            }
        }
        // Si el usuario vinculó la app Android instalada, se lanza por paquete
        // aunque el APK descargado ya se haya borrado (sigue en RomM).
        val linkedApp = settingsRepository.getInstalledLinksBlocking()[item.rom.id.toString()]
        val launchPath = if (!item.isLocalSource && linkedApp != null) {
            "package:$linkedApp"
        } else {
            item.localLaunchPath
        }
        val service = romLaunchService
        if (service != null) {
            return service.prepare(
                GameLaunchRequest(
                    romId = item.rom.id,
                    platformSlug = item.rom.platformSlug,
                    platformLabel = item.platformLabel,
                    localPath = launchPath,
                    fileName = item.rom.fileName,
                    knownFileNames = item.knownFileNames,
                    overrideFamilyId = item.launchOverride,
                    saveEmulatorId = item.platformEmulatorId,
                    retroArchCore = settingsRepository.getRetroArchCoreBlocking(item.rom.platformSlug),
                ),
            )
        }
        // Sin servicio (tests o cableado incompleto): comportamiento anterior.
        return when (
            val plan = emulatorLauncher.prepare(
                LaunchRequest(
                    platformSlug = item.rom.platformSlug,
                    platformLabel = item.platformLabel,
                    localPath = item.localLaunchPath,
                    knownFileNames = item.knownFileNames,
                    overrideFamilyId = item.launchOverride,
                    saveEmulatorId = item.platformEmulatorId,
                ),
            )
        ) {
            is LaunchPlan.Ready -> GameLaunchPlan.Ready(plan)
            is LaunchPlan.Failed -> GameLaunchPlan.Failed(plan.message)
        }
    }

    /** Arranca el plan (emulador o APK); null = aceptado. */
    suspend fun startLaunch(activityContext: android.content.Context, plan: GameLaunchPlan): String? =
        romLaunchService?.startSuspending(activityContext, plan)
            ?: when (plan) {
                is GameLaunchPlan.Ready -> emulatorLauncher.start(activityContext, plan.plan)
                is GameLaunchPlan.Apk -> tr("launch.error.apk_no_opener")
                is GameLaunchPlan.InstalledApp -> tr("launch.error.app_desktop")
                is GameLaunchPlan.Failed -> plan.message
            }

    /** Asocia el documento elegido al juego del launcher (Localizar archivo). */
    suspend fun locateFile(item: ShelfItem, uri: android.net.Uri, overwrite: Boolean): LocateOutcome {
        val service = romFileImportService ?: return LocateOutcome.CopyFailed
        return service.locate(
            romId = item.rom.id,
            groupRomIds = item.groupRomIds,
            platformSlug = item.rom.platformSlug,
            fileName = item.rom.fileName,
            uri = uri,
            overwrite = overwrite,
        )
    }

    /**
     * Convierte el DTO del servidor en el modelo de dominio. Sin DTO (servidor
     * caído o ROM eliminada) se usa [OfflineShelfCache.offlineRom].
     */
    private fun buildRom(entity: DownloadedRomEntity, dto: RomDto, baseCoverUrl: String): Rom {
        return Rom(
            id = dto.id,
            name = dto.name.ifBlank { dto.fileName },
            fileName = dto.fileName,
            fileSizeBytes = dto.fileSizeBytes,
            platformId = dto.platformId,
            platformSlug = dto.platformSlug ?: entity.platformSlug,
            // El asset local de RomM antes que la URL externa (igual que en
            // escritorio); el `?ts=` puede llevar espacios y se codifican.
            coverUrlSmall = dto.pathCoverSmall?.takeIf { it.isNotBlank() }
                ?.let { baseCoverUrl + it.removePrefix("/").replace(" ", "%20") }
                ?: dto.urlCover?.takeIf { it.isNotBlank() },
            coverUrlLarge = dto.pathCoverLarge?.takeIf { it.isNotBlank() }
                ?.let { baseCoverUrl + it.removePrefix("/").replace(" ", "%20") },
            files = dto.files.map { RomFile(it.filename, it.size, it.hash) },
            isMulti = dto.multi || dto.hasMultipleFiles,
            revision = dto.revision,
            regions = dto.regions,
            languages = dto.languages,
            genres = dto.genres,
            summary = dto.summary,
            fileNameNoTags = dto.fileNameNoTags,
            fileNameNoExt = dto.fileNameNoExt,
            fileExtension = dto.fileExtension,
            igdbId = dto.igdbId,
            igdbMetadata = dto.igdbMetadata?.toDomain(),
            screenshots = dto.mergedScreenshots.map { baseCoverUrl + it.removePrefix("/") },
            videoPath = dto.pathVideo?.let { baseCoverUrl + it.removePrefix("/") },
            manualPath = dto.pathManual?.let { baseCoverUrl + it.removePrefix("/") },
            youtubeVideoId = dto.youtubeVideoId,
            physicalMediaUrl = PhysicalMedia.resourceUrl(baseCoverUrl, dto.physicalMediaPath),
        )
    }

    /** Mismo mapeo que RomRepository: solo los campos que usa la UI. */
    private fun IgdbMetadataDto.toDomain() = IgdbMetadata(
        totalRating = totalRating?.toDoubleOrNull(),
        firstReleaseDate = firstReleaseDate,
        genres = genres,
        companies = companies,
        gameModes = gameModes,
        playerCount = playerCount,
    )
}
