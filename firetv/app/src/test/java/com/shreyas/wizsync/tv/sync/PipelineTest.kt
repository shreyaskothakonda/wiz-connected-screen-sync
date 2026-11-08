package com.shreyas.wizsync.tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private fun solid(r: Int, g: Int, b: Int): Frame =
    Frame(4, 4, IntArray(16) { (r shl 16) or (g shl 8) or b })

class PipelineTest {
    @Test
    fun `presets match engine py`() {
        assertEquals(listOf("instant", "movie", "gaming", "ambient", "party"), Presets.ALL.map { it.id })
        assertEquals("Instant", Presets.INSTANT.label)
        assertEquals(Mode.AVERAGE, Presets.INSTANT.mode)
        assertEquals(0f, Presets.INSTANT.smoothing)
        assertEquals(1.3f, Presets.INSTANT.saturation)
        assertEquals(BrightnessMode.FOLLOW, Presets.INSTANT.brightnessMode)
        assertEquals(100, Presets.INSTANT.defaultBrightness)
        assertEquals(0, Presets.INSTANT.leadMs)
        assertEquals(30f, Presets.INSTANT.rate)
        assertTrue(Presets.INSTANT.ignoreBlackBars)

        assertEquals(Mode.AVERAGE, Presets.MOVIE.mode)
        assertEquals(0.45f, Presets.MOVIE.smoothing)
        assertEquals(1.4f, Presets.MOVIE.saturation)
        assertEquals(BrightnessMode.FOLLOW, Presets.MOVIE.brightnessMode)
        assertEquals(15, Presets.MOVIE.defaultBrightness)
        assertEquals(80, Presets.MOVIE.leadMs)
        assertEquals(20f, Presets.MOVIE.rate)
        assertTrue(Presets.MOVIE.ignoreBlackBars)

        assertEquals(Mode.DOMINANT, Presets.GAMING.mode)
        assertEquals(0.05f, Presets.GAMING.smoothing)
        assertEquals(20, Presets.GAMING.defaultBrightness)
        assertFalse(Presets.GAMING.ignoreBlackBars)

        assertEquals(BrightnessMode.FIXED, Presets.AMBIENT.brightnessMode)
        assertEquals(60, Presets.AMBIENT.defaultBrightness)
        assertEquals(0, Presets.AMBIENT.leadMs)
        assertEquals(10f, Presets.AMBIENT.rate)

        assertEquals(Mode.SATURATED, Presets.PARTY.mode)
        assertEquals(2.5f, Presets.PARTY.saturation)
        assertEquals(100, Presets.PARTY.defaultBrightness)
        assertEquals(0f, Presets.PARTY.smoothing)
    }

    @Test
    fun `solid red with no smoothing sends red at full dimming in fixed mode`() {
        val p = Pipeline()
        val c = p.step(solid(255, 0, 0), Presets.PARTY.copy(saturation = 1f), 100)!!
        assertEquals(255, c.r)
        assertEquals(0, c.g)
        assertEquals(100, c.dimming)
    }

    @Test
    fun `follow mode maps level onto min brightness range`() {
        val c = Pipeline().step(solid(128, 128, 128), Presets.MOVIE.copy(smoothing = 0f, saturation = 1f, leadMs = 0), 20)!!
        assertTrue(abs(c.dimming - 60) <= 1) // 20 + 80 * 128/255 ~= 60.2
    }

    @Test
    fun `dead band suppresses tiny changes`() {
        val p = Pipeline()
        val pr = Presets.PARTY.copy(saturation = 1f, leadMs = 0)
        assertNotNull(p.step(solid(200, 100, 50), pr, 100))
        assertNull(p.step(solid(201, 101, 51), pr, 100))
    }

    @Test
    fun `near black keeps last hue and drops dimming`() {
        val p = Pipeline()
        val pr = Presets.MOVIE.copy(smoothing = 0f, saturation = 1f, leadMs = 0)
        p.step(solid(0, 0, 255), pr, 10)
        val c = p.step(solid(1, 1, 1), pr, 10)!!
        assertEquals(255, c.b)
        assertEquals(10, c.dimming)
    }

    @Test
    fun `dimming is always clamped 10 to 100`() {
        for (b in listOf(10, 55, 100)) {
            val c = Pipeline().step(solid(255, 255, 255), Presets.AMBIENT, b)!!
            assertTrue(c.dimming in 10..100)
        }
    }
}
