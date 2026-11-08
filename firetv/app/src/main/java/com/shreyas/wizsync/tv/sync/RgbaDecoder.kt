package com.shreyas.wizsync.tv.sync

import java.nio.ByteBuffer

/** Converts an RGBA_8888 image plane into a [Frame]. */
object RgbaDecoder {
    /**
     * Reads [buf] with absolute gets (its position is untouched) and writes packed 0xRRGGBB ints into [into],
     * which must hold at least `width * height` entries.
     */
    fun decode(buf: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, into: IntArray): Frame {
        require(into.size >= width * height) { "into too small: ${into.size} < ${width * height}" }
        var out = 0
        for (y in 0 until height) {
            var o = y * rowStride
            for (x in 0 until width) {
                val r = buf.get(o).toInt() and 0xFF
                val g = buf.get(o + 1).toInt() and 0xFF
                val b = buf.get(o + 2).toInt() and 0xFF
                into[out++] = (r shl 16) or (g shl 8) or b
                o += pixelStride
            }
        }
        return Frame(width, height, into)
    }
}
