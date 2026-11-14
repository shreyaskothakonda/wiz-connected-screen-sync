package com.shreyas.wizsync.tv.wiz

import com.shreyas.wizsync.tv.store.MapStore
import com.shreyas.wizsync.tv.store.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BulbRefinderTest {
    private fun settings() = Settings(MapStore()).apply {
        upsertFromDiscovery(listOf(Discovered("192.168.1.5", "cc40855d5492"), Discovered("192.168.1.7", "a8bb50003f2a")))
        rename("cc40855d5492", "Lamp")
        setEnabled("a8bb50003f2a", false)
    }

    @Test fun `known bulb at a new address gets its ip updated and keeps name and tick`() {
        val s = settings()
        val moved = BulbRefinder(s, { listOf(Discovered("192.168.1.10", "CC40855D5492")) }).refindNow()
        assertEquals(1, moved)
        assertEquals("192.168.1.10", s.bulbs[0].ip)
        assertEquals("Lamp", s.bulbs[0].name)
        assertEquals("192.168.1.7", s.bulbs[1].ip) // not seen: left alone
        assertFalse(s.bulbs[1].enabled)
    }

    @Test fun `unknown bulbs are never added and unchanged addresses count as nothing`() {
        val s = settings()
        val moved = BulbRefinder(s, {
            listOf(Discovered("192.168.1.5", "cc40855d5492"), Discovered("192.168.1.99", "ffffffffffff"))
        }).refindNow()
        assertEquals(0, moved)
        assertEquals(2, s.bulbs.size)
    }

    @Test fun `two bulbs that swapped addresses are both corrected`() {
        val s = settings()
        val moved = BulbRefinder(s, {
            listOf(Discovered("192.168.1.7", "cc40855d5492"), Discovered("192.168.1.5", "a8bb50003f2a"))
        }).refindNow()
        assertEquals(2, moved)
        assertEquals(listOf("192.168.1.7", "192.168.1.5"), s.bulbs.map { it.ip })
    }

    @Test fun `a failing discovery changes nothing`() {
        val s = settings()
        assertEquals(0, BulbRefinder(s, { throw IllegalStateException("no wifi") }).refindNow())
        assertEquals("192.168.1.5", s.bulbs[0].ip)
    }
}
