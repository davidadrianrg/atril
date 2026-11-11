package com.davidadrianrg.atril.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.FileInputStream
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Longest side of a hero backdrop; the decoded source is never painted at full size. */
private const val DEFAULT_BACKDROP_DIMENSION = 128

/** Maximum number of downscaled backdrops kept in memory. */
private const val BACKDROP_CACHE_ENTRIES = 8

/**
 * Size of the downscaled backdrop, preserving the aspect ratio.
 *
 * The longest side is capped at [maxDimension]; an already smaller image is
 * not upscaled. Invalid dimensions (zero or negative) yield 1x1, never zero.
 */
internal fun backdropTargetSize(
    width: Int,
    height: Int,
    maxDimension: Int = DEFAULT_BACKDROP_DIMENSION,
): IntSize {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return IntSize(1, 1)
    val longestSide = maxOf(width, height)
    if (longestSide <= maxDimension) return IntSize(width, height)
    val scale = maxDimension.toFloat() / longestSide
    return IntSize(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
    )
}

/**
 * Bounded LRU cache of downscaled backdrops.
 *
 * Keys are `url@maxDimension`; a null value is cached as a marker so a failed
 * decode is not retried on every recomposition. Access order is tracked so the
 * least recently used entry is evicted beyond [BACKDROP_CACHE_ENTRIES].
 */
internal object BackdropCache {
    private val entries = object : LinkedHashMap<String, ImageBitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>): Boolean =
            size > BACKDROP_CACHE_ENTRIES
    }

    /** Cached bitmap for [key]; check [contains] to tell "missing" from "failed". */
    @Synchronized
    fun get(key: String): ImageBitmap? = entries[key]

    /** True when [key] was already resolved (even if the decode failed). */
    @Synchronized
    fun contains(key: String): Boolean = entries.containsKey(key)

    /** Stores [bitmap] (or a null marker) for [key], refreshing its recency. */
    @Synchronized
    fun put(key: String, bitmap: ImageBitmap?) {
        entries[key] = bitmap
    }

    /** Number of cached keys (including cached decode failures). */
    @Synchronized
    fun size(): Int = entries.size

    /** Symmetric clear for tests and future cache resets. */
    @Synchronized
    fun clear() {
        entries.clear()
    }
}

/**
 * Downscaled backdrop for [url], or null when it cannot be loaded.
 *
 * Fetches through [coverSourceFile] (disk cache for http(s), direct file for
 * `file://`), decodes on [Dispatchers.IO], draws into a bitmap of
 * [backdropTargetSize] with [FilterQuality.Low] and releases the full-size
 * source. Results are memoized in [BackdropCache]; decoding/IO failures resolve
 * to null and never escape into composition, cancellation is always rethrown.
 */
@Composable
internal fun produceBackdrop(url: String, maxDimension: Int = DEFAULT_BACKDROP_DIMENSION): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url, maxDimension) {
        val key = backdropKey(url, maxDimension)
        if (BackdropCache.contains(key)) {
            value = BackdropCache.get(key)
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val decoded = try {
                decodeBackdrop(url, maxDimension)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (io: IOException) {
                null
            } catch (runtime: RuntimeException) {
                null
            }
            BackdropCache.put(key, decoded)
            decoded
        }
    }
    return bitmap
}

/** Decodes [url] and returns a Low-filtered bitmap capped at [maxDimension]. */
private fun decodeBackdrop(url: String, maxDimension: Int): ImageBitmap? {
    val sourceFile = coverSourceFile(url) ?: return null
    val source = FileInputStream(sourceFile).use { loadImageBitmap(it) }
    val target = backdropTargetSize(source.width, source.height, maxDimension)
    val scaled = ImageBitmap(target.width, target.height)
    Canvas(scaled).drawImageRect(
        image = source,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(source.width, source.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(target.width, target.height),
        paint = Paint().apply { filterQuality = FilterQuality.Low },
    )
    return scaled
}

/** Cache key of a URL at a given bound. */
private fun backdropKey(url: String, maxDimension: Int): String = "$url@$maxDimension"
