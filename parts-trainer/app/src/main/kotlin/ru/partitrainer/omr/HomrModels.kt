package ru.partitrainer.omr

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.partitrainer.omr.homr.HomrModelFiles
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val doneBytes: Long, val totalBytes: Long, val file: String) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Модели homr (ONNX, ≈157 МБ) скачиваются по запросу из релиза проекта homr и проверяются по SHA-256.
 * В APK их нет. Скачивание идёт напрямую с GitHub, без нашего сервера; передаются только запросы файлов.
 */
class HomrModelManager(context: Context) {
    private data class Spec(val zip: String, val onnx: String, val sha256: String, val zipBytes: Long)

    private val specs = listOf(
        Spec("segnet_308-3296ccd40960f90ca6ab9c035cca945675d30a0f.zip", "segnet_308-3296ccd40960f90ca6ab9c035cca945675d30a0f.onnx",
            "6ed36640db4ef5d223098b6d5efe4eda97c66b24a2c72faab8a018c749003a8d", 53_214_519),
        Spec("encoder_pytorch_model_509-2293bba14bfea47d9d28411339362af15fce4c31.zip", "encoder_pytorch_model_509-2293bba14bfea47d9d28411339362af15fce4c31.onnx",
            "ee5767286c60dc3d567fe72b6560e7285261ede1535b4215153c199ab3057559", 48_281_161),
        Spec("decoder_pytorch_model_509-2293bba14bfea47d9d28411339362af15fce4c31.zip", "decoder_pytorch_model_509-2293bba14bfea47d9d28411339362af15fce4c31.onnx",
            "99a6ac39770616bc1a20d6c409bb85fbbe21ba47f972984f9ef834fb5741b96f", 37_824_782),
    )
    private val baseUrl = "https://github.com/liebharc/homr/releases/download/onnx_checkpoints/"
    private val dir = File(context.filesDir, "models/homr").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<ModelState>(if (installed()) ModelState.Ready else ModelState.Missing)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    val downloadSizeMb: Int = (specs.sumOf { it.zipBytes } / 1_000_000).toInt()

    private fun fileOf(s: Spec) = File(dir, s.onnx)
    private fun installed() = specs.all { fileOf(it).isFile }

    fun files(): HomrModelFiles? =
        if (installed()) HomrModelFiles(fileOf(specs[0]), fileOf(specs[1]), fileOf(specs[2])) else null

    fun startDownload() {
        if (job?.isActive == true || installed()) { if (installed()) _state.value = ModelState.Ready; return }
        job = scope.launch {
            try {
                val total = specs.sumOf { it.zipBytes }
                if (dir.usableSpace < total * 3) throw IllegalStateException("Недостаточно места: нужно ≈ ${total * 3 / 1_000_000} МБ")
                var done = 0L
                for (s in specs) {
                    if (fileOf(s).isFile) { done += s.zipBytes; continue }
                    download(s) { n -> done += n; _state.value = ModelState.Downloading(done, total, s.onnx.substringBefore('_')) }
                }
                _state.value = ModelState.Ready
            } catch (e: CancellationException) {
                _state.value = if (installed()) ModelState.Ready else ModelState.Missing
                throw e
            } catch (e: Exception) {
                _state.value = ModelState.Failed(e.message ?: "Ошибка загрузки")
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun delete() {
        job?.cancel()
        dir.listFiles()?.forEach { it.delete() }
        _state.value = ModelState.Missing
    }

    private suspend fun download(s: Spec, onBytes: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val zip = File(dir, s.zip + ".part")
        val conn = URL(baseUrl + s.zip).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) throw IllegalStateException("Сервер вернул код ${conn.responseCode}")
            conn.inputStream.use { input -> zip.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); onBytes(n.toLong())
                }
            } }
        } finally { conn.disconnect() }
        // распаковка + проверка хеша
        val tmp = File(dir, s.onnx + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        var found = false
        ZipInputStream(zip.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory || !e.name.endsWith(".onnx")) continue
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { ensureActive(); val n = zis.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
                }
                found = true; break
            }
        }
        zip.delete()
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (!found || hash != s.sha256) { tmp.delete(); throw IllegalStateException("Файл ${s.onnx} повреждён или изменён (проверка SHA-256 не пройдена)") }
        if (!tmp.renameTo(fileOf(s))) throw IllegalStateException("Не удалось сохранить модель")
    }
}
