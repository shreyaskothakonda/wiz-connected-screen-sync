package com.shreyas.wizsync.tv.sync

/** FOLLOW scales brightness with the picture between a floor and 100; FIXED holds one level. */
enum class BrightnessMode { FOLLOW, FIXED }

/**
 * A tuning profile, ported from PRESETS in app/engine.py.
 * [defaultBrightness] is the min brightness for FOLLOW presets and the fixed level for FIXED ones.
 */
data class Preset(
    val id: String,
    val label: String,
    val mode: Mode,
    val smoothing: Float,
    val saturation: Float,
    val brightnessMode: BrightnessMode,
    val defaultBrightness: Int,
    val leadMs: Int,
    val rate: Float,
    val ignoreBlackBars: Boolean,
)

object Presets {
    val INSTANT = Preset(
        id = "instant", label = "Instant", mode = Mode.AVERAGE, smoothing = 0f, saturation = 1.3f,
        brightnessMode = BrightnessMode.FOLLOW, defaultBrightness = 100, leadMs = 0, rate = 30f,
        ignoreBlackBars = true,
    )
    val MOVIE = Preset(
        id = "movie", label = "Movie", mode = Mode.AVERAGE, smoothing = 0.45f, saturation = 1.4f,
        brightnessMode = BrightnessMode.FOLLOW, defaultBrightness = 15, leadMs = 80, rate = 20f,
        ignoreBlackBars = true,
    )
    val GAMING = Preset(
        id = "gaming", label = "Gaming", mode = Mode.DOMINANT, smoothing = 0.05f, saturation = 1.8f,
        brightnessMode = BrightnessMode.FOLLOW, defaultBrightness = 20, leadMs = 120, rate = 30f,
        ignoreBlackBars = false,
    )
    val AMBIENT = Preset(
        id = "ambient", label = "Ambient", mode = Mode.AVERAGE, smoothing = 0.85f, saturation = 1.2f,
        brightnessMode = BrightnessMode.FIXED, defaultBrightness = 60, leadMs = 0, rate = 10f,
        ignoreBlackBars = true,
    )
    val PARTY = Preset(
        id = "party", label = "Party", mode = Mode.SATURATED, smoothing = 0f, saturation = 2.5f,
        brightnessMode = BrightnessMode.FIXED, defaultBrightness = 100, leadMs = 120, rate = 30f,
        ignoreBlackBars = false,
    )

    val ALL: List<Preset> = listOf(INSTANT, MOVIE, GAMING, AMBIENT, PARTY)

    fun byId(id: String): Preset =
        ALL.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown preset: $id")
}
