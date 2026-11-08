package com.shreyas.wizsync.tv.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class RgbaDecoderTest {
    @Test
    fun `decode honours row stride padding`() {
        val w = 2
        val h = 2
        val stride = 12 // 2 px * 4 + 4 bytes padding
        val bytes = ByteArray(stride * h)
        fun put(x: Int, y: Int, r: Int, g: Int, b: Int) {
            val o = y * stride + x * 4
            bytes[o] = r.toByte(); bytes[o + 1] = g.toByte(); bytes[o + 2] = b.toByte(); bytes[o + 3] = -1
        }
        put(0, 0, 255, 0, 0); put(1, 0, 0, 255, 0); put(0, 1, 0, 0, 255); put(1, 1, 10, 20, 30)
        val buf = ByteBuffer.wrap(bytes)
        val f = RgbaDecoder.decode(buf, w, h, stride, 4, IntArray(w * h))
        assertEquals(0xFF0000, f.px[0])
        assertEquals(0x00FF00, f.px[1])
        assertEquals(0x0000FF, f.px[2])
        assertEquals(0x0A141E, f.px[3])
        assertEquals(0, buf.position())
    }
}
