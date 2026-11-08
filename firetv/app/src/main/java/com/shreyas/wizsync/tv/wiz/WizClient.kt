package com.shreyas.wizsync.tv.wiz

import com.shreyas.wizsync.tv.sync.BulbCommand
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class Discovered(val ip: String, val mac: String)

/**
 * Minimal WiZ local-UDP client. [send] and [setState] are fire-and-forget on one reused socket;
 * [discover], [ping] and [identify] block the calling thread. Nothing here throws on network errors.
 */
class WizClient(val port: Int = 38899) : Closeable {
    private val socket = DatagramSocket()
    @Volatile private var closed = false
    private var pingId = 0

    fun send(ip: String, cmd: BulbCommand) = transmit(ip, WizJson.setPilot(cmd))

    fun setState(ip: String, on: Boolean) = transmit(ip, WizJson.setState(on))

    private fun transmit(ip: String, json: String) {
        if (closed) return
        try {
            val data = json.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), port))
        } catch (e: IOException) {
            // fire-and-forget: the next frame supersedes this one
        }
    }

    /** Broadcasts getPilot twice, `timeoutMs / 2` apart, and returns one entry per replying IP. */
    fun discover(broadcast: String = "255.255.255.255", timeoutMs: Long = 2000): List<Discovered> {
        if (closed) return emptyList()
        val found = LinkedHashMap<String, Discovered>()
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                val data = WizJson.getPilot.toByteArray(Charsets.UTF_8)
                val target = InetAddress.getByName(broadcast)
                val start = System.nanoTime()
                for (round in 1..2) {
                    s.send(DatagramPacket(data, data.size, target, port))
                    val roundEnd = start + timeoutMs * 1_000_000L * round / 2
                    collect(s, roundEnd) { ip, reply ->
                        WizJson.parseMac(reply)?.let { found.putIfAbsent(ip, Discovered(ip, it)) }
                    }
                }
            }
        } catch (e: IOException) {
            // return whatever was collected
        }
        return found.values.toList()
    }

    /**
     * True when [ip] answers a getPilot with a MAC and the request's id within [timeoutMs]; returns on the first
     * such reply. Stale or unrelated packets (other IPs, other ids) are ignored.
     */
    @Synchronized
    fun ping(ip: String, timeoutMs: Long = 500): Boolean {
        if (closed) return false
        return try {
            drain()
            val id = ++pingId
            val data = WizJson.getPilot(id).toByteArray(Charsets.UTF_8)
            val target = InetAddress.getByName(ip)
            socket.send(DatagramPacket(data, data.size, target, port))
            collect(socket, System.nanoTime() + timeoutMs * 1_000_000L) { from, reply ->
                if (from == target.hostAddress && WizJson.parseId(reply) == id && WizJson.parseMac(reply) != null) return true
            }
            false
        } catch (e: IOException) {
            false
        }
    }

    /** Discards packets already queued on the main socket (replies to earlier setPilot/ping calls). */
    private fun drain() {
        val p = DatagramPacket(ByteArray(2048), 2048)
        socket.soTimeout = 1
        try {
            while (true) socket.receive(p)
        } catch (e: SocketTimeoutException) {
            // queue is empty
        }
    }

    /** White blink: 100 -> 20 -> 100 with 300 ms gaps. Does not restore the previous colour. */
    fun identify(ip: String) {
        try {
            send(ip, BulbCommand(255, 255, 255, 100))
            Thread.sleep(300)
            send(ip, BulbCommand(255, 255, 255, 20))
            Thread.sleep(300)
            send(ip, BulbCommand(255, 255, 255, 100))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        closed = true
        socket.close()
    }

    /** Reads datagrams from [s] until [deadlineNanos], passing each datagram (source IP, text) to [onReply]. */
    private inline fun collect(s: DatagramSocket, deadlineNanos: Long, onReply: (ip: String, reply: String) -> Unit) {
        val buf = ByteArray(2048)
        while (true) {
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) return
            val p = DatagramPacket(buf, buf.size)
            try {
                s.soTimeout = remainingMs.toInt()
                s.receive(p)
            } catch (e: SocketTimeoutException) {
                return
            }
            onReply(p.address.hostAddress ?: continue, String(p.data, 0, p.length, Charsets.UTF_8))
        }
    }
}
