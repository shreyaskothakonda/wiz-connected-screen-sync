package com.shreyas.wizsync.tv.store

import com.shreyas.wizsync.tv.wiz.Discovered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test fun `defaults`() {
        val s = Settings(MapStore())
        assertEquals("movie", s.presetId)
        assertEquals(15, s.brightnessFor("movie"))
        assertEquals(100, s.brightnessFor("party"))
        assertTrue(s.bulbs.isEmpty())
    }

    @Test fun `discovery adds ticked bulbs named by mac tail and dedupes by mac`() {
        val s = Settings(MapStore())
        s.upsertFromDiscovery(listOf(Discovered("10.0.0.5", "a8bb50003f2a")))
        s.upsertFromDiscovery(listOf(Discovered("10.0.0.9", "a8bb50003f2a")))
        assertEquals(1, s.bulbs.size)
        assertEquals("WiZ 3F2A", s.bulbs[0].name)
        assertEquals("10.0.0.9", s.bulbs[0].ip)
        assertTrue(s.bulbs[0].enabled)
    }

    @Test fun `rediscovery keeps rename and tick`() {
        val s = Settings(MapStore())
        s.upsertFromDiscovery(listOf(Discovered("1.1.1.1", "aa")))
        s.rename("aa", "Floor lamp")
        s.setEnabled("aa", false)
        s.upsertFromDiscovery(listOf(Discovered("1.1.1.2", "aa")))
        assertEquals("Floor lamp", s.bulbs[0].name)
        assertFalse(s.bulbs[0].enabled)
    }

    @Test fun `brightness is stored per preset and survives switching`() {
        val s = Settings(MapStore())
        s.setPreset("movie")
        s.setBrightness("movie", 40)
        s.setPreset("party")
        assertEquals(100, s.brightnessFor("party"))
        s.setPreset("movie")
        assertEquals(40, s.brightnessFor("movie"))
    }

    @Test fun `brightness clamps 10 to 100 and persists across instances`() {
        val m = MapStore()
        Settings(m).setBrightness("ambient", 300)
        assertEquals(100, Settings(m).brightnessFor("ambient"))
        Settings(m).setBrightness("ambient", 1)
        assertEquals(10, Settings(m).brightnessFor("ambient"))
    }

    @Test fun `remove and enabledBulbs`() {
        val s = Settings(MapStore())
        s.upsertFromDiscovery(listOf(Discovered("1.1.1.1", "aa"), Discovered("1.1.1.2", "bb")))
        s.setEnabled("bb", false)
        assertEquals(listOf("aa"), s.enabledBulbs().map { it.mac })
        s.remove("aa")
        assertEquals(1, s.bulbs.size)
    }
}
