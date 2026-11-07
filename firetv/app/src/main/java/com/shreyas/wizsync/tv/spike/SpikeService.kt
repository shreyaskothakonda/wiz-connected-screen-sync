package com.shreyas.wizsync.tv.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class SpikeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private val sampler = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            Log.w(TAG, "no projection data, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            Log.w(TAG, "getMediaProjection returned null")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped")
            }
        }, handler)

        val r = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        reader = r
        display = mp.createVirtualDisplay(
            "wizspike", W, H, 160,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )

        handler.postDelayed(sampler, 1000)
        handler.postDelayed({ stopSelf() }, 10 * 60 * 1000L)
        return START_NOT_STICKY
    }

    private fun sample() {
        val image = reader?.acquireLatestImage()
        if (image == null) {
            Log.i(TAG, "no frame")
            return
        }
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            var r = 0L
            var g = 0L
            var b = 0L
            for (y in 0 until H) {
                for (x in 0 until W) {
                    val o = y * rowStride + x * pixelStride
                    r += buf.get(o).toInt() and 0xFF
                    g += buf.get(o + 1).toInt() and 0xFF
                    b += buf.get(o + 2).toInt() and 0xFF
                }
            }
            val n = W * H
            Log.i(TAG, "avg r=%d g=%d b=%d".format(r / n, g / n, b / n))
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        display?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("wizsync", "WizSync", NotificationManager.IMPORTANCE_LOW)
            )
            return Notification.Builder(this, "wizsync")
                .setContentTitle("WizSync spike")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle("WizSync spike")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val TAG = "WIZSPIKE"
        private const val W = 96
        private const val H = 54
    }
}
