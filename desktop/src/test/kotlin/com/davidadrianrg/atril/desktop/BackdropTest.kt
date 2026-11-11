package com.davidadrianrg.atril.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parte determinista del fondo cinematográfico de escritorio: tamaño acotado
 * (nunca a tamaño completo, nunca cero) y caché LRU acotada. No se decodifica
 * ninguna imagen real.
 */
class BackdropTest {

    @After
    fun clearCache() {
        BackdropCache.clear()
    }

    @Test
    fun `a wide image is capped on its longest side`() {
        assertEquals(IntSize(128, 72), backdropTargetSize(1920, 1080, maxDimension = 128))
    }

    @Test
    fun `a portrait image is capped on its longest side`() {
        assertEquals(IntSize(72, 128), backdropTargetSize(720, 1280, maxDimension = 128))
    }

    @Test
    fun `an already small image is not upscaled`() {
        assertEquals(IntSize(100, 80), backdropTargetSize(100, 80, maxDimension = 128))
        assertEquals(IntSize(128, 64), backdropTargetSize(128, 64, maxDimension = 128))
    }

    @Test
    fun `the default bound is 128`() {
        assertEquals(IntSize(128, 72), backdropTargetSize(1920, 1080))
    }

    @Test
    fun `invalid dimensions yield 1x1 and never zero`() {
        assertEquals(IntSize(1, 1), backdropTargetSize(0, 500, maxDimension = 128))
        assertEquals(IntSize(1, 1), backdropTargetSize(500, 0, maxDimension = 128))
        assertEquals(IntSize(1, 1), backdropTargetSize(-10, 20, maxDimension = 128))
        assertEquals(IntSize(1, 1), backdropTargetSize(100, 100, maxDimension = 0))
        assertEquals(IntSize(1, 1), backdropTargetSize(100, 100, maxDimension = -1))
    }

    @Test
    fun `the cache evicts the least recently used entry beyond eight`() {
        val keys = (1..8).map { "https://cdn.atril.test/shot-$it.png@128" }
        keys.forEach { BackdropCache.put(it, ImageBitmap(2, 2)) }

        // Touch the first entry so the second becomes the eviction victim.
        BackdropCache.get(keys[0])
        BackdropCache.put("https://cdn.atril.test/shot-9.png@128", ImageBitmap(2, 2))

        assertEquals(8, BackdropCache.size())
        assertTrue(BackdropCache.contains(keys[0]))
        assertFalse(BackdropCache.contains(keys[1]))
        assertTrue(BackdropCache.contains("https://cdn.atril.test/shot-9.png@128"))
    }

    @Test
    fun `the cache overwrites the same key without growing`() {
        val key = "https://cdn.atril.test/shot-1.png@128"
        val first = ImageBitmap(2, 2)
        val second = ImageBitmap(4, 4)

        BackdropCache.put(key, first)
        BackdropCache.put(key, second)

        assertEquals(1, BackdropCache.size())
        assertSame(second, BackdropCache.get(key))
    }

    @Test
    fun `a failed decode is cached as a null marker`() {
        val key = "https://cdn.atril.test/broken.png@128"

        BackdropCache.put(key, null)

        assertTrue(BackdropCache.contains(key))
        assertNull(BackdropCache.get(key))
        assertEquals(1, BackdropCache.size())
    }
}
