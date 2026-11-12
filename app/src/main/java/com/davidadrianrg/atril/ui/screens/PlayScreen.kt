package com.davidadrianrg.atril.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.davidadrianrg.atril.ui.input.focusRing
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.davidadrianrg.atril.data.repository.LauncherDisplayMode
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.davidadrianrg.atril.RomMSyncApplication
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.core.library.LibraryViewPreferences
import com.davidadrianrg.atril.core.playstats.GamePlayStats
import com.davidadrianrg.atril.core.playstats.PlayStats
import com.davidadrianrg.atril.core.playstats.PlayStatsLogic
import com.davidadrianrg.atril.core.playstats.PlayTimeFormat
import com.davidadrianrg.atril.data.local.ServerConfig
import com.davidadrianrg.atril.data.repository.LocateOutcome
import com.davidadrianrg.atril.domain.model.formatBytesForUi
import com.davidadrianrg.atril.launch.GameLaunchPlan
import com.davidadrianrg.atril.ui.components.rememberLocateFilePicker
import com.davidadrianrg.atril.ui.navigation.Screen
import com.davidadrianrg.atril.ui.viewmodel.ShelfItem
import com.davidadrianrg.atril.ui.viewmodel.ShelfViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Juego cuyo medio físico se está "insertando" antes de lanzar. */
private data class Insertion(val item: ShelfItem, val model: Any?)

/**
 * Agregado de las estadísticas del grupo del juego (todos sus discos más el
 * representante): lo que pintan la carta, el detalle y la fila de continuar.
 */
private fun Map<Int, PlayStats>.statsOf(item: ShelfItem): GamePlayStats =
    PlayStatsLogic.aggregate(item.groupRomIds + item.rom.id, this)

/**
 * Launcher de juegos descargados, a pantalla completa y sin chrome permanente:
 * la navegación vive en un menú overlay. Cada juego se muestra como carta o,
 * si el usuario lo eligió y está descargado, como su medio físico real.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(
    onOpenDownloads: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    menuItems: List<Screen> = emptyList(),
    onNavigate: (Screen) -> Unit = {},
) {
    val context = LocalContext.current
    val container = (context.applicationContext as RomMSyncApplication).container
    val viewModel: ShelfViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ShelfViewModel(
                    romRepository = container.romRepository,
                    romDao = container.romDao,
                    platformDao = container.platformDao,
                    settingsRepository = container.settingsRepository,
                    physicalMediaRepository = container.physicalMediaRepository,
                    emulatorLauncher = container.emulatorLauncher,
                    localGames = container.localGamesRepository,
                    playStatsRepository = container.playStats,
                    preLaunchSaveSync = container.preLaunchSaveSync,
                    romLaunchService = container.romLaunchService,
                    romFileImportService = container.romFileImportService,
                )
            }
        }
    )

    val items by viewModel.items.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val playStats by viewModel.playStats.collectAsState()
    val settings by container.settingsRepository.settings.collectAsState(
        initial = ServerConfig("", "", "", 2)
    )
    val landscape = com.davidadrianrg.atril.ui.components.rememberWindowInfo().isLandscape
    val viewPrefs by container.settingsRepository.libraryViewPreferences
        .collectAsState(initial = LibraryViewPreferences.DEFAULT)
    val saveSyncEnabled by container.settingsRepository.saveSyncEnabled
        .collectAsState(initial = true)
    val displayMode by container.settingsRepository.launcherDefaultDisplay
        .collectAsState(initial = LauncherDisplayMode.CARD.id)

    var selectedRomId by remember { mutableStateOf<Int?>(null) }
    var pendingDelete by remember { mutableStateOf<ShelfItem?>(null) }
    var platformFilter by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // ── Juegos locales (fuera de RomM) ──
    var pendingAddPath by remember { mutableStateOf<String?>(null) }
    var coverTargetId by remember { mutableStateOf<String?>(null) }
    var scrapingId by remember { mutableStateOf<String?>(null) }
    val knownPlatforms = remember { runCatching { container.platformDao.getAllPlatformsBlocking() }.getOrDefault(emptyList()) }
    val platformChoices = remember(knownPlatforms) {
        knownPlatforms.map { it.slug to it.name }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
    val pickGame = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val path = com.davidadrianrg.atril.core.localgames.LocalGamePaths.fromPickedUri(uri.toString())
            if (path != null) pendingAddPath = path else scope.launch { snackbar.showSnackbar(tr("local.pick_failed")) }
        }
    }
    val pickCover = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        val id = coverTargetId
        if (uri != null && id != null) {
            context.contentResolver.openInputStream(uri)?.let { viewModel.setLocalCover(id, it) }
        }
    }

    // "Localizar archivo" para un juego manual desde el detalle o el snackbar.
    var locateTarget by remember { mutableStateOf<ShelfItem?>(null) }
    val locateFilePicker = rememberLocateFilePicker(
        onPicked = { uri, overwrite ->
            val item = locateTarget
            if (item == null) {
                LocateOutcome.InvalidFile
            } else {
                viewModel.locateFile(item, uri, overwrite).also { outcome ->
                    if (outcome == LocateOutcome.Linked) locateTarget = null
                }
            }
        },
        onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
    )

    val platformsInShelf = remember(items) {
        items.map { it.rom.platformSlug to it.platformLabel }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }
    LaunchedEffect(platformsInShelf) {
        if (platformFilter != null && platformsInShelf.none { it.first == platformFilter }) {
            platformFilter = null
        }
    }
    val visibleItems = items.filter {
        platformFilter == null || it.rom.platformSlug == platformFilter
    }
    val pagerState = rememberPagerState(pageCount = { visibleItems.size })
    // Juego elegido desde «Seguir jugando»: se lleva el carrusel a su página.
    var pendingSelectId by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(platformFilter) {
        // Un cambio de filtro vuelve al principio, salvo que la selección
        // venga de «Seguir jugando» (entonces manda esa página).
        if (pendingSelectId == null) pagerState.scrollToPage(0)
    }
    LaunchedEffect(pendingSelectId, visibleItems) {
        val id = pendingSelectId ?: return@LaunchedEffect
        val index = visibleItems.indexOfFirst { it.rom.id == id }
        if (index >= 0) {
            pagerState.scrollToPage(index)
            pendingSelectId = null
        }
    }
    val current = visibleItems.getOrNull(pagerState.currentPage)

    // Juegos jugados recientemente, con su tiempo jugado; vacío = fila oculta.
    val continueGames = remember(items, playStats) {
        PlayStatsLogic.recent(items.map { it.groupRomIds + it.rom.id }, playStats)
            .mapNotNull { aggregate -> items.firstOrNull { it.rom.id in aggregate.groupIds } }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg -> snackbar.showSnackbar(msg) }
    }

    // ── Jugar: preparar → (animación de inserción) → lanzar ─────────────
    var launchJob by remember { mutableStateOf<Job?>(null) }
    var insertion by remember { mutableStateOf<Insertion?>(null) }
    val insertProgress = remember { Animatable(0f) }
    var liftingRomId by remember { mutableStateOf<Int?>(null) }
    val cardLift = remember { Animatable(0f) }

    // Los avisos van en su propia corrutina: si colgaran del job de
    // lanzamiento, el botón Jugar quedaría "ocupado" mientras se muestran.
    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    /** Avisa de que falta el fichero y ofrece abrir el selector sin reiniciar. */
    fun notifyLocate(item: ShelfItem, message: String) {
        scope.launch {
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = tr("library.locate_file"),
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                locateTarget = item
                locateFilePicker()
            }
        }
    }

    suspend fun launchWithInsertion(item: ShelfItem, plan: GameLaunchPlan) {
        // Medio físico o carátula: siempre se ve la inserción completa y solo
        // después se lanza el emulador o el instalador (el overlay sigue hasta
        // enviar el intent).
        val physical: Any? = if (item.showPhysical) {
            item.physicalMediaFile?.let { java.io.File(it) } ?: item.rom.physicalMediaUrl
        } else {
            null
        }
        val media: Any? = physical ?: item.rom.coverUrlLarge ?: item.rom.coverUrlSmall
        insertion = Insertion(item, media)
        insertProgress.snapTo(0f)
        insertProgress.animateTo(
            1f,
            tween(com.davidadrianrg.atril.core.launch.InsertionMotion.DURATION_MS, easing = LinearEasing),
        )
        val error = viewModel.startLaunch(context, plan)
        if (error != null) {
            notify(error)
        } else {
            // Lanzamiento aceptado: la partida empieza a contar.
            viewModel.onGameLaunched(item)
        }
    }

    fun play(item: ShelfItem) {
        if (launchJob?.isActive == true) return
        launchJob = scope.launch {
            try {
                when (val plan = viewModel.prepareLaunch(item)) {
                    is GameLaunchPlan.Failed -> {
                        if (plan.needsLocate && !item.isLocalSource) {
                            notifyLocate(item, plan.message)
                        } else {
                            notify(plan.message)
                        }
                    }
                    is GameLaunchPlan.Ready -> {
                        plan.plan.fallbackFrom?.let { preferred ->
                            notify(tr("launcher.fallback", preferred.displayName, plan.plan.family.displayName))
                        }
                        // Medio físico o carátula: siempre se ve la inserción completa y solo
                        // después se lanza el emulador (el overlay sigue hasta enviar el intent).
                        launchWithInsertion(item, plan)
                    }
                    is GameLaunchPlan.Apk -> launchWithInsertion(item, plan)
                    is GameLaunchPlan.InstalledApp -> launchWithInsertion(item, plan)
                }
            } finally {
                // Nunca dejar un overlay invisible bloqueando la pantalla.
                insertion = null
                liftingRomId = null
                cardLift.snapTo(0f)
            }
        }
    }

    // Start / Menu del mando abre o cierra el menú del launcher.
    LaunchedEffect(Unit) {
        com.davidadrianrg.atril.ui.input.PadEvents.menu.collect { if (insertion == null) menuOpen = !menuOpen }
    }
    // Start abre el detalle del juego seleccionado; L1/R1 cambian el filtro de plataforma.
    // `current` y `platformsInShelf` son valores de composición: los colectores de larga vida
    // deben leer la versión actual, no la capturada en el primer frame (lista vacía al cargar).
    val currentNow by androidx.compose.runtime.rememberUpdatedState(current)
    val platformsNow by androidx.compose.runtime.rememberUpdatedState(platformsInShelf)
    LaunchedEffect(Unit) {
        com.davidadrianrg.atril.ui.input.PadEvents.details.collect {
            if (insertion == null && !menuOpen) currentNow?.let { selectedRomId = it.rom.id }
        }
    }
    // X del mando: favorito del juego actual (nunca con la inserción en curso ni el menú abierto).
    LaunchedEffect(Unit) {
        com.davidadrianrg.atril.ui.input.PadEvents.favorite.collect {
            if (insertion == null && !menuOpen) currentNow?.let { viewModel.toggleFavorite(it) }
        }
    }
    LaunchedEffect(Unit) {
        com.davidadrianrg.atril.ui.input.PadEvents.page.collect { dir ->
            if (insertion == null && !menuOpen && selectedRomId == null) {
                platformFilter = com.davidadrianrg.atril.core.input.PlatformCycle.step(
                    platformFilter, platformsNow.map { it.first }, dir,
                )
            }
        }
    }
    // Foco inicial en el carrusel: ←/→ (D-pad, stick o teclado) cambian de juego.
    val carouselFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(visibleItems.isNotEmpty()) { runCatching { carouselFocus.requestFocus() } }

    // Fondo del launcher: captura difuminada (por defecto) o degradado sólido del tema.
    val launcherBg by container.settingsRepository.launcherBackground.collectAsState(initial = "blur")
    val launcherBlur by container.settingsRepository.launcherBlur.collectAsState(initial = 28f)

    BackHandler(enabled = insertion != null) { launchJob?.cancel() }
    BackHandler(enabled = menuOpen && insertion == null) { menuOpen = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(LauncherBgTop, LauncherBgBottom))),
    ) {
        // Fondo cinematográfico: primera captura del juego, si la hay. Es
        // decorativo y va detrás de todo (sin foco ni toques).
        CinematicHero(
            url = if (launcherBg == "solid") null else current?.rom?.heroBackdropUrl(),
            blurRadius = launcherBlur.dp,
            modifier = Modifier.fillMaxSize(),
        )

        // Halo cálido detrás del objeto central.
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(LauncherAmber.copy(alpha = 0.09f), Color.Transparent),
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                // Hueco inferior para la leyenda del mando (anclada abajo en el Box).
                .padding(bottom = if (visibleItems.isNotEmpty()) 40.dp else 0.dp),
        ) {
            LauncherHeader(
                count = visibleItems.size,
                onAddLocal = { pickGame.launch(arrayOf("*/*")) },
                onOpenDownloads = onOpenDownloads,
                onOpenMenu = { menuOpen = true },
                showMenu = menuItems.isNotEmpty(),
                displayMode = displayMode,
                onToggleDisplay = {
                    val next = if (displayMode == LauncherDisplayMode.PHYSICAL.id) {
                        LauncherDisplayMode.CARD.id
                    } else {
                        LauncherDisplayMode.PHYSICAL.id
                    }
                    scope.launch { container.settingsRepository.setLauncherDefaultDisplay(next) }
                },
            )

            if (platformsInShelf.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item {
                        LauncherPill(
                            label = tr("shelf.filter.all"),
                            selected = platformFilter == null,
                            onClick = { platformFilter = null },
                        )
                    }
                    items(platformsInShelf, key = { it.first }) { (slug, label) ->
                        LauncherPill(
                            label = label,
                            selected = platformFilter == slug,
                            onClick = { platformFilter = slug },
                        )
                    }
                }
            }

            ContinuePlayingRow(
                games = continueGames,
                playStats = playStats,
                onSelect = { item ->
                    pendingSelectId = item.rom.id
                    // Si el filtro de plataforma lo deja fuera, se quita para poder centrarlo.
                    if (visibleItems.none { it.rom.id == item.rom.id }) platformFilter = null
                },
            )

            when {
                loading && items.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = LauncherAmber) }

                visibleItems.isEmpty() -> EmptyLauncher(onOpenLibrary, onAddLocal = { pickGame.launch(arrayOf("*/*")) })

                else -> {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        BoxWithConstraints(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            // Sin panel lateral: la página ocupa casi todo el ancho
                            // y solo asoma una franja de los juegos vecinos.
                            val heroWidth = if (landscape) {
                                (maxWidth * 0.53f).coerceAtLeast(maxHeight * 0.55f)
                            } else {
                                (maxWidth * 0.66f).coerceAtMost(maxHeight * 0.62f)
                            }

                            // Ancho de una carta: alto por la proporción 0,70. Con dos
                            // huecos a cada lado siguen cabiendo carátulas completas.
                            val cardWidth = (maxHeight * 0.98f) * 0.70f
                            val slots = 2

                            HorizontalPager(
                                state = pagerState,
                                pageSize = PageSize.Fill,
                                pageSpacing = 0.dp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(carouselFocus)
                                    .focusable()
                                    .onKeyEvent { ev ->
                                        if (ev.type != KeyEventType.KeyDown || insertion != null || menuOpen) return@onKeyEvent false
                                        when (ev.key) {
                                            Key.DirectionLeft -> {
                                                scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) }
                                                true
                                            }
                                            Key.DirectionRight -> {
                                                scope.launch {
                                                    pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(visibleItems.lastIndex))
                                                }
                                                true
                                            }
                                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                                                current?.let { play(it); true } ?: false
                                            else -> false
                                        }
                                    },
                            ) { page ->
                                val selectedItem = visibleItems[page]
                                val lift = if (liftingRomId == selectedItem.rom.id) cardLift.value else 0f
                                val hidden = insertion?.item?.rom?.id == selectedItem.rom.id
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    // Ancho exacto de los 2*slots+1 huecos: aunque supere
                                    // la pantalla, requiredWidth deja que el Box lo centre
                                    // y la carta del medio queda siempre en el centro.
                                    val rowWidth = cardWidth * (slots * 2 + 1) + 10.dp * (slots * 2)
                                    Row(
                                        modifier = Modifier
                                            .requiredWidth(rowWidth)
                                            .fillMaxHeight(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        for (offset in -slots..slots) {
                                            val index = page + offset
                                            val item = visibleItems.getOrNull(index)
                                            Box(
                                                modifier = Modifier
                                                    .width(cardWidth)
                                                    .fillMaxHeight(),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                when {
                                                    item == null -> Unit
                                                    offset == 0 -> LauncherGameObject(
                                                        item = item,
                                                        favorite = playStats.statsOf(item).favorite,
                                                        emphasized = true,
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(vertical = 2.dp)
                                                            .graphicsLayer {
                                                                scaleX = 1f + 0.05f * lift
                                                                scaleY = 1f + 0.05f * lift
                                                                translationY = -10.dp.toPx() * lift
                                                                this.alpha = if (hidden) 0f else 1f
                                                            }
                                                            .focusRing()
                                                            .clickable(
                                                                onClickLabel = tr("launcher.details"),
                                                            ) { selectedRomId = item.rom.id },
                                                    )
                                                    else -> GameCard(
                                                        item = item,
                                                        favorite = playStats.statsOf(item).favorite,
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .graphicsLayer { alpha = 0.75f }
                                                            .clickable {
                                                                scope.launch { pagerState.animateScrollToPage(index) }
                                                            },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Zonas de toque laterales: cambian de página sin depender
                            // de que el toque caiga dentro de la carátula vecina.
                            if (pagerState.currentPage > 0) {
                                Box(
                                    Modifier
                                        .align(Alignment.CenterStart)
                                        .fillMaxHeight()
                                        .fillMaxWidth(0.16f)
                                        .clickable {
                                            scope.launch {
                                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                            }
                                        },
                                )
                            }
                            if (pagerState.currentPage < visibleItems.lastIndex) {
                                Box(
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                        .fillMaxWidth(0.16f)
                                        .clickable {
                                            scope.launch {
                                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                            }
                                        },
                                )
                            }

                            if (!landscape) {
                                LauncherArrow(
                                    icon = Icons.Filled.ChevronLeft,
                                    description = tr("common.previous"),
                                    enabled = pagerState.currentPage > 0,
                                    modifier = Modifier.align(Alignment.CenterStart),
                                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                                )
                                LauncherArrow(
                                    icon = Icons.Filled.ChevronRight,
                                    description = tr("common.next"),
                                    enabled = pagerState.currentPage < visibleItems.lastIndex,
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                                )
                            }
                        }
                    }

                    if (!landscape) {
                        current?.let { item ->
                            Caption(
                                item, viewModel, saveSyncEnabled, launchJob?.isActive == true,
                                stats = playStats.statsOf(item),
                                alignStart = false,
                                onPlay = { play(item) },
                                onDetails = { selectedRomId = item.rom.id },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }

        // Gesto de borde: arrastrar desde la izquierda abre el menú.
        if (menuItems.isNotEmpty() && !menuOpen && insertion == null) {
            EdgeSwipeZone(
                onOpen = { menuOpen = true },
                // Justo después de la zona del gesto Atrás del sistema; en
                // vertical, en la parte baja (las flechas ocupan el centro).
                modifier = Modifier
                    .align(if (landscape) Alignment.CenterStart else Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Start))
                    .fillMaxHeight(if (landscape) 0.7f else 0.3f),
            )
        }

        // Leyenda del mando: por debajo del snackbar y de los overlays (se
        // declaran después, así que se dibujan encima) y sin capturar toques.
        if (visibleItems.isNotEmpty()) {
            PadLegendBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 6.dp),
                onPlay = { current?.let { play(it) } },
                onBack = {
                    when {
                        insertion != null -> launchJob?.cancel()
                        menuOpen -> menuOpen = false
                        selectedRomId != null -> selectedRomId = null
                    }
                },
                onFavorite = { current?.let { viewModel.toggleFavorite(it) } },
                onDetails = { current?.let { selectedRomId = it.rom.id } },
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = Color(0xFF2A241C),
                contentColor = LauncherCream,
                actionColor = LauncherAmber,
                shape = RoundedCornerShape(14.dp),
            )
        }

        insertion?.let { ins ->
            InsertionOverlay(
                title = ins.item.rom.name,
                isOptical = ins.item.isOptical,
                model = ins.model,
                progress = insertProgress.value,
                onCancel = { launchJob?.cancel() },
            )
        }

        LauncherMenuOverlay(
            visible = menuOpen,
            items = menuItems,
            landscape = landscape,
            onNavigate = { screen ->
                menuOpen = false
                if (screen != Screen.Play) onNavigate(screen)
            },
            onDismiss = { menuOpen = false },
            currentRoute = Screen.Play.route,
        )
    }

    // ── Detalle del juego ──────────────────────────────────────────────
    val selected = visibleItems.firstOrNull { it.rom.id == selectedRomId }
    selected?.takeIf { it.isLocalSource }?.let { item ->
        LocalGameSheet(
            item = item,
            scraping = scrapingId == item.localGame?.id,
            stats = playStats.statsOf(item),
            onToggleFavorite = { viewModel.toggleFavorite(item) },
            onPlay = { selectedRomId = null; play(item) },
            onRename = { viewModel.renameLocal(item.localGame!!.id, it) },
            onRetryScrape = {
                val id = item.localGame!!.id
                scrapingId = id
                viewModel.scrapeLocal(id)
                scope.launch { kotlinx.coroutines.delay(1500); if (scrapingId == id) scrapingId = null }
            },
            onPickCover = { coverTargetId = item.localGame!!.id; pickCover.launch("image/*") },
            onRemove = { selectedRomId = null; viewModel.removeLocal(item) },
            onClose = { selectedRomId = null },
            onSelectDisc = { choiceId ->
                item.localDiscGroup?.let { viewModel.setLocalDiscChoice(it.key, choiceId) }
            },
        )
    }
    selected?.takeIf { !it.isLocalSource }?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { selectedRomId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            sheetMaxWidth = 1600.dp,
            // Let the scrolling content run to the screen edge; the sheet
            // body pads its own bottom for the gesture bar (no dead band).
            // Keep the top inset so the drag handle never sits under the
            // status bar when the sheet is fully expanded in landscape.
            contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top) },
        ) {
            RomDetailSheet(
                rom = item.rom,
                platformName = item.platformLabel,
                isDownloaded = true,
                isDownloading = false,
                discCount = item.discCount,
                stats = playStats.statsOf(item),
                onToggleFavorite = { viewModel.toggleFavorite(item) },
                syncEnabled = saveSyncEnabled,
                savesPathOverride = item.savesPathOverride,
                excludedFromSync = item.excludedFromSync,
                platformSavesPath = item.platformSavesPath,
                onSavesPathOverrideChange = { path ->
                    viewModel.setSavesPathOverride(item.rom.id, path)
                },
                onExcludedFromSyncChange = { excluded ->
                    viewModel.setExcludedFromSync(item.rom.id, excluded)
                },
                onDownload = {},
                onDelete = { pendingDelete = item },
                onClose = { selectedRomId = null },
                serverUrl = settings.serverUrl,
                apiKey = settings.apiKey,
                isManualDownloaded = item.isManualDownloaded,
                wide = landscape,
                onLocateFile = if (item.isManualDownloaded) {
                    {
                        locateTarget = item
                        locateFilePicker()
                    }
                } else {
                    null
                },
                launcherControls = rememberLauncherDetailControls(
                    rom = item.rom,
                    platformLabel = item.platformLabel,
                    platformEmulatorId = item.platformEmulatorId,
                    // Manual sin ruta: se resuelve al pulsar Jugar.
                    launchable = item.launchable || item.isManualDownloaded,
                    onPlay = {
                        selectedRomId = null
                        play(item)
                    },
                ),
                coverStyle = viewPrefs.coverStyle,
            )
        }
    }

    // ── Alta de un juego local tras elegir el fichero ──
    pendingAddPath?.let { path ->
        AddLocalGameDialog(
            path = path,
            platforms = platformChoices,
            initialSlug = com.davidadrianrg.atril.core.localgames.LocalGamePaths
                .guessPlatform(path, platformChoices.map { it.first }),
            onConfirm = { title, slug ->
                pendingAddPath = null
                viewModel.addLocalGame(title, slug, path)
            },
            onDismiss = { pendingAddPath = null },
        )
    }

    // ── Confirmación de borrado ────────────────────────────────────────
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr("game.delete_download")) },
            text = {
                Text(
                    if (item.discCount > 1) {
                        tr("android.library.delete_confirm_multi", item.rom.name, item.discCount.toString())
                    } else {
                        tr("android.library.delete_confirm_single", item.rom.name)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item)
                    pendingDelete = null
                    selectedRomId = null
                }) { Text(tr("common.delete")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(tr("common.cancel")) }
            },
        )
    }
}

@Composable
private fun Caption(
    item: ShelfItem,
    viewModel: ShelfViewModel,
    syncEnabled: Boolean,
    busy: Boolean,
    stats: GamePlayStats,
    alignStart: Boolean,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // El nombre del emulador consulta PackageManager: fuera del hilo principal.
    val emulatorName by produceState<String?>(null, item.rom.id, item.launchOverride, item.platformEmulatorId) {
        value = withContext(Dispatchers.IO) { viewModel.currentEmulatorName(item) }
    }
    // Línea principal del héroe: año, géneros, plataforma y nota (formateador compartido).
    val meta = listOfNotNull(item.rom.metadataLine(item.platformLabel))
    // Resto de información (tamaño, discos, emulador, aviso de sync) en una línea menor.
    val secondaryMeta = buildList {
        if (meta.isEmpty()) add(item.platformLabel)
        if (item.fileSizeBytes >= 1024) add(formatBytesForUi(item.fileSizeBytes))
        if (item.discCount > 1) add(tr("launcher.badge.discs", item.discCount))
        emulatorName?.let { add(it) }
        // Los juegos locales no se sincronizan con RomM: no tiene sentido decirlo.
        if (syncEnabled && item.excludedFromSync && !item.isLocalSource) add(tr("play.excluded"))
    }
    val lastPlayed = stats.lastPlayedAt?.let { PlayTimeFormat.relativeDate(it, System.currentTimeMillis()) }
    LauncherCaption(
        item = item,
        meta = meta,
        lastPlayed = lastPlayed,
        onPlay = onPlay,
        onDetails = onDetails,
        playBusy = busy,
        secondaryMeta = secondaryMeta,
        alignStart = alignStart,
        modifier = modifier,
    )
}

@Composable
private fun LauncherHeader(
    count: Int,
    onAddLocal: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenMenu: () -> Unit,
    showMenu: Boolean,
    displayMode: String,
    onToggleDisplay: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showMenu) {
            RoundIconButton(Icons.Filled.Menu, tr("launcher.menu.open"), onOpenMenu)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            AppMark()
            Text(
                tr("nav.play"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = LauncherCream,
            )
        }
        if (count > 0) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = LauncherInk,
                modifier = Modifier
                    .semantics { contentDescription = tr("launcher.games_count", count) }
                    .clip(RoundedCornerShape(50))
                    .background(LauncherAmber)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        RoundIconButton(
            icon = if (displayMode == LauncherDisplayMode.PHYSICAL.id) {
                Icons.Filled.VideogameAsset
            } else {
                Icons.Filled.GridView
            },
            description = tr("launcher.display.toggle"),
            onClick = onToggleDisplay,
        )
        Spacer(Modifier.width(8.dp))
        RoundIconButton(Icons.Filled.Add, tr("local.add"), onAddLocal)
        Spacer(Modifier.width(8.dp))
        com.davidadrianrg.atril.ui.components.DownloadQueueButton(
            onClick = onOpenDownloads,
            tint = LauncherCream,
            modifier = Modifier.clip(CircleShape).background(LauncherCream.copy(alpha = 0.08f)),
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .clip(CircleShape)
            .background(LauncherCream.copy(alpha = 0.08f)),
    ) {
        Icon(icon, contentDescription = description, tint = LauncherCream)
    }
}

@Composable
private fun EmptyLauncher(onOpenLibrary: () -> Unit, onAddLocal: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.SportsEsports,
                contentDescription = null,
                tint = LauncherAmber.copy(alpha = 0.8f),
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                tr("play.empty"),
                style = MaterialTheme.typography.titleMedium,
                color = LauncherCream,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                tr("play.empty_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = LauncherCream.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            androidx.compose.material3.FilledTonalButton(onClick = onOpenLibrary) {
                Text(tr("play.open_library"))
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = onAddLocal) { Text(tr("local.add")) }
        }
    }
}

/** Franja invisible en el borde izquierdo: arrastrar hacia dentro abre el menú. */
@Composable
private fun EdgeSwipeZone(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .width(20.dp)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    dragged = (dragged + delta).coerceAtLeast(0f)
                    if (dragged > threshold) {
                        dragged = 0f
                        onOpen()
                    }
                },
                onDragStopped = { dragged = 0f },
            ),
    )
}

@Composable
private fun LauncherArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .padding(horizontal = 6.dp)
            .clip(CircleShape)
            .background(LauncherCream.copy(alpha = if (enabled) 0.08f else 0.02f)),
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = LauncherCream.copy(alpha = if (enabled) 0.9f else 0.2f),
        )
    }
}

/** Desvanece los bordes laterales del contenido (máscara DstIn sobre capa offscreen). */
private fun Modifier.fadeEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.045f to Color.Black,
                0.955f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
