package com.shreyas.wizsync.tv.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.shreyas.wizsync.tv.App
import com.shreyas.wizsync.tv.R
import com.shreyas.wizsync.tv.wiz.WizSink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground service that owns the projection, frame source and [SyncEngine] while syncing. */
class SyncService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(SyncState) -> Unit>()
    private val shuttingDown = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    @Volatile private var stopping = false
    @Volatile private var engine: SyncEngine? = null
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var source: ProjectionFrameSource? = null
    @Volatile private var sink: WizSink? = null
    @Volatile private var loopThread: Thread? = null
    @Volatile private var receiverRegistered = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "projection stopped by system")
            shutDown { it.applyResting() }
        }
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "screen off")
            shutDown { it.applyOff() }
        }
    }

    inner class LocalBinder : Binder() {
        val engine: SyncEngine? get() = this@SyncService.engine

        fun addListener(l: (SyncState) -> Unit) {
            listeners.add(l)
            l(this@SyncService.engine?.state ?: SyncState.Ready)
        }

        fun removeListener(l: (SyncState) -> Unit) {
            listeners.remove(l)
        }
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                shutDown(null)
            }
            else -> if (engine == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (engine != null) return // duplicate start
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        val mp = data?.let {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mpm.getMediaProjection(resultCode, it)
        }
        if (mp == null) {
            Log.w(TAG, "no projection available, stopping")
            stopSelf()
            return
        }
        projection = mp
        try {
            val src = ProjectionFrameSource(mp, projectionCallback)
            source = src
            val snk = WizSink(App.wiz)
            sink = snk
            val eng = SyncEngine(src, snk, App.settings)
            eng.onState = { s -> main.post { listeners.forEach { it(s) } } }
            engine = eng
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            receiverRegistered = true
            stopping = false
            loopThread = Thread({
                Log.i(TAG, "loop started")
                try {
                    eng.runLoop { stopping }
                } finally {
                    Log.i(TAG, "loop stopped")
                }
            }, "wizsync-loop").also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "failed to start sync", e)
            release()
            stopSelf()
        }
    }

    /**
     * Single exit path: on a background thread run [before] (if any), signal the loop to stop (its exit rests the
     * bulbs unless [before] latched them off), join it, release everything and stop the service. Runs once.
     */
    private fun shutDown(before: ((SyncEngine) -> Unit)?) {
        if (!shuttingDown.compareAndSet(false, true)) return
        val eng = engine
        Thread({
            try {
                if (eng != null) before?.invoke(eng)
                stopping = true
                loopThread?.join()
            } catch (e: Exception) {
                Log.w(TAG, "shutdown", e)
            } finally {
                release()
                main.post { stopSelf() }
            }
        }, "wizsync-shutdown").start()
    }

    /** Releases every resource exactly once; safe from any thread. */
    private fun release() {
        if (!released.compareAndSet(false, true)) return
        stopping = true
        if (receiverRegistered) {
            receiverRegistered = false
            try { unregisterReceiver(screenOff) } catch (_: IllegalArgumentException) {}
        }
        try { source?.close() } catch (e: Exception) { Log.w(TAG, "source.close", e) }
        source = null
        try { projection?.stop() } catch (e: Exception) { Log.w(TAG, "projection.stop", e) }
        projection = null
        sink?.shutdown()
        sink = null
        engine = null
    }

    override fun onDestroy() {
        stopping = true
        release()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val text = "WiZ Sync is syncing your lights"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "WiZ Sync", NotificationManager.IMPORTANCE_LOW)
            )
            return Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .build()
    }

    companion object {
        const val ACTION_START = "com.shreyas.wizsync.tv.START"
        const val ACTION_STOP = "com.shreyas.wizsync.tv.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "wizsync"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "WIZSYNC"

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, SyncService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SyncService::class.java).setAction(ACTION_STOP))
        }
    }
}
