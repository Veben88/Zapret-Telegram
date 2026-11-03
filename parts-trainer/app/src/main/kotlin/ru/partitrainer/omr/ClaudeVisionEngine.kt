package ru.partitrainer.omr

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ru.partitrainer.core.omr.ClaudeOmr
import ru.partitrainer.core.omr.OmrFormatException
import ru.partitrainer.core.omr.OmrParsed
import ru.partitrainer.data.EngineId
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Запасной движок: страницы уходят напрямую с телефона в api.anthropic.com с ключом пользователя.
 * Никакого промежуточного сервера. Ключ передаётся в заголовке x-api-key и нигде не логируется.
 */
class ClaudeVisionEngine(private val apiKeyProvider: () -> String?) : OmrEngine {
    override val id = EngineId.CLAUDE_VISION

    companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val MAX_SIDE = 1568
        const val MAX_FIX_ATTEMPTS = 2
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun availability(): Availability =
        if (apiKeyProvider().isNullOrBlank()) Availability.Unavailable("Не задан API-ключ Anthropic (Настройки → Распознавание).")
        else Availability.Ready

    override suspend fun recognize(request: OmrRequest, onProgress: (OmrProgress) -> Unit): OmrParsed {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() } ?: throw OmrException("Не задан API-ключ", offerManual = true)
        val pages = ArrayList<OmrParsed>()
        for ((i, page) in request.pages.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: отправка…"))
            val bmp = request.provider.render(page)
            val b64 = try { encodeJpegBase64(bmp) } finally { bmp.recycle() }
            val parsed = recognizePage(key, b64, request.staff, request.twoStaffChoir) { msg -> onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: $msg")) }
            pages += parsed
            onProgress(OmrProgress(i + 1, request.pages.size, "Страница ${page + 1} готова"))
        }
        return merge(pages)
    }

    private fun merge(pages: List<OmrParsed>): OmrParsed {
        val first = pages.first()
        var n = 0
        val measures = pages.flatMapIndexed { pi, p ->
            p.measures.map { m -> n++; m.copy(n = n, pickup = m.pickup && pi == 0) }
        }
        return first.copy(title = pages.firstNotNullOfOrNull { it.title }, tempo = pages.firstNotNullOfOrNull { it.tempo }, measures = measures)
    }

    private suspend fun recognizePage(key: String, imageB64: String, staff: StaffSelection, twoStaff: Boolean, status: (String) -> Unit): OmrParsed {
        val layout = if (twoStaff && staff.isChoirVoice) "\nХор записан на ДВУХ строках: сверху SA (верхний голос строки — S, нижний — A), ниже TB (верхний — T, нижний — B), ещё ниже — фортепиано. Фортепиано пропусти." else ""
        val prompt = "${staff.promptRu}$layout\nВерни только JSON по схеме."
        var text = call(key, imageB64, prompt, null)
        var parsed = try { ClaudeOmr.parse(text) } catch (e: OmrFormatException) {
            status("ответ не разобран, повтор…")
            text = call(key, imageB64, "$prompt\nПредыдущий ответ не был корректным JSON. Верни ТОЛЬКО один JSON-объект без пояснений.", null)
            try { ClaudeOmr.parse(text) } catch (e2: OmrFormatException) {
                throw OmrException("Модель вернула некорректный ответ дважды. Попробуйте ещё раз или введите партию вручную.", offerManual = true, cause = e2)
            }
        }
        var attempt = 0
        while (attempt < MAX_FIX_ATTEMPTS) {
            val bad = ClaudeOmr.badMeasures(parsed)
            if (bad.isEmpty()) break
            attempt++
            status("проверка тактов ${bad.map { parsed.measures[it].n }.joinToString()} (повтор $attempt)…")
            val hint = ClaudeOmr.retryHint(bad.map { parsed.measures[it].n }, parsed)
            val retryText = try { call(key, imageB64, "$prompt\n$hint", text) } catch (e: OmrException) { break }
            val retry = try { ClaudeOmr.parse(retryText) } catch (e: OmrFormatException) { continue }
            if (retry.measures.size == parsed.measures.size) {
                val fixed = bad.filter { i -> Math.abs(retry.measures[i].total - parsed.timeNum * 4.0 / parsed.timeDen) <= 0.02 }
                    .associateWith { retry.measures[it] }
                parsed = ClaudeOmr.replaceMeasures(parsed, fixed)
            }
        }
        val stillBad = ClaudeOmr.badMeasures(parsed)
        return if (stillBad.isEmpty()) parsed else ClaudeOmr.withUncertain(parsed, stillBad)
    }

    private fun encodeJpegBase64(src: Bitmap): String {
        var bmp = src
        val side = maxOf(src.width, src.height)
        if (side > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / side
            bmp = Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true)
        }
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        if (bmp !== src) bmp.recycle()
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    /** Один запрос к Messages API с повторами при 429/5xx. Возвращает текст ответа модели. */
    private suspend fun call(key: String, imageB64: String, userText: String, previousAssistant: String?): String {
        val body = buildJsonObject {
            put("model", ClaudeOmr.MODEL)
            put("max_tokens", 8192)
            put("system", ClaudeOmr.SYSTEM_PROMPT)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") { put("type", "base64"); put("media_type", "image/jpeg"); put("data", imageB64) }
                        })
                        add(buildJsonObject { put("type", "text"); put("text", if (previousAssistant == null) userText else "$userText\nТвой прошлый ответ:\n$previousAssistant") })
                    }
                })
            }
        }.toString()

        var backoff = 2000L
        var lastError = ""
        repeat(4) { attemptNo ->
            val (code, resp, retryAfter) = post(key, body)
            when {
                code in 200..299 -> return extractText(resp)
                code == 401 || code == 403 -> throw OmrException("Ключ API отклонён (код $code). Проверьте ключ в настройках.", offerManual = false)
                code == 400 -> throw OmrException("Запрос отклонён: ${errorMessage(resp)}", offerManual = true)
                code == 429 || code == 529 || code >= 500 -> {
                    lastError = "код $code"
                    if (attemptNo < 3) delay(retryAfter ?: backoff)
                    backoff *= 2
                }
                else -> throw OmrException("Ошибка сервиса (код $code): ${errorMessage(resp)}", offerManual = true)
            }
        }
        throw OmrException("Сервис недоступен или перегружен ($lastError). Попробуйте позже.", offerManual = true)
    }

    private data class Http(val code: Int, val body: String, val retryAfterMs: Long?)

    private suspend fun post(key: String, body: String): Http = withContext(Dispatchers.IO) {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        val job = currentCoroutineContext()[Job]
        val handle = job?.invokeOnCompletion { if (it != null) conn.disconnect() } // отмена закрывает соединение
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", ClaudeOmr.ANTHROPIC_VERSION)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val ra = conn.getHeaderField("retry-after")?.toLongOrNull()?.times(1000)
            Http(code, text, ra)
        } catch (e: java.io.IOException) {
            if (job?.isActive == false) throw kotlinx.coroutines.CancellationException("Отменено")
            throw OmrException("Нет связи с сервером Anthropic. Проверьте интернет.", offerManual = false, cause = e)
        } finally {
            handle?.dispose()
            conn.disconnect()
        }
    }

    private fun extractText(resp: String): String {
        val root = try { json.parseToJsonElement(resp) as JsonObject } catch (e: Exception) { throw OmrException("Непонятный ответ сервиса", true, e) }
        val content = root["content"] as? JsonArray ?: throw OmrException("В ответе нет содержимого", true)
        return content.mapNotNull { (it as? JsonObject)?.takeIf { o -> (o["type"] as? JsonPrimitive)?.contentOrNull == "text" }?.get("text") }
            .joinToString("") { (it as JsonPrimitive).content }
            .ifBlank { throw OmrException("Пустой ответ модели", true) }
    }

    private fun errorMessage(resp: String): String = try {
        ((json.parseToJsonElement(resp) as JsonObject)["error"] as? JsonObject)?.get("message")?.let { (it as JsonPrimitive).content } ?: "неизвестная ошибка"
    } catch (e: Exception) { "неизвестная ошибка" }
}
