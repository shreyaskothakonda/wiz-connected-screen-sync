package com.shreyas.wizsync.tv.sync

import com.shreyas.wizsync.tv.store.MapStore
import com.shreyas.wizsync.tv.store.Settings
import com.shreyas.wizsync.tv.wiz.Discovered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun solid(r: Int, g: Int, b: Int): Frame =
    Frame(4, 4, IntArray(16) { (r shl 16) or (g shl 8) or b })

private class FakeSource : FrameSource {
    @Volatile var frame: Frame? = null
    override fun latest() = frame
}

private class FakeSink : BulbSink {
    val sent = mutableListOf<Pair<String, BulbCommand>>()
    val states = mutableListOf<Pair<String, Boolean>>()
    var alive = true
    override fun send(ip: String, cmd: BulbCommand) { sent += ip to cmd }
    override fun setState(ip: String, on: Boolean) { states += ip to on }
    override fun ping(ip: String) = alive
}

private fun engine(src: FakeSource, sink: FakeSink, vararg ips: String): Pair<SyncEngine, Settings> {
    val s = Settings(MapStore())
    s.upsertFromDiscovery(ips.mapIndexed { i, ip -> Discovered(ip, "mac$i") })
    s.setPreset("party")
    var now = 0L
    return SyncEngine(src, sink, s, clock = { now }, sleep = { now += it * 1_000_000 }) to s
}

class SyncEngineTest {
    @Test fun `sends colour to every ticked bulb and switches with the frame`() {
        val src = FakeSource(); val sink = FakeSink(); val (e, s) = engine(src, sink, "1.1.1.1", "1.1.1.2")
        s.setEnabled("mac1", false)
        src.frame = solid(255, 0, 0); e.tick(0); src.frame = solid(0, 0, 255); e.tick(100_000_000)
        assertTrue(sink.sent.all { it.first == "1.1.1.1" })
        assertEquals(255, sink.sent.first().second.r)
        assertEquals(255, sink.sent.last().second.b)
    }

    @Test fun `black for one second fades to resting and resumes on picture`() {
        val src = FakeSource(); val sink = FakeSink(); val (e, _) = engine(src, sink, "1.1.1.1")
        src.frame = solid(0, 0, 200); e.tick(0); src.frame = solid(2, 2, 2)
        var t = 0L
        while (t < 1_600_000_000) { t += 50_000_000; e.tick(t) }
        assertEquals(SyncState.PausedNoPicture, e.state)
        assertEquals(WARM_WHITE, sink.sent.last().second)
        src.frame = solid(0, 200, 0); e.tick(t + 50_000_000)
        assertTrue(e.state is SyncState.Syncing)
        assertEquals(255, sink.sent.last().second.g)
        assertEquals(0, sink.sent.last().second.r) // hue normalised, level goes via dimming
    }

    @Test fun `fade takes five steps and then sends nothing more`() {
        val src = FakeSource(); val sink = FakeSink(); val (e, _) = engine(src, sink, "1.1.1.1")
        src.frame = solid(0, 0, 200); e.tick(0); src.frame = solid(1, 1, 1)
        var t = 0L
        while (e.state !is SyncState.PausedNoPicture) { t += 50_000_000; e.tick(t) }
        val before = sink.sent.size
        repeat(10) { t += 50_000_000; e.tick(t) }
        assertEquals(5, sink.sent.size - before)
        assertEquals(WARM_WHITE, sink.sent.last().second)
    }

    @Test fun `applyResting sends warm white and applyOff sends state false`() {
        val sink = FakeSink(); val (e, _) = engine(FakeSource(), sink, "1.1.1.1")
        e.applyResting(); assertEquals(WARM_WHITE, sink.sent.last().second)
        e.applyOff(); assertEquals("1.1.1.1" to false, sink.states.last())
    }

    @Test fun `health marks a bulb unhealthy after two missed pings and clears on reply`() {
        val src = FakeSource(); val sink = FakeSink(); val (e, _) = engine(src, sink, "1.1.1.1"); sink.alive = false
        var t = 0L
        // alternating hues defeat the dead-band (party normalises level into dimming), so every tick sends
        fun run(n: Int) = repeat(n) { i ->
            t += 40_000_000
            src.frame = if (i % 2 == 0) solid(200, 0, 0) else solid(0, 0, 200)
            e.tick(t)
        }
        run(130)
        assertEquals(130, sink.sent.size)
        assertTrue("1.1.1.1" in e.unhealthy)
        sink.alive = true; run(70)
        assertFalse("1.1.1.1" in e.unhealthy)
    }

    @Test fun `muted bulb receives nothing`() {
        val src = FakeSource(); val sink = FakeSink(); val (e, _) = engine(src, sink, "1.1.1.1", "1.1.1.2")
        e.muted += "1.1.1.2"; src.frame = solid(200, 0, 0); e.tick(0)
        assertTrue(sink.sent.isNotEmpty()); assertTrue(sink.sent.none { it.first == "1.1.1.2" })
    }

    @Test fun `same colour returning after a black pause is resent`() {
        val src = FakeSource(); val sink = FakeSink(); val (e, _) = engine(src, sink, "1.1.1.1")
        src.frame = solid(0, 0, 255); e.tick(0)
        src.frame = solid(2, 2, 2)
        var t = 0L
        while (t < 1_600_000_000) { t += 50_000_000; e.tick(t) }
        assertEquals(SyncState.PausedNoPicture, e.state)
        src.frame = solid(0, 0, 255); e.tick(t + 50_000_000)
        val c = sink.sent.last().second
        assertEquals(Triple(0, 0, 255), Triple(c.r, c.g, c.b))
    }

    @Test fun `unmuted bulb is brought back to the current colour`() {
        val src = FakeSource(); val sink = FakeSink(); val (e, _) = engine(src, sink, "1.1.1.1", "1.1.1.2")
        e.muted += "1.1.1.2"; src.frame = solid(255, 0, 0)
        for (i in 0..4) e.tick(i * 50_000_000L)
        assertTrue(sink.sent.none { it.first == "1.1.1.2" })
        e.muted -= "1.1.1.2"; e.tick(300_000_000)
        val c = sink.sent.last { it.first == "1.1.1.2" }.second
        assertEquals(255, c.r); assertEquals(0, c.b)
    }

    @Test fun `applyOff latches so ticks send nothing`() {
        val src = FakeSource(); val sink = FakeSink(); val (e, _) = engine(src, sink, "1.1.1.1")
        src.frame = solid(255, 0, 0); e.tick(0); e.applyOff()
        val sends = sink.sent.size
        src.frame = solid(0, 0, 255)
        for (i in 1..5) e.tick(i * 50_000_000L)
        assertEquals(sends, sink.sent.size)
    }

    @Test fun `runLoop after applyOff does not rest the bulbs`() {
        val src = FakeSource(); val sink = FakeSink(); val (e, _) = engine(src, sink, "1.1.1.1")
        src.frame = solid(0, 200, 0); e.applyOff(); var n = 0
        e.runLoop(stop = { ++n > 3 })
        assertTrue(sink.sent.isEmpty()); assertEquals("1.1.1.1" to false, sink.states.last())
        assertEquals(SyncState.Ready, e.state)
    }

    @Test fun `runLoop stops and applies resting`() {
        val src = FakeSource(); val sink = FakeSink(); val (e, _) = engine(src, sink, "1.1.1.1")
        src.frame = solid(0, 200, 0); var n = 0
        e.runLoop(stop = { ++n > 5 })
        assertEquals(WARM_WHITE, sink.sent.last().second); assertEquals(SyncState.Ready, e.state)
    }
}
