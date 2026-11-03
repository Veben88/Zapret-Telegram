package ru.partitrainer.omr.homr

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Одноканальное 8-битное изображение (0 = чёрный, 255 = белый), построчно. */
class GrayImage(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height)) {
    init { require(data.size == width * height) }

    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF
    operator fun set(x: Int, y: Int, v: Int) { data[y * width + x] = v.coerceIn(0, 255).toByte() }

    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): GrayImage {
        val cx0 = x0.coerceIn(0, width); val cx1 = x1.coerceIn(cx0, width)
        val cy0 = y0.coerceIn(0, height); val cy1 = y1.coerceIn(cy0, height)
        val out = GrayImage(cx1 - cx0, cy1 - cy0)
        for (y in cy0 until cy1) System.arraycopy(data, y * width + cx0, out.data, (y - cy0) * out.width, cx1 - cx0)
        return out
    }

    companion object {
        /** Из ARGB-пикселей (как в android.graphics.Bitmap.getPixels); формула яркости как в OpenCV (BGR→GRAY). */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage {
            val out = GrayImage(width, height)
            for (i in argb.indices) {
                val p = argb[i]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                // коэффициенты OpenCV: 0.299 R + 0.587 G + 0.114 B с округлением (фиксированная точка 14 бит)
                out.data[i] = ((r * 4899 + g * 9617 + b * 1868 + 8192) shr 14).toByte()
            }
            return out
        }
    }
}

object ImageOps {
    private fun cubic(x0: Double): Double {
        val a = -0.5
        val x = abs(x0)
        return when {
            x < 1.0 -> ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0
            x < 2.0 -> (((x - 5.0) * x + 8.0) * x - 4.0) * a
            else -> 0.0
        }
    }

    private class Coeffs(val start: IntArray, val weights: Array<DoubleArray>)

    /** Коэффициенты бикубической передискретизации с антиалиасингом при уменьшении (как в Pillow). */
    private fun coeffs(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = 2.0 * filterScale
        val starts = IntArray(outSize)
        val ws = Array(outSize) { DoubleArray(0) }
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            val xmin = max((center - support + 0.5).toInt(), 0)
            val xmax = min((center + support + 0.5).toInt(), inSize)
            val w = DoubleArray(max(xmax - xmin, 1))
            var sum = 0.0
            for (x in 0 until xmax - xmin) { w[x] = cubic((x + xmin - center + 0.5) / filterScale); sum += w[x] }
            if (sum != 0.0) for (x in w.indices) w[x] /= sum
            starts[xx] = xmin; ws[xx] = w
        }
        return Coeffs(starts, ws)
    }

    /** Аналог PIL `Image.resize` (BICUBIC): сначала по горизонтали, затем по вертикали, с округлением в 8 бит. */
    fun resizeBicubic(src: GrayImage, outW: Int, outH: Int): GrayImage {
        if (outW == src.width && outH == src.height) return src
        var cur = src
        if (outW != src.width) {
            val c = coeffs(src.width, outW)
            val out = GrayImage(outW, cur.height)
            for (y in 0 until cur.height) {
                val row = y * cur.width
                for (x in 0 until outW) {
                    val w = c.weights[x]; val s = c.start[x]
                    var acc = 0.0
                    for (k in w.indices) acc += w[k] * (cur.data[row + s + k].toInt() and 0xFF)
                    out.data[y * outW + x] = acc.roundToInt().coerceIn(0, 255).toByte()
                }
            }
            cur = out
        }
        if (outH != src.height) {
            val c = coeffs(cur.height, outH)
            val out = GrayImage(cur.width, outH)
            for (y in 0 until outH) {
                val w = c.weights[y]; val s = c.start[y]
                for (x in 0 until cur.width) {
                    var acc = 0.0
                    for (k in w.indices) acc += w[k] * (cur.data[(s + k) * cur.width + x].toInt() and 0xFF)
                    out.data[y * cur.width + x] = acc.roundToInt().coerceIn(0, 255).toByte()
                }
            }
            cur = out
        }
        return cur
    }

    /** Аналог cv2.resize(..., INTER_LINEAR). */
    fun resizeBilinear(src: GrayImage, outW: Int, outH: Int): GrayImage {
        if (outW == src.width && outH == src.height) return src
        val out = GrayImage(outW, outH)
        val sx = src.width.toDouble() / outW; val sy = src.height.toDouble() / outH
        for (y in 0 until outH) {
            val fy = (y + 0.5) * sy - 0.5
            val y0 = floor(fy).toInt(); val ay = fy - y0
            val ya = y0.coerceIn(0, src.height - 1); val yb = (y0 + 1).coerceIn(0, src.height - 1)
            for (x in 0 until outW) {
                val fx = (x + 0.5) * sx - 0.5
                val x0 = floor(fx).toInt(); val ax = fx - x0
                val xa = x0.coerceIn(0, src.width - 1); val xb = (x0 + 1).coerceIn(0, src.width - 1)
                val v = (src[xa, ya] * (1 - ax) + src[xb, ya] * ax) * (1 - ay) + (src[xa, yb] * (1 - ax) + src[xb, yb] * ax) * ay
                out.data[y * outW + x] = v.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** Ширина 1920 с сохранением пропорций (homr: resize.py). */
    fun resizeToHomrWidth(src: GrayImage): GrayImage {
        val w = 1920
        if (src.width == w) return src
        val h = Math.round(src.height.toDouble() * (w.toDouble() / src.width)).toInt().coerceAtLeast(1)
        return resizeBicubic(src, w, h)
    }

    /** CLAHE как в OpenCV: clipLimit=1.0, плитки 8×8, отражение (BORDER_REFLECT_101) при дополнении. */
    fun clahe(src: GrayImage, clipLimit: Double = 1.0, tilesX: Int = 8, tilesY: Int = 8): GrayImage {
        val padW = if (src.width % tilesX == 0) 0 else tilesX - src.width % tilesX
        val padH = if (src.height % tilesY == 0) 0 else tilesY - src.height % tilesY
        val w = src.width + padW; val h = src.height + padH
        fun reflect(i: Int, n: Int): Int { var k = i; if (k >= n) k = 2 * n - 2 - k; return if (k < 0) 0 else k }
        val img = if (padW == 0 && padH == 0) src else GrayImage(w, h).also { o ->
            for (y in 0 until h) for (x in 0 until w) o.data[y * w + x] = src.data[reflect(y, src.height) * src.width + reflect(x, src.width)]
        }
        val tw = w / tilesX; val th = h / tilesY
        val tileArea = tw * th
        var clip = (clipLimit * tileArea / 256).toInt()
        clip = max(clip, 1)
        val lutScale = 255.0f / tileArea
        val luts = Array(tilesX * tilesY) { IntArray(256) }
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val hist = IntArray(256)
            for (y in ty * th until (ty + 1) * th) for (x in tx * tw until (tx + 1) * tw) hist[img.data[y * w + x].toInt() and 0xFF]++
            var clipped = 0
            for (i in 0 until 256) if (hist[i] > clip) { clipped += hist[i] - clip; hist[i] = clip }
            val batch = clipped / 256
            var residual = clipped - batch * 256
            for (i in 0 until 256) hist[i] += batch
            if (residual != 0) {
                val step = max(256 / residual, 1)
                var i = 0
                while (i < 256 && residual > 0) { hist[i]++; residual--; i += step }
            }
            var sum = 0
            val lut = luts[ty * tilesX + tx]
            for (i in 0 until 256) { sum += hist[i]; lut[i] = Math.round(sum * lutScale).coerceIn(0, 255) }
        }
        val out = GrayImage(src.width, src.height)
        val invTw = 1.0f / tw; val invTh = 1.0f / th
        for (y in 0 until src.height) {
            val tyf = y * invTh - 0.5f
            var ty1 = floor(tyf).toInt(); var ty2 = ty1 + 1
            val ya = tyf - ty1; val ya1 = 1.0f - ya
            ty1 = max(ty1, 0); ty2 = min(ty2, tilesY - 1)
            for (x in 0 until src.width) {
                val txf = x * invTw - 0.5f
                var tx1 = floor(txf).toInt(); var tx2 = tx1 + 1
                val xa = txf - tx1; val xa1 = 1.0f - xa
                tx1 = max(tx1, 0); tx2 = min(tx2, tilesX - 1)
                val v = src.data[y * src.width + x].toInt() and 0xFF
                val res = (luts[ty1 * tilesX + tx1][v] * xa1 + luts[ty1 * tilesX + tx2][v] * xa) * ya1 +
                    (luts[ty2 * tilesX + tx1][v] * xa1 + luts[ty2 * tilesX + tx2][v] * xa) * ya
                out.data[y * src.width + x] = Math.round(res).coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** Поворот вокруг центра на [radians] (положительный — наклон линий «вниз вправо» исправляется), белая заливка. */
    fun rotate(src: GrayImage, radians: Double): GrayImage {
        val out = GrayImage(src.width, src.height)
        java.util.Arrays.fill(out.data, 0xFF.toByte())
        val c = Math.cos(radians); val sn = Math.sin(radians)
        val cx = (src.width - 1) / 2.0; val cy = (src.height - 1) / 2.0
        for (y in 0 until src.height) {
            val dy = y - cy
            for (x in 0 until src.width) {
                val dx = x - cx
                val sx = cx + dx * c - dy * sn
                val sy = cy + dx * sn + dy * c
                if (sx < 0 || sy < 0 || sx > src.width - 1 || sy > src.height - 1) continue
                val x0 = sx.toInt(); val y0 = sy.toInt()
                val x1 = min(x0 + 1, src.width - 1); val y1 = min(y0 + 1, src.height - 1)
                val ax = sx - x0; val ay = sy - y0
                val v = (src[x0, y0] * (1 - ax) + src[x1, y0] * ax) * (1 - ay) + (src[x0, y1] * (1 - ax) + src[x1, y1] * ax) * ay
                out.data[y * src.width + x] = v.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
