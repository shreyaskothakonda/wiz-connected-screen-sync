package com.shreyas.wizsync.tv.sync

/** A captured video frame: [px] holds packed 0xRRGGBB pixels, row-major, so pixel (x, y) is `px[y * width + x]`. */
class Frame(val width: Int, val height: Int, val px: IntArray)
