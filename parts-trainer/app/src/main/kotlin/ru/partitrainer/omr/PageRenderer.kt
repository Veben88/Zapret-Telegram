package ru.partitrainer.omr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Источник страниц: PDF (штатный PdfRenderer) или одно изображение. Страницы рендерятся по одной. */
class PageRenderer(private val file: File, private val isPdf: Boolean) : AutoCloseable {
    companion object {
        const val DPI = 200
        /** Ограничение памяти на одну страницу (~48 МБ ARGB_8888). */
        const val MAX_PIXELS = 12_000_000L
    }

    private val mutex = Mutex() // PdfRenderer не потокобезопасен
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    val pageCount: Int by lazy {
        if (isPdf) {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
            renderer!!.pageCount
        } else 1
    }

    /** Полноразмерная страница (≈200 dpi, с ограничением по пикселям) с поворотом. */
    suspend fun render(index: Int, rotation: Int = 0): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock { rotate(renderLocked(index, DPI, MAX_PIXELS), rotation) }
    }

    /**
     * Страница для распознавания на устройстве: сразу нужной ширины (1920 px после поворота), а не 200 dpi с последующим
     * растяжением — меньше работы и чётче линии. Фото не увеличиваются, только уменьшаются.
     */
    suspend fun renderForOmr(index: Int, rotation: Int = 0, width: Int = 1920): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!isPdf) return@withLock rotate(renderLocked(index, DPI, MAX_PIXELS), rotation)
            pageCount
            val r = renderer ?: throw IllegalStateException("PDF закрыт")
            val d = ((rotation % 360) + 360) % 360
            val bmp = r.openPage(index).use { page ->
                val across = if (d == 90 || d == 270) page.height else page.width
                var scale = width.toFloat() / across
                val px = page.width.toDouble() * scale * page.height * scale
                if (px > MAX_PIXELS) scale *= Math.sqrt(MAX_PIXELS / px).toFloat()
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
            rotate(bmp, rotation)
        }
    }

    /** Миниатюра заданной ширины. */
    suspend fun thumbnail(index: Int, widthPx: Int, rotation: Int = 0): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock { rotate(renderLocked(index, 0, 0, widthPx), rotation) }
    }

    private fun renderLocked(index: Int, dpi: Int, maxPixels: Long, targetWidth: Int = 0): Bitmap {
        if (!isPdf) return decodeImage(file, if (targetWidth > 0) targetWidth else Int.MAX_VALUE, maxPixels.takeIf { it > 0 } ?: MAX_PIXELS)
        pageCount // инициализация
        val r = renderer ?: throw IllegalStateException("PDF закрыт")
        r.openPage(index).use { page ->
            var scale = if (targetWidth > 0) targetWidth.toFloat() / page.width else dpi / 72f
            if (maxPixels > 0) {
                val px = page.width.toDouble() * scale * page.height * scale
                if (px > maxPixels) scale *= Math.sqrt(maxPixels / px).toFloat()
            }
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val m = Matrix().apply { postRotate(d.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    override fun close() {
        runCatching { renderer?.close() }
        runCatching { pfd?.close() }
        renderer = null; pfd = null
    }
}

/** Декодирует изображение с понижением разрешения и учётом ориентации EXIF. */
fun decodeImage(file: File, maxWidth: Int, maxPixels: Long): Bitmap {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, opts)
    var sample = 1
    while ((opts.outWidth / sample).toLong() * (opts.outHeight / sample) > maxPixels ||
        (maxWidth != Int.MAX_VALUE && opts.outWidth / sample > maxWidth * 2)) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IllegalStateException("Не удалось прочитать изображение")
    val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val deg = when (orientation) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
    var out = bmp
    if (deg != 0f) {
        out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        if (out !== bmp) bmp.recycle()
    }
    if (maxWidth != Int.MAX_VALUE && out.width > maxWidth) {
        val k = maxWidth.toFloat() / out.width
        val scaled = Bitmap.createScaledBitmap(out, maxWidth, (out.height * k).toInt().coerceAtLeast(1), true)
        if (scaled !== out) out.recycle()
        out = scaled
    }
    return out
}
