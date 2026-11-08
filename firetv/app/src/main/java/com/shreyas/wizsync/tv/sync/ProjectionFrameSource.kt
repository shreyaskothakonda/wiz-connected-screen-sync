package com.shreyas.wizsync.tv.sync

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.Closeable

/**
 * Mirrors the screen into a tiny [ImageReader] and decodes each image into a [Frame] on a private thread.
 * [callback] is registered on [projection] (delivered on the main looper) before the display is created.
 * [close] releases the display, reader and thread and unregisters [callback]; it does not stop the projection.
 */
class ProjectionFrameSource(
    private val projection: MediaProjection,
    private val callback: MediaProjection.Callback,
    private val width: Int = 96,
    private val height: Int = 54,
) : FrameSource, Closeable {

    @Volatile private var latest: Frame? = null
    private val buffers = arrayOf(IntArray(width * height), IntArray(width * height))
    private var next = 0

    private val thread = HandlerThread("wizsync-capture").also { it.start() }
    private val reader: ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private val display: VirtualDisplay?
    private var closed = false

    init {
        reader.setOnImageAvailableListener({ r -> onImage(r) }, Handler(thread.looper))
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        display = projection.createVirtualDisplay(
            "wizsync", width, height, 160,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null,
        )
        if (display == null) {
            close()
            throw IllegalStateException("createVirtualDisplay returned null")
        }
    }

    override fun latest(): Frame? = latest

    private fun onImage(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null // reader closed or too many images held
        } ?: return
        try {
            val plane = image.planes[0]
            val into = buffers[next]
            next = next xor 1
            latest = RgbaDecoder.decode(plane.buffer, width, height, plane.rowStride, plane.pixelStride, into)
        } finally {
            image.close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        projection.unregisterCallback(callback)
        display?.release()
        reader.setOnImageAvailableListener(null, null)
        thread.quitSafely()
        if (Thread.currentThread() !== thread) thread.join(500)
        reader.close()
    }
}
