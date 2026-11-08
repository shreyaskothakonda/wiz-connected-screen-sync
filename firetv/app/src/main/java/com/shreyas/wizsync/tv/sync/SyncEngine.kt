package com.shreyas.wizsync.tv.sync

import com.shreyas.wizsync.tv.store.Settings
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

sealed class SyncState {
    object Ready : SyncState()
    data class Syncing(val fps: Float, val color: BulbCommand) : SyncState()
    object PausedNoPicture : SyncState()
    data class Error(val message: String) : SyncState()
}

/**
 * The capture -> colour -> bulbs loop, free of Android APIs. Drive [tick] directly in tests, or [runLoop] in a service.
 * Port of Engine._loop in app/engine.py, plus black-picture fallback and bulb health checks.
 */
class SyncEngine(
    val source: FrameSource,
    val sink: BulbSink,
    val settings: Settings,
    val clock: () -> Long = System::nanoTime,
    val sleep: (Long) -> Unit = Thread::sleep,
) {
    @Volatile var state: SyncState = SyncState.Ready
        private set(value) {
            field = value
            onState?.invoke(value)
        }
    @Volatile var onState: ((SyncState) -> Unit)? = null

    private val unhealthySet: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val unhealthy: Set<String> = unhealthySet

    /** IPs that must receive no sends (used while identifying a bulb). */
    val muted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val pipeline = Pipeline(clock)
    private val lastSentByIp = HashMap<String, BulbCommand>()
    private var lastPipelineCmd: BulbCommand? = null
    private var previousEnabled: Set<String> = emptySet()

    private var blackSinceNs = -1L
    private var pausedAtNs = 0L
    private var fadeDone = false
    private var lastTickNs = 0L
    private val fadeFrom = HashMap<String, BulbCommand>()

    private val sendCounts = HashMap<String, Int>()
    private val missStreak = HashMap<String, Int>()
    private val lastPingNs = HashMap<String, Long>()

    private var fpsWindowStartNs = -1L
    private var framesInWindow = 0
    private var fps = 0f

    /** Set by [applyOff]; once latched, ticks do nothing and [runLoop] does not rest the bulbs. */
    @Volatile private var off = false

    fun runLoop(stop: () -> Boolean) {
        try {
            state = SyncState.Syncing(0f, lastPipelineCmd ?: WARM_WHITE)
            while (!stop()) {
                val t0 = clock()
                tick(t0)
                val elapsedMs = (clock() - t0) / 1_000_000
                val periodMs = (1000f / Presets.byId(settings.presetId).rate).toLong()
                if (periodMs > elapsedMs) sleep(periodMs - elapsedMs)
            }
        } finally {
            applyResting()
            state = SyncState.Ready
        }
    }

    /** Sends [WARM_WHITE] to every enabled, unmuted bulb. Does not change [state]. */
    @Synchronized
    fun applyResting() {
        if (off) return
        for (ip in enabledIps()) if (ip !in muted) sendTo(ip, WARM_WHITE)
    }

    /**
     * Call when something outside the engine has changed this bulb's colour (e.g. Identify) so the next tick
     * re-sends the sync colour.
     */
    @Synchronized
    fun forget(ip: String) {
        lastSentByIp.remove(ip)
    }

    /**
     * Hands a bulb back after something outside the engine (Identify) changed its colour: off if the engine has
     * latched off, warm white when there is no sync colour to return to, otherwise the next tick re-sends the sync colour.
     */
    @Synchronized
    fun restore(ip: String) {
        when {
            off -> sink.setState(ip, false)
            state == SyncState.PausedNoPicture || ip !in enabledIps() || lastPipelineCmd == null ->
                sendTo(ip, WARM_WHITE)
            else -> forget(ip)
        }
    }

    /** Turns every enabled bulb off, muted or not, and latches: later ticks and the loop's exit send nothing. */
    @Synchronized
    fun applyOff() {
        off = true
        for (ip in enabledIps()) sink.setState(ip, false)
    }

    @Synchronized
    fun tick(nowNs: Long) {
        if (off) return
        val frame = source.latest() ?: return
        lastTickNs = nowNs
        val preset = Presets.byId(settings.presetId)
        val enabled = enabledIps()
        restDisabled(enabled)
        countFrame(nowNs)

        val avgMax = ColorMath.brightnessOf(ColorMath.average(frame)) * 255f
        if (avgMax < BLACK_BELOW) {
            if (blackSinceNs < 0) blackSinceNs = nowNs
            if (state == SyncState.PausedNoPicture) {
                fadeToResting(enabled, nowNs)
                pingIfDue(enabled, nowNs)
                return
            }
            if (nowNs - blackSinceNs >= BLACK_HOLD_NS) {
                enterPaused(enabled, nowNs)
                return
            }
        } else {
            blackSinceNs = -1L
        }

        val cmd = pipeline.step(frame, preset, settings.brightnessFor(preset.id))
        if (cmd != null) lastPipelineCmd = cmd
        for (ip in enabled) {
            if (ip in muted) continue
            // the pipeline's dead-band only knows its own history; re-sync any bulb that shows something else
            val toSend = cmd ?: lastPipelineCmd?.takeIf { lastSentByIp[ip] != it }
            if (toSend != null) sendTo(ip, toSend)
        }
        pingIfDue(enabled, nowNs)
        val shown = lastPipelineCmd ?: WARM_WHITE
        val current = state
        if (current !is SyncState.Syncing || current.fps != fps || current.color != shown) {
            state = SyncState.Syncing(fps, shown)
        }
    }

    private fun enabledIps(): List<String> = settings.enabledBulbs().map { it.ip }

    /** A bulb that was ticked last time and is not now gets one [WARM_WHITE]. */
    private fun restDisabled(enabled: List<String>) {
        val now = enabled.toSet()
        for (ip in previousEnabled - now) {
            if (ip !in muted) sink.send(ip, WARM_WHITE)
            lastSentByIp.remove(ip)
            unhealthySet.remove(ip)
        }
        previousEnabled = now
    }

    private fun countFrame(nowNs: Long) {
        if (fpsWindowStartNs < 0) fpsWindowStartNs = nowNs
        framesInWindow++
        val elapsed = nowNs - fpsWindowStartNs
        if (elapsed >= ONE_SECOND_NS) {
            fps = framesInWindow * 1e9f / elapsed
            framesInWindow = 0
            fpsWindowStartNs = nowNs
        }
    }

    private fun enterPaused(enabled: List<String>, nowNs: Long) {
        pausedAtNs = nowNs
        fadeDone = false
        fadeFrom.clear()
        fadeFrom.putAll(lastSentByIp)
        for (ip in enabled) lastPingNs[ip] = nowNs
        state = SyncState.PausedNoPicture
    }

    /** At most one interpolated command per tick toward [WARM_WHITE] over [FADE_NS], then exactly [WARM_WHITE], then silence. */
    private fun fadeToResting(enabled: List<String>, nowNs: Long) {
        if (fadeDone) return
        val f = minOf(1f, (nowNs - pausedAtNs).toFloat() / FADE_NS)
        for (ip in enabled) {
            if (ip in muted) continue
            val from = fadeFrom[ip]
            if (from == null) {
                if (ip !in lastSentByIp) sendTo(ip, WARM_WHITE) // nothing to fade from: rest once
            } else if (f > 0f) {
                sendTo(ip, if (f >= 1f) WARM_WHITE else lerp(from, WARM_WHITE, f))
            }
        }
        if (f >= 1f) fadeDone = true
    }

    private fun lerp(a: BulbCommand, b: BulbCommand, f: Float) = BulbCommand(
        r = (a.r + (b.r - a.r) * f).roundToInt(),
        g = (a.g + (b.g - a.g) * f).roundToInt(),
        b = (a.b + (b.b - a.b) * f).roundToInt(),
        dimming = (a.dimming + (b.dimming - a.dimming) * f).roundToInt(),
    )

    /** Pings any bulb not pinged for [PING_EVERY_NS], whatever the send count. */
    private fun pingIfDue(enabled: List<String>, nowNs: Long) {
        for (ip in enabled) {
            if (nowNs - lastPingNs.getOrPut(ip) { nowNs } >= PING_EVERY_NS) {
                lastPingNs[ip] = nowNs
                recordPing(ip)
            }
        }
    }

    private fun sendTo(ip: String, cmd: BulbCommand) {
        sink.send(ip, cmd)
        lastSentByIp[ip] = cmd
        val n = (sendCounts[ip] ?: 0) + 1
        sendCounts[ip] = n
        if (n % PING_EVERY_SENDS == 0) {
            lastPingNs[ip] = lastTickNs
            recordPing(ip)
        }
    }

    /** Two consecutive misses mark the bulb unhealthy; any reply clears it. */
    private fun recordPing(ip: String) {
        if (sink.ping(ip)) {
            missStreak[ip] = 0
            unhealthySet.remove(ip)
        } else {
            val misses = (missStreak[ip] ?: 0) + 1
            missStreak[ip] = misses
            if (misses >= 2) unhealthySet.add(ip)
        }
    }

    private companion object {
        const val BLACK_BELOW = 8f
        const val ONE_SECOND_NS = 1_000_000_000L
        const val BLACK_HOLD_NS = ONE_SECOND_NS
        const val PING_EVERY_NS = 3_000_000_000L
        const val FADE_NS = 500_000_000f
        const val PING_EVERY_SENDS = 60
    }
}
