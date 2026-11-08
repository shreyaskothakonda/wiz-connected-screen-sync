package com.shreyas.wizsync.tv.sync

/** An RGB colour as three floats in 0..255, in R, G, B order. */
typealias Rgb = FloatArray

/** Port of the colour maths in app/color.py. Pure Kotlin, no Android APIs. */
object ColorMath {
    private const val BUCKETS = 512
    private const val DOMINANT_MIN_MAX = 24
    private const val SATURATED_MIN_MAX = 40
    private const val TOP_FRACTION = 0.05f

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF
    private fun maxChannel(p: Int) = maxOf(red(p), green(p), blue(p))
    private fun minChannel(p: Int) = minOf(red(p), green(p), blue(p))

    /** Mean colour of every pixel. */
    fun average(f: Frame): Rgb {
        if (f.px.isEmpty()) return floatArrayOf(0f, 0f, 0f)
        var sr = 0L
        var sg = 0L
        var sb = 0L
        for (p in f.px) {
            sr += red(p)
            sg += green(p)
            sb += blue(p)
        }
        val n = f.px.size.toFloat()
        return floatArrayOf(sr / n, sg / n, sb / n)
    }

    /** Most populated colour bucket, weighted toward saturated pixels; ignores near-black. */
    fun dominant(f: Frame): Rgb {
        val scores = DoubleArray(BUCKETS)
        val sumR = DoubleArray(BUCKETS)
        val sumG = DoubleArray(BUCKETS)
        val sumB = DoubleArray(BUCKETS)
        val counts = IntArray(BUCKETS)
        for (p in f.px) {
            val r = red(p)
            val g = green(p)
            val b = blue(p)
            val mx = maxOf(r, g, b)
            if (mx <= DOMINANT_MIN_MAX) continue
            val sat = (mx - minOf(r, g, b)).toDouble() / mx
            val key = (r / 32) * 64 + (g / 32) * 8 + (b / 32)
            scores[key] += 0.05 + sat * sat
            sumR[key] += r
            sumG[key] += g
            sumB[key] += b
            counts[key]++
        }
        var best = -1
        for (k in 0 until BUCKETS) {
            if (counts[k] > 0 && (best < 0 || scores[k] > scores[best])) best = k
        }
        if (best < 0) return floatArrayOf(0f, 0f, 0f)
        val n = counts[best].toDouble()
        return floatArrayOf((sumR[best] / n).toFloat(), (sumG[best] / n).toFloat(), (sumB[best] / n).toFloat())
    }

    /** Mean of the brightest 5% of pixels by perceived luminance; robust to single hot pixels. */
    fun brightest(f: Frame): Rgb {
        val lum = FloatArray(f.px.size) { i ->
            val p = f.px[i]
            0.2126f * red(p) + 0.7152f * green(p) + 0.0722f * blue(p)
        }
        return topMean(f.px, lum)
    }

    /** Mean of the 5% most saturated pixels, ignoring dark ones whose saturation is just noise. */
    fun saturated(f: Frame): Rgb {
        val kept = IntArray(f.px.size)
        var n = 0
        for (p in f.px) {
            if (maxChannel(p) > SATURATED_MIN_MAX) kept[n++] = p
        }
        val candidates = kept.copyOf(n)
        val score = FloatArray(n) { i ->
            val p = candidates[i]
            val mx = maxChannel(p).toFloat()
            val mn = minChannel(p).toFloat()
            ((mx - mn) / mx) * (0.5f + mx / 510f) // mild preference for brighter pixels
        }
        return topMean(candidates, score)
    }

    /** Mean of the top [TOP_FRACTION] of [pixels] by [score] (at least one pixel); [score] is aligned with [pixels]. */
    private fun topMean(pixels: IntArray, score: FloatArray): Rgb {
        if (pixels.isEmpty()) return floatArrayOf(0f, 0f, 0f)
        val k = maxOf(1, (pixels.size * TOP_FRACTION).toInt())
        val sorted = score.copyOf()
        sorted.sort()
        val threshold = sorted[sorted.size - k]
        var above = 0
        for (s in score) if (s > threshold) above++
        var tiesLeft = k - above
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var taken = 0
        for (i in pixels.indices) {
            val take = when {
                score[i] > threshold -> true
                score[i] == threshold && tiesLeft > 0 -> {
                    tiesLeft--
                    true
                }
                else -> false
            }
            if (take) {
                val p = pixels[i]
                sr += red(p)
                sg += green(p)
                sb += blue(p)
                taken++
            }
        }
        return floatArrayOf(sr.toFloat() / taken, sg.toFloat() / taken, sb.toFloat() / taken)
    }

    /**
     * Trims letterbox/pillarbox bars: edge rows and columns whose mean brightness is above [threshold].
     * Returns the full frame if nothing is bright, or if the crop would keep less than [minKeep] of either dimension.
     */
    fun cropBlackBars(f: Frame, threshold: Float = 14f, minKeep: Float = 0.2f): Frame {
        val rowSum = DoubleArray(f.height)
        val colSum = DoubleArray(f.width)
        for (y in 0 until f.height) {
            for (x in 0 until f.width) {
                val p = f.px[y * f.width + x]
                val s = (red(p) + green(p) + blue(p)).toDouble()
                rowSum[y] += s
                colSum[x] += s
            }
        }
        val rows = (0 until f.height).filter { rowSum[it] / (3.0 * f.width) > threshold }
        val cols = (0 until f.width).filter { colSum[it] / (3.0 * f.height) > threshold }
        if (rows.isEmpty() || cols.isEmpty()) return f
        val top = rows.first()
        val left = cols.first()
        val outH = rows.last() - top + 1
        val outW = cols.last() - left + 1
        if (outH < f.height * minKeep || outW < f.width * minKeep) return f
        val out = IntArray(outW * outH)
        for (y in 0 until outH) {
            for (x in 0 until outW) out[y * outW + x] = f.px[(top + y) * f.width + left + x]
        }
        return Frame(outW, outH, out)
    }

    /** Scales saturation by [amount] (1.0 = unchanged), clamped to 1. Greys stay grey. */
    fun boostSaturation(c: Rgb, amount: Float): Rgb {
        val r = c[0] / 255f
        val g = c[1] / 255f
        val b = c[2] / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        val v = mx
        val s = if (mx == 0f) 0f else d / mx
        val h = if (d == 0f) {
            0f
        } else {
            val rc = (mx - r) / d
            val gc = (mx - g) / d
            val bc = (mx - b) / d
            val hue = when {
                r == mx -> bc - gc
                g == mx -> 2f + rc - bc
                else -> 4f + gc - rc
            }
            ((hue / 6f) % 1f + 1f) % 1f
        }
        val rgb = hsvToRgb(h, minOf(1f, s * amount), v)
        return floatArrayOf(rgb[0] * 255f, rgb[1] * 255f, rgb[2] * 255f)
    }

    /** HSV to RGB with all components in 0..1. */
    private fun hsvToRgb(h: Float, s: Float, v: Float): Rgb {
        val i = (h * 6f).toInt()
        val f = h * 6f - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        return when (i % 6) {
            0 -> floatArrayOf(v, t, p)
            1 -> floatArrayOf(q, v, p)
            2 -> floatArrayOf(p, v, t)
            3 -> floatArrayOf(p, q, v)
            4 -> floatArrayOf(t, p, v)
            else -> floatArrayOf(v, p, q)
        }
    }

    /** Exponential smoothing: [factor] 0 means no smoothing, closer to 1 means slower. The first value passes through. */
    fun smooth(prev: Rgb?, new: Rgb, factor: Float): Rgb {
        if (prev == null) return new
        return FloatArray(3) { prev[it] * factor + new[it] * (1f - factor) }
    }

    /** Peak channel as a fraction, 0..1. */
    fun brightnessOf(c: Rgb): Float = maxOf(c[0], c[1], c[2]) / 255f

    /** Chebyshev distance: the largest absolute per-channel difference. */
    fun distance(a: Rgb, b: Rgb): Float = maxOf(
        kotlin.math.abs(a[0] - b[0]),
        kotlin.math.abs(a[1] - b[1]),
        kotlin.math.abs(a[2] - b[2]),
    )
}

/** The four extraction strategies, each mapping a frame to one colour. */
enum class Mode(val extract: (Frame) -> Rgb) {
    AVERAGE({ ColorMath.average(it) }),
    DOMINANT({ ColorMath.dominant(it) }),
    BRIGHTEST({ ColorMath.brightest(it) }),
    SATURATED({ ColorMath.saturated(it) }),
}
