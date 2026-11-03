package ru.partitrainer.omr

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ru.partitrainer.core.omr.ClaudeOmr
import ru.partitrainer.omr.homr.GrayImage
import ru.partitrainer.omr.homr.TextRecognizer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Распознавание слов песни: на api.anthropic.com уходят только узкие полосы страницы с текстом под нотоносцами
 * (а не вся страница), ключ — пользователя, посредников нет. Работает синхронно: вызывается из рабочего потока распознавания.
 * Сбои не прерывают чтение нот: при ошибке строка остаётся без текста, причина в [lastError].
 */
class ClaudeTextRecognizer(private val apiKeyProvider: () -> String?) : TextRecognizer {
    @Volatile var lastError: String? = null
        private set
    @Volatile var recognized = 0
        private set
    private var fatal = false

    private val json = Json { ignoreUnknownKeys = true }

    override fun lines(image: GrayImage): List<String> {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() } ?: run { lastError = "не задан API-ключ"; return emptyList() }
        if (fatal) return emptyList()
        val body = buildJsonObject {
            put("model", ClaudeOmr.MODEL)
            put("max_tokens", 400)
            put("system", PROMPT)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") { put("type", "base64"); put("media_type", "image/png"); put("data", png(image)) }
                        })
                        add(buildJsonObject { put("type", "text"); put("text", "Перепиши слова песни с картинки.") })
                    }
                })
            }
        }.toString()
        var backoff = 1500L
        repeat(3) { attempt ->
            val (code, resp) = try { post(key, body) } catch (e: IOException) { lastError = "нет связи с сервером"; return emptyList() }
            when {
                code in 200..299 -> { recognized++; return extract(resp) }
                code == 401 || code == 403 -> { fatal = true; lastError = "ключ API отклонён (код $code)"; return emptyList() }
                code == 429 || code == 529 || code >= 500 -> {
                    lastError = "сервис перегружен (код $code)"
                    if (attempt < 2) { Thread.sleep(backoff); backoff *= 2 }
                }
                else -> { lastError = "ошибка сервиса (код $code)"; return emptyList() }
            }
        }
        return emptyList()
    }

    private fun png(g: GrayImage): String {
        val px = IntArray(g.width * g.height) { val v = g.data[it].toInt() and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val bmp = Bitmap.createBitmap(px, g.width, g.height, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        try { bmp.compress(Bitmap.CompressFormat.PNG, 100, bos) } finally { bmp.recycle() }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun post(key: String, body: String): Pair<Int, String> {
        val conn = URL(ClaudeVisionEngine.ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 20_000; conn.readTimeout = 60_000; conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", ClaudeOmr.ANTHROPIC_VERSION)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally { conn.disconnect() }
    }

    private fun extract(resp: String): List<String> {
        val content = (try { json.parseToJsonElement(resp) as? JsonObject } catch (e: Exception) { null })?.get("content") as? JsonArray ?: return emptyList()
        val text = content.mapNotNull { (it as? JsonObject)?.takeIf { o -> (o["type"] as? JsonPrimitive)?.contentOrNull == "text" }?.get("text") as? JsonPrimitive }
            .joinToString("") { it.content }
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private companion object {
        const val PROMPT = "На картинке — узкая полоса нотной страницы: строка (строки) текста песни под нотоносцем, возможно с остатками нот и значков. " +
            "Перепиши ТОЛЬКО слова песни, каждую строку текста — с новой строки, сверху вниз. " +
            "Слоги, разделённые на странице дефисами, пиши через дефис с пробелом после него: «Ра- дуй- ся». " +
            "Не добавляй пояснений, номеров куплетов, нот и динамических обозначений (p, mf, cresc.). " +
            "Если на картинке нет слов песни — верни пустой ответ."
    }
}
