package com.davidadrianrg.atril.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Album
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.core.library.CoverStyle
import com.davidadrianrg.atril.core.library.PlatformAbbreviation
import com.davidadrianrg.atril.domain.model.DownloadStatus
import com.davidadrianrg.atril.domain.model.RomWithStatus
import com.davidadrianrg.atril.ui.input.focusRing
import kotlin.math.abs
import kotlinx.coroutines.launch

/** Distancia al final de la lista a la que se pide la página siguiente. */
private const val NEAR_END_DISTANCE = 8

/** Proporción ancho/alto de la carátula (3:4, la misma de la rejilla). */
private const val CARD_ASPECT_RATIO = 0.75f

/** Altura del reflejo como fracción de la altura de la carátula. */
private const val REFLECTION_FRACTION = 0.26f

/**
 * Vista de carrusel 3D (cover flow) de la Biblioteca.
 *
 * La tarjeta centrada se dibuja grande y las vecinas se rotan sobre el eje Y,
 * se escalan y se atenúan progresivamente con `graphicsLayer`, de modo que el
 * gesto y el mando siempre tienen una referencia visual clara. Debajo de cada
 * carátula se pinta un reflejo sutil que **reutiliza el mismo painter**: la
 * misma petición de imagen de Coil se dibuja dos veces (una con `scaleY = -1`),
 * nunca se decodifica ni se descarga dos veces.
 *
 * Es una vista pasiva: recibe la lista ya filtrada y ordenada, la misma que
 * pinta la rejilla, y avisa al llamador mediante callbacks. La carga
 * incremental se dispara cuando la página seleccionada se acerca al final.
 *
 * @param items tarjetas ya filtradas y ordenadas (misma lista que la rejilla).
 * @param pagerState estado del pager; lo posee la pantalla para poder saltar.
 * @param cardScale multiplicador de tamaño elegido en las opciones de vista.
 * @param coverStyle estilo de carátula elegido en las opciones de vista.
 * @param focusRequester foco del carrusel, para la entrega de foco explícita.
 * @param isFavorite si la tarjeta (su grupo completo) es favorita.
 * @param onOpenDetail abre el detalle del juego centrado.
 * @param onDownload encola la descarga de una tarjeta sin descargar.
 * @param onLoadMore pide la página siguiente cuando se llega al final.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryCoverFlow(
    items: List<RomWithStatus>,
    pagerState: PagerState,
    cardScale: Float,
    coverStyle: CoverStyle,
    focusRequester: FocusRequester,
    isFavorite: (RomWithStatus) -> Boolean,
    onOpenDetail: (RomWithStatus) -> Unit,
    onDownload: (RomWithStatus) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val scope = rememberCoroutineScope()

    // El carrusel toma el foco al entrar (p. ej. al cambiar de vista) para que
    // el mando y las flechas funcionen sin tocar la pantalla primero.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // Carga incremental: mismo criterio que la rejilla (a 8 del final), con las
    // guardas isLoadingMore/hasMore dentro del ViewModel.
    LaunchedEffect(pagerState.currentPage, items.size) {
        if (items.isNotEmpty() && pagerState.currentPage >= items.size - NEAR_END_DISTANCE) {
            onLoadMore()
        }
    }

    // Si la lista se acorta (filtro, búsqueda, refresco), la página actual no
    // puede quedar fuera de rango.
    LaunchedEffect(items.size) {
        if (pagerState.currentPage > items.lastIndex) pagerState.scrollToPage(items.lastIndex)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // La tarjeta ocupa como mucho la mitad del ancho y deja sitio al reflejo
        // y a la etiqueta inferior en pantallas bajas (portátiles compactos).
        val baseWidth = minOf(maxWidth * 0.52f, maxHeight * 0.53f).coerceAtLeast(96.dp)
        val cardWidth = baseWidth * cardScale
        val cardHeight = cardWidth / CARD_ASPECT_RATIO
        val reflectionHeight = cardHeight * REFLECTION_FRACTION

        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(cardWidth),
            contentPadding = PaddingValues(
                horizontal = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp),
            ),
            pageSpacing = 12.dp,
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
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
                                    (pagerState.currentPage + 1).coerceAtMost(items.lastIndex),
                                )
                            }
                            true
                        }
                        // A del mando (DPAD_CENTER) / Enter: abre el detalle, igual
                        // que la tarjeta del escritorio (D7).
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            items.getOrNull(pagerState.currentPage)?.let {
                                onOpenDetail(it)
                                true
                            } ?: false
                        }
                        else -> false
                    }
                },
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            val selected = page == pagerState.currentPage
            CoverFlowPage(
                item = item,
                page = page,
                pagerState = pagerState,
                cardWidth = cardWidth,
                cardHeight = cardHeight,
                reflectionHeight = reflectionHeight,
                coverStyle = coverStyle,
                favorite = isFavorite(item),
                onOpenDetail = onOpenDetail,
                onDownload = onDownload,
                onSelect = { scope.launch { pagerState.animateScrollToPage(page) } },
                selected = selected,
            )
        }
    }
}

/**
 * Una página del carrusel: carátula, adornos y reflejo.
 *
 * La transformación 3D se calcula dentro del bloque `graphicsLayer`, que se
 * ejecuta en la fase de dibujo: desplazar el carrusel no recomposicióna cada
 * página, solo redibuja la capa.
 */
@Composable
private fun CoverFlowPage(
    item: RomWithStatus,
    page: Int,
    pagerState: PagerState,
    cardWidth: Dp,
    cardHeight: Dp,
    reflectionHeight: Dp,
    coverStyle: CoverStyle,
    favorite: Boolean,
    onOpenDetail: (RomWithStatus) -> Unit,
    onDownload: (RomWithStatus) -> Unit,
    onSelect: () -> Unit,
    selected: Boolean,
) {
    val context = LocalContext.current
    val coverUrl = item.rom.coverUrlFor(coverStyle)
    // Una sola petición por tarjeta: el reflejo reutiliza este painter.
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(coverUrl)
            .crossfade(true)
            .build(),
    )

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
            .focusRing(RoundedCornerShape(14.dp))
            .clickable { if (selected) onOpenDetail(item) else onSelect() },
    ) {
        CoverArtwork(
            item = item,
            painter = painter,
            favorite = favorite,
            width = cardWidth,
            height = cardHeight,
            onDownload = onDownload,
        )
        CoverReflection(
            painter = painter,
            width = cardWidth,
            coverHeight = cardHeight,
            reflectionHeight = reflectionHeight,
        )
    }
}

/**
 * Carátula con los mismos adornos que la tarjeta de la rejilla: placeholder de
 * plataforma, degradado inferior, título y badges de favorito, discos y estado.
 */
@Composable
private fun CoverArtwork(
    item: RomWithStatus,
    painter: coil.compose.AsyncImagePainter,
    favorite: Boolean,
    width: Dp,
    height: Dp,
    onDownload: (RomWithStatus) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (painter.state is coil.compose.AsyncImagePainter.State.Loading ||
            painter.state is coil.compose.AsyncImagePainter.State.Error
        ) {
            Text(
                text = PlatformAbbreviation.of(item.rom.platformSlug),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                maxLines = 1,
            )
        }
        Image(
            painter = painter,
            contentDescription = item.rom.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        // Scrim degradado inferior para legibilidad del título.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )

        Text(
            text = item.rom.name,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )

        if (item.status == DownloadStatus.DOWNLOADED) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .background(MaterialTheme.colorScheme.secondary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = tr("common.downloaded"),
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (item.discCount > 1 || favorite) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(
                        MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (favorite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = tr("launcher.badge.favorite"),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                }
                if (item.discCount > 1) {
                    Icon(
                        Icons.Outlined.Album,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        tr("library.card.discs", item.discCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }

        if (item.status == DownloadStatus.NOT_DOWNLOADED) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.65f), CircleShape)
                    .clickable { onDownload(item) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = tr("common.download"),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (item.status == DownloadStatus.DOWNLOADING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    strokeWidth = 3.dp,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * Reflejo inferior de la carátula.
 *
 * Reutiliza el painter de la carátula: la imagen se dibuja una segunda vez con
 * `scaleY = -1` (volteada sobre su borde inferior, que coincide con el borde
 * inferior de la carátula) y se recorta a [reflectionHeight]. Un degradado
 * vertical del color de fondo la funde hacia abajo, así que el reflejo es
 * sutil y barato: no hay segunda descarga ni segunda decodificación.
 */
@Composable
private fun CoverReflection(
    painter: coil.compose.AsyncImagePainter,
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
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(width)
                // Altura completa de la carátula aunque el hueco del reflejo sea
                // más bajo: requiredHeight ignora las restricciones del padre.
                .requiredHeight(coverHeight)
                .offset(y = -coverHeight)
                .graphicsLayer {
                    scaleY = -1f
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    alpha = 0.35f
                },
        )
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
