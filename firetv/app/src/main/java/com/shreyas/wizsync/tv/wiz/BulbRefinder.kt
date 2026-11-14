package com.shreyas.wizsync.tv.wiz

import com.shreyas.wizsync.tv.store.Settings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds known bulbs again after their address changed (router DHCP): runs [discover] and, for every bulb already in
 * [settings] whose MAC answers from a different IP, stores the new IP. Never adds or removes bulbs.
 */
class BulbRefinder(
    private val settings: Settings,
    private val discover: () -> List<Discovered>,
    private val onMoved: (mac: String, from: String, to: String) -> Unit = { _, _, _ -> },
) {
    private val inFlight = AtomicBoolean(false)

    /** Blocks for the length of one discovery. Returns how many bulbs had their address corrected. */
    fun refindNow(): Int {
        val found = try {
            discover()
        } catch (e: Exception) {
            return 0
        }
        val byMac = found.associateBy { it.mac.lowercase() }
        var moved = 0
        for (b in settings.bulbs) {
            val d = byMac[b.mac.lowercase()] ?: continue
            if (d.ip == b.ip) continue
            settings.updateIp(b.mac, d.ip)
            onMoved(b.mac, b.ip, d.ip)
            moved++
        }
        return moved
    }

    /** Non-blocking: starts [refindNow] on a background thread unless one is already running. */
    fun request() {
        if (!inFlight.compareAndSet(false, true)) return
        Thread({
            try {
                refindNow()
            } finally {
                inFlight.set(false)
            }
        }, "wizsync-refind").apply { isDaemon = true }.start()
    }
}
