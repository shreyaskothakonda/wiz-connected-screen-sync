package com.shreyas.wizsync.tv.wiz

import android.util.Log
import com.shreyas.wizsync.tv.sync.BulbCommand
import com.shreyas.wizsync.tv.sync.BulbSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** [BulbSink] over [WizClient]. [ping] never blocks: it returns the last result and refreshes it in the background. */
class WizSink(private val client: WizClient) : BulbSink {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "wizsync-ping").apply { isDaemon = true } }
    private val lastResult = ConcurrentHashMap<String, Boolean>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun send(ip: String, cmd: BulbCommand) = client.send(ip, cmd)

    override fun setState(ip: String, on: Boolean) = client.setState(ip, on)

    override fun ping(ip: String): Boolean {
        if (inFlight.add(ip)) {
            try {
                executor.execute {
                    try {
                        val ok = client.ping(ip, 500)
                        if (lastResult.put(ip, ok) != ok) Log.i(TAG, "ping $ip -> $ok")
                    } finally {
                        inFlight.remove(ip)
                    }
                }
            } catch (e: RejectedExecutionException) {
                inFlight.remove(ip) // already shut down
            }
        }
        return lastResult[ip] ?: true
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "WIZSYNC"
    }
}
