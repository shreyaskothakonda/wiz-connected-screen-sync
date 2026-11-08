package com.shreyas.wizsync.tv.spike

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import com.shreyas.wizsync.tv.App
import com.shreyas.wizsync.tv.sync.SyncService

/** Throwaway launcher until Task 8/9: asks for capture consent, discovers bulbs, then starts [SyncService]. */
class SpikeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val app = applicationContext
            Thread({
                val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lock = wifi.createMulticastLock("wizsync")
                lock.acquire()
                try {
                    val found = App.wiz.discover(timeoutMs = 2000)
                    App.settings.upsertFromDiscovery(found)
                    Log.i(TAG, "bulbs found: ${found.joinToString { "${it.ip}/${it.mac}" }}")
                } finally {
                    lock.release()
                }
                SyncService.start(app, resultCode, data)
                runOnUiThread { finish() }
            }, "wizsync-discover").start()
        } else {
            Log.w(TAG, "consent declined")
            finish()
        }
    }

    private companion object {
        const val TAG = "WIZSYNC"
    }
}
