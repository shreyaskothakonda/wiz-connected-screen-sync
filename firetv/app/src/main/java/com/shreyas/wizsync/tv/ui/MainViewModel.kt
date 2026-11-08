package com.shreyas.wizsync.tv.ui

import com.shreyas.wizsync.tv.store.Settings
import com.shreyas.wizsync.tv.sync.BrightnessMode
import com.shreyas.wizsync.tv.sync.Presets
import com.shreyas.wizsync.tv.sync.SyncState
import kotlin.math.roundToInt

/** Presentation logic for the main screen. Android-free so it can be unit tested. */
class MainViewModel(val settings: Settings) {

    val canStart: Boolean get() = settings.enabledBulbs().isNotEmpty()

    val statusText: (SyncState) -> String = { state ->
        when (state) {
            is SyncState.Ready -> if (canStart) "Ready" else "Tick at least one bulb"
            is SyncState.Syncing -> "Syncing · ${state.fps.roundToInt()} fps"
            is SyncState.PausedNoPicture -> "Paused · no picture"
            is SyncState.Error -> state.message
        }
    }

    fun startLabel(state: SyncState): String =
        if (state is SyncState.Ready || state is SyncState.Error) "Start" else "Stop"

    fun brightnessTitle(): String =
        if (Presets.byId(settings.presetId).brightnessMode == BrightnessMode.FOLLOW) "Min brightness" else "Brightness"

    fun brightnessPercent(): Int = settings.brightnessFor(settings.presetId)

    fun brightnessLabel(): String = "${brightnessTitle()} ${brightnessPercent()}%"

    /** Moves the current preset's brightness by [delta] steps of [STEP]; [Settings] clamps to 10..100. */
    fun adjustBrightness(delta: Int) {
        val id = settings.presetId
        settings.setBrightness(id, settings.brightnessFor(id) + delta * STEP)
    }

    companion object {
        const val STEP = 5
    }
}
