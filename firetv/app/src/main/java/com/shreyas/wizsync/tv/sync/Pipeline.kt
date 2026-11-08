package com.shreyas.wizsync.tv.sync

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** One update for a bulb: an RGB colour (0..255) plus a dimming level (10..100). */
data class BulbCommand(val r: Int, val g: Int, val b: Int, val dimming: Int)

/** The "warm white" colour used by the stop action. */
val WARM_WHITE = BulbCommand(255, 214, 170, 80)

private val NEAR_BLACK_FALLBACK_WHITE: Rgb = floatArrayOf(255f, 255f, 255f)

/**
 * Colour state for one screen zone: extraction -> saturation -> smoothing -> lead prediction -> bulb values.
 * Port of _Pipeline in app/engine.py. [clock] returns nanoseconds and is injectable for tests.
 */
class Pipeline(val clock: () -> Long = System::nanoTime) {
    private var smoothed: Rgb? = null
    private var vel: Rgb = floatArrayOf(0f, 0f, 0f)
    private var prevCenter: Rgb? = null
    private var prevTime = 0L
    private var lastSent: BulbCommand? = null

    /**
     * Returns the command to send, or null when the dead-band says the bulbs are already close enough.
     * [brightness] is the preset's slider value: the min brightness for FOLLOW, the fixed level for FIXED.
     */
    fun step(frame: Frame, preset: Preset, brightness: Int): BulbCommand? {
        val source = if (preset.ignoreBlackBars) ColorMath.cropBlackBars(frame) else frame
        val raw = ColorMath.boostSaturation(preset.mode.extract(source), preset.saturation)
        val s = ColorMath.smooth(smoothed, raw, preset.smoothing.pow(10f / preset.rate))
        smoothed = s

        val now = clock()
        val prevC = prevCenter
        if (prevC != null && now > prevTime) {
            val dt = (now - prevTime) / 1e9f
            val inst = FloatArray(3) { (s[it] - prevC[it]) / dt }
            vel = ColorMath.smooth(vel, inst, 0.6f) // damp noise in the velocity estimate
        }
        prevCenter = s
        prevTime = now

        val leadSeconds = preset.leadMs / 1000f
        val target = FloatArray(3) { (s[it] + vel[it] * leadSeconds).coerceIn(0f, 255f) }
        val level = ColorMath.brightnessOf(target)

        val rgb: Rgb = if (level < 0.02f) {
            // near-black frame: keep the hue, let brightness fall
            lastSent?.let { floatArrayOf(it.r.toFloat(), it.g.toFloat(), it.b.toFloat()) } ?: NEAR_BLACK_FALLBACK_WHITE
        } else {
            FloatArray(3) { target[it] / level } // normalise; brightness goes via dimming
        }

        val dimFloat = if (preset.brightnessMode == BrightnessMode.FIXED) {
            brightness.toFloat()
        } else {
            brightness + (100 - brightness) * level
        }
        val cmd = BulbCommand(
            r = rgb[0].roundToInt(),
            g = rgb[1].roundToInt(),
            b = rgb[2].roundToInt(),
            dimming = dimFloat.roundToInt().coerceIn(10, 100),
        )

        val last = lastSent
        if (last != null &&
            maxOf(abs(cmd.r - last.r), abs(cmd.g - last.g), abs(cmd.b - last.b)) <= 2 &&
            abs(cmd.dimming - last.dimming) <= 1
        ) {
            return null
        }
        lastSent = cmd
        return cmd
    }
}
