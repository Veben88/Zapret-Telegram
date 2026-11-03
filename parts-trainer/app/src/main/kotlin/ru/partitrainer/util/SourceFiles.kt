package ru.partitrainer.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

/** Копирование выбранных пользователем файлов во внутреннее хранилище + хеширование. */
class SourceFiles(private val context: Context) {
    data class Imported(val file: File, val hash: String, val displayName: String, val mime: String?)

    private val dir get() = File(context.filesDir, "sources").apply { mkdirs() }

    fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) return it.getString(0)
                }
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    /** Копирует файл и считает SHA-256; одинаковые файлы хранятся один раз. */
    fun import(uri: Uri, maxBytes: Long = 200L * 1024 * 1024): Imported {
        val name = displayName(uri)
        val mime = context.contentResolver.getType(uri)
        val tmp = File.createTempFile("imp", ".tmp", context.cacheDir)
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    total += n
                    if (total > maxBytes) { tmp.delete(); throw IllegalStateException("Файл слишком большой") }
                    md.update(buf, 0, n); out.write(buf, 0, n)
                }
            }
        } ?: throw IllegalStateException("Не удалось открыть файл")
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..8 } ?: "bin"
        val dest = File(dir, "$hash.$ext")
        if (!dest.exists()) tmp.copyTo(dest, overwrite = false)
        tmp.delete()
        return Imported(dest, hash, name, mime)
    }

    fun fromHash(hash: String): File? = dir.listFiles()?.firstOrNull { it.name.startsWith(hash) }

    fun deleteAll() { dir.deleteRecursively() }
}

object FileKinds {
    enum class Kind { PDF, IMAGE, MUSICXML, MXL, MIDI, UNKNOWN }

    fun detect(name: String, mime: String?, head: ByteArray): Kind {
        val n = name.lowercase()
        return when {
            head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "%PDF" -> Kind.PDF
            head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "MThd" -> Kind.MIDI
            head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> Kind.IMAGE
            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> Kind.IMAGE
            n.endsWith(".mxl") -> Kind.MXL
            head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> Kind.MXL
            n.endsWith(".musicxml") || n.endsWith(".xml") -> Kind.MUSICXML
            mime?.startsWith("image/") == true -> Kind.IMAGE
            mime?.contains("xml") == true -> Kind.MUSICXML
            n.endsWith(".mid") || n.endsWith(".midi") -> Kind.MIDI
            n.endsWith(".pdf") -> Kind.PDF
            else -> Kind.UNKNOWN
        }
    }
}
