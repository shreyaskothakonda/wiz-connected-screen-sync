package com.shreyas.wizsync.tv.wiz

import com.shreyas.wizsync.tv.sync.BulbCommand
import org.json.JSONObject
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Fake bulb: a loopback DatagramSocket that records datagrams and answers getPilot with a mac. */
class FakeBulb(val mac: String) : Closeable {
    val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    val received = CopyOnWriteArrayList<String>()
    @Volatile var answer = true

    private val thread = Thread {
        val buf = ByteArray(2048)
        while (!socket.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                socket.receive(p)
            } catch (e: SocketException) {
                break
            }
            val text = String(p.data, 0, p.length, Charsets.UTF_8)
            received.add(text)
            if (answer && text.contains("getPilot")) {
                val reply = """{"method":"getPilot","result":{"mac":"$mac","state":true}}""".toByteArray()
                socket.send(DatagramPacket(reply, reply.size, p.address, p.port))
            }
        }
    }.apply { isDaemon = true; start() }

    override fun close() {
        socket.close()
        thread.join(1000)
    }
}

private fun await(timeoutMs: Long = 2000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return
        Thread.sleep(20)
    }
    fail("condition not met within ${timeoutMs}ms")
}

class WizClientTest {
    @Test fun `setPilot json matches python client`() {
        val j = JSONObject(WizJson.setPilot(BulbCommand(10, 20, 30, 55)))
        assertEquals("setPilot", j.getString("method")); val p = j.getJSONObject("params")
        assertTrue(p.getBoolean("state")); assertEquals(10, p.getInt("r")); assertEquals(30, p.getInt("b")); assertEquals(55, p.getInt("dimming"))
    }

    @Test fun `setPilot clamps dimming`() {
        assertEquals(10, JSONObject(WizJson.setPilot(BulbCommand(0, 0, 0, 3))).getJSONObject("params").getInt("dimming"))
    }

    @Test fun `parseMac reads result mac`() =
        assertEquals("a8bb50123456", WizJson.parseMac("""{"method":"getPilot","result":{"mac":"a8bb50123456","state":true}}"""))

    @Test fun `send delivers setPilot datagram`() {
        FakeBulb("aa").use { b ->
            WizClient(b.socket.localPort).use { it.send("127.0.0.1", BulbCommand(1, 2, 3, 50)) }
            await { b.received.any { it.contains("setPilot") } }
        }
    }

    @Test fun `ping true when bulb answers, false when silent`() {
        FakeBulb("aa").use { b ->
            WizClient(b.socket.localPort).use { c ->
                assertTrue(c.ping("127.0.0.1", 500))
                b.answer = false
                assertFalse(c.ping("127.0.0.1", 300))
            }
        }
    }

    @Test fun `discover collects replies by ip`() {
        FakeBulb("abc123").use { b ->
            WizClient(b.socket.localPort).use { c ->
                val found = c.discover(broadcast = "127.0.0.1", timeoutMs = 600)
                assertEquals(listOf(Discovered("127.0.0.1", "abc123")), found)
            }
        }
    }
}
