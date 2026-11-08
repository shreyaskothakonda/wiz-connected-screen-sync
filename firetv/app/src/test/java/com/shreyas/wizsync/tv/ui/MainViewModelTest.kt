package com.shreyas.wizsync.tv.ui

import com.shreyas.wizsync.tv.store.MapStore
import com.shreyas.wizsync.tv.store.Settings
import com.shreyas.wizsync.tv.sync.SyncState
import com.shreyas.wizsync.tv.sync.WARM_WHITE
import com.shreyas.wizsync.tv.wiz.Discovered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MainViewModelTest {
    @Test fun `cannot start with no ticked bulbs`() {
        val vm = MainViewModel(Settings(MapStore()))
        assertFalse(vm.canStart)
        assertEquals("Tick at least one bulb", vm.statusText(SyncState.Ready))
    }

    @Test fun `status strings`() {
        val s = Settings(MapStore())
        s.upsertFromDiscovery(listOf(Discovered("1.1.1.1", "aa")))
        val vm = MainViewModel(s)
        assertEquals("Ready", vm.statusText(SyncState.Ready))
        assertEquals("Syncing · 25 fps", vm.statusText(SyncState.Syncing(25.2f, WARM_WHITE)))
        assertEquals("Paused · no picture", vm.statusText(SyncState.PausedNoPicture))
        assertEquals("Bulb 1.1.1.1 not responding", vm.statusText(SyncState.Error("Bulb 1.1.1.1 not responding")))
        assertEquals("Start", vm.startLabel(SyncState.Ready))
        assertEquals("Stop", vm.startLabel(SyncState.Syncing(1f, WARM_WHITE)))
        assertEquals("Stop", vm.startLabel(SyncState.PausedNoPicture))
    }

    @Test fun `brightness label follows preset mode and steps by 5`() {
        val s = Settings(MapStore())
        val vm = MainViewModel(s)
        s.setPreset("movie")
        assertEquals("Min brightness 15%", vm.brightnessLabel())
        vm.adjustBrightness(+1)
        assertEquals(20, s.brightnessFor("movie"))
        s.setPreset("ambient")
        assertEquals("Brightness 60%", vm.brightnessLabel())
        vm.adjustBrightness(-1)
        assertEquals(55, s.brightnessFor("ambient"))
    }
}
