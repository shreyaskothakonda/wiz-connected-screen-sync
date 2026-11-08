package com.shreyas.wizsync.tv.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun solid(r: Int, g: Int, b: Int, h: Int = 9, w: Int = 16) =
    Frame(w, h, IntArray(w * h) { (r shl 16) or (g shl 8) or b })

private fun Frame.fill(x0: Int, y0: Int, x1: Int, y1: Int, r: Int, g: Int, b: Int) {
    for (y in y0 until y1) for (x in x0 until x1) px[y * width + x] = (r shl 16) or (g shl 8) or b
}

class ColorMathTest {
    @Test
    fun `average of halves`() {
        val f = solid(0, 0, 0)
        f.fill(8, 0, 16, 9, 200, 100, 0)
        assertArrayEquals(floatArrayOf(100f, 50f, 0f), ColorMath.average(f), 0.01f)
    }

    @Test
    fun `dominant ignores black and prefers saturated`() {
        val f = solid(0, 0, 0)
        f.fill(0, 0, 4, 2, 250, 10, 10)
        f.fill(0, 4, 16, 9, 120, 120, 120)
        val c = ColorMath.dominant(f)
        assertTrue(c[0] > 200 && c[1] < 40)
    }

    @Test
    fun `dominant all black`() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), ColorMath.dominant(solid(0, 0, 0)), 0f)
    }

    @Test
    fun smoothing() {
        assertArrayEquals(floatArrayOf(10f, 10f, 10f), ColorMath.smooth(null, floatArrayOf(10f, 10f, 10f), 0.5f), 0f)
        assertArrayEquals(
            floatArrayOf(25f, 25f, 25f),
            ColorMath.smooth(floatArrayOf(0f, 0f, 0f), floatArrayOf(100f, 100f, 100f), 0.75f),
            0.001f,
        )
    }

    @Test
    fun `saturation boost keeps grey`() {
        assertArrayEquals(
            floatArrayOf(128f, 128f, 128f),
            ColorMath.boostSaturation(floatArrayOf(128f, 128f, 128f), 2f),
            0.01f,
        )
    }

    @Test
    fun `brightest and saturated pick different colours`() {
        val f = solid(30, 30, 30)
        f.fill(0, 0, 16, 3, 240, 240, 235)
        f.fill(0, 3, 4, 5, 200, 0, 20)
        val br = ColorMath.brightest(f)
        val sat = ColorMath.saturated(f)
        assertTrue(br.min() > 200)
        assertTrue(sat[0] > 150 && sat[1] < 60)
    }

    @Test
    fun `all extractors handle black`() {
        for (m in Mode.entries) assertEquals(0f, m.extract(solid(0, 0, 0)).max(), 0f)
    }

    @Test
    fun `crop black bars trims letterbox`() {
        val f = solid(0, 0, 0, h = 10, w = 10)
        f.fill(0, 2, 10, 8, 200, 200, 200)
        val c = ColorMath.cropBlackBars(f)
        assertEquals(10, c.width)
        assertEquals(6, c.height)
    }

    @Test
    fun `distance is chebyshev, the largest channel difference`() {
        assertEquals(4f, ColorMath.distance(floatArrayOf(0f, 0f, 0f), floatArrayOf(3f, 4f, 0f)), 0f)
    }

    @Test
    fun `crop black bars keeps full frame when almost all dark`() {
        val f = solid(0, 0, 0, h = 10, w = 10)
        f.fill(0, 0, 1, 1, 200, 200, 200)
        val c = ColorMath.cropBlackBars(f)
        assertEquals(10, c.height)
    }
}
