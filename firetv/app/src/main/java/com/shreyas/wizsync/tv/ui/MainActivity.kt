package com.shreyas.wizsync.tv.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.shreyas.wizsync.tv.App
import com.shreyas.wizsync.tv.R
import com.shreyas.wizsync.tv.databinding.ActivityMainBinding
import com.shreyas.wizsync.tv.sync.Presets
import com.shreyas.wizsync.tv.sync.SyncService
import com.shreyas.wizsync.tv.sync.SyncState
import com.shreyas.wizsync.tv.wiz.Discovered

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private val vm = MainViewModel(App.settings)
    private val adapter = BulbAdapter(
        onToggle = { b ->
            App.settings.setEnabled(b.mac, b.enabled)
            notice = null
            render()
        },
        onMenu = { b -> menu.show(b) },
    )
    private val menu = BulbMenuDialog(this, App.settings, { binder?.engine }, { refreshBulbs() })
    private val main = Handler(Looper.getMainLooper())

    private var binder: SyncService.LocalBinder? = null
    private var state: SyncState = SyncState.Ready
    private var notice: String? = null
    private var discovering = false

    private val running: Boolean get() = state is SyncState.Syncing || state is SyncState.PausedNoPicture

    private val listener: (SyncState) -> Unit = { s ->
        if (s != state) notice = null
        state = s
        render()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val b = service as SyncService.LocalBinder
            binder = b
            b.addListener(listener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            listener(SyncState.Ready)
        }
    }

    /** Once a second while visible: refresh the warning badges (unhealthy bulbs change independently of state events). */
    private val poll = object : Runnable {
        override fun run() {
            adapter.setUnhealthy(binder?.engine?.unhealthy ?: emptySet())
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.bulbList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        ui.bulbList.itemAnimator = null
        ui.bulbList.adapter = adapter

        buildPresetChips()
        ui.findBulbs.setOnClickListener { findBulbs() }
        ui.startStop.setOnClickListener { onStartStop() }
        ui.brightnessRow.setOnKeyListener { _, keyCode, event ->
            // Only fires while the row has focus, so LEFT/RIGHT elsewhere keep moving focus.
            val delta = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> 0
            }
            if (delta != 0 && event.action == KeyEvent.ACTION_DOWN) {
                vm.adjustBrightness(delta)
                renderPreset()
            }
            delta != 0
        }

        refreshBulbs()
        renderPreset()
        render()
        ui.root.post { focusDefault() }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, SyncService::class.java), connection, Context.BIND_AUTO_CREATE)
        main.postDelayed(poll, POLL_MS)
    }

    override fun onStop() {
        main.removeCallbacks(poll)
        binder?.removeListener(listener)
        binder = null
        unbindService(connection)
        state = SyncState.Ready
        render()
        super.onStop()
    }

    // ---- rendering --------------------------------------------------------------------------------------

    private fun render() {
        ui.status.text = notice ?: vm.statusText(state)
        ui.startStop.text = vm.startLabel(state)
        val startEnabled = vm.canStart || running
        ui.startStop.isEnabled = startEnabled
        ui.startStop.alpha = if (startEnabled) 1f else DIMMED
        if (!startEnabled && ui.startStop.hasFocus()) ui.findBulbs.requestFocus()
        val color = (state as? SyncState.Syncing)?.color
        val argb = if (color != null) {
            0xFF000000.toInt() or (color.r shl 16) or (color.g shl 8) or color.b
        } else {
            ContextCompat.getColor(this, R.color.wiz_border)
        }
        (ui.swatch.background.mutate() as GradientDrawable).setColor(argb)
    }

    private fun renderPreset() {
        val preset = Presets.byId(App.settings.presetId)
        for (i in 0 until ui.presetRow.childCount) {
            val chip = ui.presetRow.getChildAt(i)
            chip.isSelected = chip.tag == preset.id
        }
        ui.presetDesc.setText(descriptionFor(preset.id))
        ui.brightnessTitle.text = vm.brightnessTitle()
        ui.brightnessValue.text = getString(R.string.percent_format, vm.brightnessPercent())
        ui.brightnessFill.pivotX = 0f
        ui.brightnessFill.scaleX = vm.brightnessPercent() / 100f
    }

    private fun refreshBulbs() {
        val bulbs = App.settings.bulbs
        adapter.submit(bulbs, binder?.engine?.unhealthy ?: emptySet())
        ui.noBulbs.visibility = if (bulbs.isEmpty()) View.VISIBLE else View.GONE
        render()
        ui.root.post { if (currentFocus == null) focusDefault() }
    }

    /** Start when there is something to sync, otherwise Find bulbs. */
    private fun focusDefault() {
        (if (vm.canStart) ui.startStop else ui.findBulbs).requestFocus()
    }

    private fun descriptionFor(id: String): Int = when (id) {
        Presets.INSTANT.id -> R.string.preset_desc_instant
        Presets.MOVIE.id -> R.string.preset_desc_movie
        Presets.GAMING.id -> R.string.preset_desc_gaming
        Presets.AMBIENT.id -> R.string.preset_desc_ambient
        Presets.PARTY.id -> R.string.preset_desc_party
        else -> throw IllegalArgumentException("No description for preset $id")
    }

    private fun buildPresetChips() {
        for (preset in Presets.ALL) {
            val chip = LayoutInflater.from(this).inflate(R.layout.item_chip, ui.presetRow, false) as TextView
            chip.text = preset.label
            chip.tag = preset.id
            chip.setOnClickListener {
                App.settings.setPreset(preset.id)
                renderPreset()
            }
            ui.presetRow.addView(chip)
        }
    }

    // ---- actions ----------------------------------------------------------------------------------------

    private fun onStartStop() {
        if (running) {
            SyncService.stop(this)
        } else if (vm.canStart) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE)
        } else {
            notice = null
            render()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            notice = null
            SyncService.start(this, resultCode, data)
        } else {
            notice = getString(R.string.status_capture_needed)
        }
        render()
    }

    private fun findBulbs() {
        if (discovering) return
        discovering = true
        notice = getString(R.string.status_searching)
        render()
        val app = applicationContext
        Thread({
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("wizsync")
            val found: List<Discovered>? = try {
                lock.acquire()
                App.wiz.discover().also { App.settings.upsertFromDiscovery(it) }
            } catch (_: Exception) {
                null
            } finally {
                if (lock.isHeld) lock.release()
            }
            runOnUiThread {
                discovering = false
                if (isDestroyed) return@runOnUiThread
                notice = if (found == null) {
                    getString(R.string.status_search_failed)
                } else if (found.isEmpty()) {
                    getString(R.string.status_no_bulbs_found)
                } else {
                    resources.getQuantityString(R.plurals.status_found_bulbs, found.size, found.size)
                }
                refreshBulbs()
            }
        }, "wizsync-discover").start()
    }

    private companion object {
        const val REQUEST_CAPTURE = 1
        const val POLL_MS = 1000L
        const val DIMMED = 0.5f
    }
}
