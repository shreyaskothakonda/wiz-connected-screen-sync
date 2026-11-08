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

    /** Everything that lives for one Start..Stop; the service may run several in a row. */
    private class Session(val projection: MediaProjection) {
        @Volatile var source: ProjectionFrameSource? = null
        @Volatile var sink: WizSink? = null
        @Volatile var engine: SyncEngine? = null
        @Volatile var loopThread: Thread? = null
        @Volatile var stopping = false
        @Volatile var receiverRegistered = false
        val shuttingDown = AtomicBoolean(false)
        val released = AtomicBoolean(false)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(SyncState) -> Unit>()

    @Volatile private var session: Session? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "projection stopped by system")
            session?.let { s -> shutDown(s) { it.applyResting() } }
        }
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "screen off")
            session?.let { s -> shutDown(s) { it.applyOff() } }
        }
    }

    inner class LocalBinder : Binder() {
        val engine: SyncEngine? get() = session?.takeIf { !it.released.get() }?.engine

        fun addListener(l: (SyncState) -> Unit) {
            listeners.add(l)
            l(engine?.state ?: SyncState.Ready)
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
                val s = session
                if (s != null) shutDown(s, null) else leave()
            }
            else -> if (session == null) leave()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION") // stopForeground(Boolean) is deprecated only on API 33+
    private fun leave() {
        stopForeground(true)
        stopSelf()
    }

    private fun handleStart(intent: Intent) {
        val existing = session
        if (existing != null) {
            Log.w(TAG, "start ignored: session already " +
                if (existing.shuttingDown.get()) "shutting down" else "running")
            return
        }
        var s: Session? = null
        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data: Intent = intent.getParcelableExtra(EXTRA_DATA) ?: error("no projection data")
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data) ?: error("getMediaProjection returned null")
            val cur = Session(mp)
            s = cur
            session = cur
            val src = ProjectionFrameSource(mp, projectionCallback)
            cur.source = src
            val snk = WizSink(App.wiz)
            cur.sink = snk
            val eng = SyncEngine(src, snk, App.settings)
            eng.onState = { st -> main.post { listeners.forEach { it(st) } } }
            cur.engine = eng
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            cur.receiverRegistered = true
            cur.loopThread = Thread({
                Log.i(TAG, "loop started")
                try {
                    eng.runLoop { cur.stopping }
                } finally {
                    Log.i(TAG, "loop stopped")
                }
            }, "wizsync-loop").also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "failed to start sync", e)
            s?.let { release(it) }
            val msg = e.message ?: e.javaClass.simpleName
            listeners.forEach { it(SyncState.Error(msg)) }
            leave()
        }
    }

    /**
     * Single exit path for [s]: on a background thread run [before] (if any), signal the loop to stop (its exit
     * rests the bulbs unless [before] latched them off), join it, release everything and stop the service.
     */
    private fun shutDown(s: Session, before: ((SyncEngine) -> Unit)?) {
        if (!s.shuttingDown.compareAndSet(false, true)) return
        Thread({
            try {
                s.engine?.let { e -> before?.invoke(e) }
                s.stopping = true
                s.loopThread?.join()
            } catch (e: Exception) {
                Log.w(TAG, "shutdown", e)
            } finally {
                release(s)
                main.post { if (session == null) leave() }
            }
        }, "wizsync-shutdown").start()
    }

    /** Releases every resource of [s] exactly once; safe from any thread. */
    private fun release(s: Session) {
        if (!s.released.compareAndSet(false, true)) return
        s.stopping = true
        if (s.receiverRegistered) {
            s.receiverRegistered = false
            try { unregisterReceiver(screenOff) } catch (_: IllegalArgumentException) {}
        }
        try { s.source?.close() } catch (e: Exception) { Log.w(TAG, "source.close", e) }
        try { s.projection.stop() } catch (e: Exception) { Log.w(TAG, "projection.stop", e) }
        s.sink?.shutdown()
        if (session === s) session = null
    }

    override fun onDestroy() {
        session?.let { it.stopping = true; release(it) }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val text = "WiZ Sync is syncing your lights"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
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
