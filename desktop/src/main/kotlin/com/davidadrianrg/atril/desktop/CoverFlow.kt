package com.davidadrianrg.atril.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.core.library.PlatformAbbreviation
import kotlin.math.abs
import kotlinx.coroutines.launch

/** Distancia al final de la lista a la que se pide el siguiente lote visible. */
private const val NEAR_END_DISTANCE = 8

/** Proporción ancho/alto de la carátula (3:4, la misma de la rejilla). */
private const val COVER_FLOW_ASPECT_RATIO = 0.75f

/** Altura del reflejo como fracción de la altura de la carátula. */
private const val REFLECTION_FRACTION = 0.26f

/**
 * Carrusel 3D (cover flow) de la Biblioteca de escritorio.
 *
 * La tarjeta centrada se dibuja grande y las vecinas se rotan sobre el eje Y,
 * se escalan y se atenúan con `graphicsLayer`, con un reflejo inferior que
 * reutiliza el MISMO [ImageBitmap] que la carátula (una sola descarga y una
 * sola decodificación a través de [produceCover]/[CoverCache]).
 *
 * Igual que en Android recibe la lista ya filtrada y ordenada y avisa mediante
 * callbacks; la carga por lotes de 60 la controla la pantalla, que sube
 * `visibleCount` cuando el carrusel se acerca al final.
 *
 * @param cards tarjetas visibles (ya recortadas por la pantalla).
 * @param pagerState estado del pager; lo posee la pantalla para poder saltar.
 * @param cardScale multiplicador de tamaño elegido en las opciones de vista.
 * @param serverUrl servidor RomM para resolver las rutas de portada.
 * @param focusRequester foco del carrusel, para la entrega de foco explícita.
 * @param isFavorite si la tarjeta (su grupo completo) es favorita.
 * @param isDownloading si la tarjeta tiene una descarga en curso.
 * @param onOpen abre el detalle del juego.
 * @param onEnqueue encola la descarga de una tarjeta sin descargar.
 * @param onLoadMore amplía el lote visible al acercarse al final.
 */
@Composable
fun DesktopCoverFlow(
    cards: List<GameCard>,
    pagerState: PagerState,
    cardScale: Float,
    serverUrl: String,
    focusRequester: FocusRequester,
    isFavorite: (GameCard) -> Boolean,
    isDownloading: (GameCard) -> Boolean,
    onOpen: (GameCard) -> Unit,
    onEnqueue: (GameCard) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    val scope = rememberCoroutineScope()

    // El carrusel toma el foco al entrar para que flechas, Enter y el mando
    // funcionen sin tocar la pantalla primero.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // Lote incremental: mismo criterio que la rejilla (a 8 del final).
    LaunchedEffect(pagerState.currentPage, cards.size) {
        if (cards.isNotEmpty() && pagerState.currentPage >= cards.size - NEAR_END_DISTANCE) {
            onLoadMore()
        }
    }

    // Si la lista se acorta (filtro, búsqueda, refresco), la página actual no
    // puede quedar fuera de rango.
    LaunchedEffect(cards.size) {
        if (pagerState.currentPage > cards.lastIndex) pagerState.scrollToPage(cards.lastIndex)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val baseWidth = minOf(maxWidth * 0.46f, maxHeight * 0.53f).coerceAtLeast(110.dp)
        val cardWidth = baseWidth * cardScale
        val cardHeight = cardWidth / COVER_FLOW_ASPECT_RATIO
        val reflectionHeight = cardHeight * REFLECTION_FRACTION

        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(cardWidth),
            contentPadding = PaddingValues(
                horizontal = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp),
            ),
            pageSpacing = 16.dp,
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .focusHighlight(RoundedCornerShape(16.dp))
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionLeft -> {
                            scope.launch {
                                pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    (pagerState.currentPage + 1).coerceAtMost(cards.lastIndex),
                                )
                            }
                            true
                        }
                        // Enter / A del mando: abre el detalle, como la tarjeta de
                        // la rejilla. Escape sigue cerrando el detalle y el panel
                        // en el mismo orden que hoy (D3).
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            cards.getOrNull(pagerState.currentPage)?.let {
                                onOpen(it)
                                true
                            } ?: false
                        }
                        else -> false
                    }
                },
        ) { page ->
            val card = cards[page]
            val selected = page == pagerState.currentPage
            DesktopCoverFlowPage(
                card = card,
                page = page,
                pagerState = pagerState,
                cardWidth = cardWidth,
                cardHeight = cardHeight,
                reflectionHeight = reflectionHeight,
                serverUrl = serverUrl,
                favorite = isFavorite(card),
                downloading = isDownloading(card),
                onOpen = onOpen,
                onEnqueue = onEnqueue,
                onSelect = { scope.launch { pagerState.animateScrollToPage(page) } },
                selected = selected,
            )
        }
    }
}

/**
 * Una página del carrusel: carátula, adornos y reflejo.
 *
 * La transformación 3D se calcula dentro del bloque `graphicsLayer`, que corre
 * en la fase de dibujo: desplazar el carrusel solo redibuja la capa, no
 * recompone cada página.
 */
@Composable
private fun DesktopCoverFlowPage(
    card: GameCard,
    page: Int,
    pagerState: PagerState,
    cardWidth: Dp,
    cardHeight: Dp,
    reflectionHeight: Dp,
    serverUrl: String,
    favorite: Boolean,
    downloading: Boolean,
    onOpen: (GameCard) -> Unit,
    onEnqueue: (GameCard) -> Unit,
    onSelect: () -> Unit,
    selected: Boolean,
) {
    val url = resolveMediaUrl(
        card.rep.urlCover,
        card.rep.pathCoverSmall ?: card.rep.pathCoverLarge,
        serverUrl,
    )
    // Una sola decodificación por tarjeta: el reflejo reutiliza el bitmap.
    val bitmap = if (url != null) produceCover(url) else null

    Column(
        modifier = Modifier
            .width(cardWidth)
            .graphicsLayer {
                val offset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                    .coerceIn(-2f, 2f)
                val distance = abs(offset)
                rotationY = (offset * 34f).coerceIn(-55f, 55f)
                val pageScale = 1f - distance.coerceAtMost(1f) * 0.18f
                scaleX = pageScale
                scaleY = pageScale
                alpha = (1f - distance * 0.45f).coerceIn(0.15f, 1f)
                cameraDistance = 14f * density
            }
            .clickable { if (selected) onOpen(card) else onSelect() },
    ) {
        DesktopCoverArtwork(
            card = card,
            bitmap = bitmap,
            favorite = favorite,
            downloading = downloading,
            width = cardWidth,
            height = cardHeight,
            onEnqueue = onEnqueue,
        )
        DesktopCoverReflection(
            bitmap = bitmap,
            width = cardWidth,
            coverHeight = cardHeight,
            reflectionHeight = reflectionHeight,
        )
    }
}

/**
 * Carátula con los mismos adornos que la tarjeta de la rejilla: placeholder de
 * plataforma, degradado inferior, título, tamaño y badges de favorito, discos,
 * descargado y descarga en curso.
 */
@Composable
private fun DesktopCoverArtwork(
    card: GameCard,
    bitmap: ImageBitmap?,
    favorite: Boolean,
    downloading: Boolean,
    width: Dp,
    height: Dp,
    onEnqueue: (GameCard) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = PlatformAbbreviation.of(card.rep.platformSlug.orEmpty()),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                maxLines = 1,
            )
        }

        // Scrim degradado inferior para legibilidad del título.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(10.dp),
        ) {
            Text(
                card.rep.name,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.rep.fileSizeBytes >= 1024) {
                Text(
                    formatBytes(card.rep.fileSizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }

        if (card.discCount > 1 || favorite) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (favorite) {
                    Icon(
                        AppIcons.Star,
                        contentDescription = tr("launcher.badge.favorite"),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                }
                if (card.discCount > 1) {
                    Icon(
                        AppIcons.Album,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        tr("library.card.discs", card.discCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }

        when {
            card.downloaded -> Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.CheckCircle,
                    contentDescription = tr("common.downloaded"),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
            downloading -> Unit
            else -> Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(30.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable { onEnqueue(card) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Download,
                    contentDescription = tr("common.download"),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (downloading) {
            Box(
                Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(30.dp), color = Color.White)
            }
        }
    }
}

/**
 * Reflejo inferior de la carátula.
 *
 * Reutiliza el bitmap de la carátula: se dibuja una segunda vez con
 * `scaleY = -1` (volteado sobre su borde inferior) y se recorta a
 * [reflectionHeight]. Un degradado del color de fondo lo funde hacia abajo, así
 * que no hay segunda descarga ni segunda decodificación.
 */
@Composable
private fun DesktopCoverReflection(
    bitmap: ImageBitmap?,
    width: Dp,
    coverHeight: Dp,
    reflectionHeight: Dp,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(reflectionHeight)
            .clipToBounds(),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(width)
                    // Altura completa de la carátula aunque el hueco del reflejo
                    // sea más bajo: requiredHeight ignora las restricciones.
                    .requiredHeight(coverHeight)
                    .offset(y = -coverHeight)
                    .graphicsLayer {
                        scaleY = -1f
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        alpha = 0.35f
                    },
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.background.copy(alpha = 0.25f),
                        0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
        )
    }
}
