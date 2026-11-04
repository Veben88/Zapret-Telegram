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
import ru.partitrainer.omr.homr.EncoderBackend
import ru.partitrainer.omr.homr.EncoderBench
import ru.partitrainer.omr.homr.EncoderBenchResult
import ru.partitrainer.omr.homr.EncoderVariant
import ru.partitrainer.omr.homr.Fp16Converter
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
 * Модели homr (ONNX). Сегментатор и декодер (≈91 МБ) скачиваются по запросу из релиза проекта homr и проверяются
 * по SHA-256. Энкодер — наша int8-версия (17 МБ, квантование весов MatMul, результат распознавания тот же,
 * работает быстрее) — лежит в APK (assets) и при первом обращении копируется в память приложения. Скачивание идёт напрямую с GitHub, без нашего сервера; передаются только запросы файлов.
 */
class HomrModelManager(context: Context) {
    private val appContext = context.applicationContext
    private data class Spec(val zip: String, val onnx: String, val sha256: String, val zipBytes: Long)

    private companion object {
        const val ENCODER_SHA256 = "df016c4ee93f69a168d2368b14703ca03ea507514b50d1d78369acc07d9761e3"
        const val ENCODER_BYTES = 17_142_614L
    }

    /** Модель сегментации: «Точный режим» и запасной путь для страниц, где быстрый поиск линеек не справился. */
    private val segnetSpec = Spec("segnet_308-3296ccd40960f90ca6ab9c035cca945675d30a0f.zip", "segnet_308-3296ccd40960f90ca6ab9c035cca945675d30a0f.onnx",
        "6ed36640db4ef5d223098b6d5efe4eda97c66b24a2c72faab8a018c749003a8d", 53_214_519)

    /** Модели, которые скачиваются (энкодер встроен в APK). Сегментатор обязателен, чтобы трудные страницы не срывались. */
    private val specs = listOf(
        segnetSpec,
        Spec("decoder_pytorch_model_509-2293bba14bfea47d9d28411339362af15fce4c31.zip", "decoder_pytorch_model_509-2293bba14bfea47d9d28411339362af15fce4c31.onnx",
            "99a6ac39770616bc1a20d6c409bb85fbbe21ba47f972984f9ef834fb5741b96f", 37_824_782),
    )
    private val encoderFile get() = File(dir, "encoder-int8.onnx")
    private val baseUrl = "https://github.com/liebharc/homr/releases/download/onnx_checkpoints/"
    private val dir = File(context.filesDir, "models/homr").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<ModelState>(if (installed()) ModelState.Ready else ModelState.Missing)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    val downloadSizeMb: Int = (specs.sumOf { it.zipBytes } / 1_000_000).toInt()
    val segnetSizeMb: Int = (segnetSpec.zipBytes / 1_000_000).toInt()

    fun segnetInstalled() = fileOf(segnetSpec).isFile

    /** Достаточно ли памяти, чтобы читать несколько строк одновременно (каждый параллельный прогон энкодера — сотни МБ). */
    val bigMemoryDevice: Boolean by lazy {
        val am = appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        mi.totalMem >= 5_500_000_000L && !am.isLowRamDevice
    }

    private fun fileOf(s: Spec) = File(dir, s.onnx)
    private fun installed() = specs.all { fileOf(it).isFile }

    fun files(): HomrModelFiles? {
        if (!installed()) return null
        // float-энкодер от прежних версий (уже проверен по SHA-256 при загрузке) — пригодится для подбора ускорителя
        val oldFloat = File(dir, floatSpec.onnx)
        if (oldFloat.isFile && !floatEncoderFile.isFile && job?.isActive != true) oldFloat.renameTo(floatEncoderFile)
        return try { HomrModelFiles(fileOf(segnetSpec), encoderFor(encoderBackend ?: EncoderBackend.INT8_CPU), fileOf(specs[1])) } catch (e: Exception) { null }
    }

    // ---------- Подбор ускорителя для энкодера ----------

    /** Оригинальный float-энкодер homr: нужен только для сравнения вариантов (float/fp16/NNAPI). */
    private val floatSpec = Spec("encoder_pytorch_model_509-2293bba14bfea47d9d28411339362af15fce4c31.zip", "encoder_pytorch_model_509-2293bba14bfea47d9d28411339362af15fce4c31.onnx",
        "ee5767286c60dc3d567fe72b6560e7285261ede1535b4215153c199ab3057559", 48_281_161)
    val floatEncoderFile get() = File(dir, "encoder-float.onnx")
    val fp16EncoderFile get() = File(dir, "encoder-fp16.onnx")
    val floatEncoderSizeMb = (floatSpec.zipBytes / 1_000_000).toInt()
    private val prefs = appContext.getSharedPreferences("encoder", Context.MODE_PRIVATE)

    /** Выбранный способ запуска энкодера; null — ещё не подбирали. */
    var encoderBackend: EncoderBackend?
        get() = EncoderBackend.parse(prefs.getString("backend", null))?.takeIf { encoderFileOrNull(it) != null }
        set(v) { prefs.edit().putString("backend", v?.name).apply() }

    /** Итоги последнего подбора (для экрана настроек). */
    var benchReport: String
        get() = prefs.getString("report", "") ?: ""
        private set(v) { prefs.edit().putString("report", v).apply() }

    /** Есть ли у процессора аппаратная арифметика fp16 (ARMv8.2-A, флаг asimdhp). */
    val supportsFp16: Boolean by lazy { runCatching { File("/proc/cpuinfo").readText().contains("asimdhp") }.getOrDefault(false) }

    private fun encoderFileOrNull(b: EncoderBackend): File? = when (b.model) {
        EncoderBackend.Model.INT8 -> encoderFile.takeIf { it.isFile } ?: runCatching { ensureEncoder() }.getOrNull()
        EncoderBackend.Model.FLOAT -> floatEncoderFile.takeIf { it.isFile }
        EncoderBackend.Model.FP16 -> fp16EncoderFile.takeIf { it.isFile }
    }

    private fun encoderFor(b: EncoderBackend): File = encoderFileOrNull(b) ?: ensureEncoder()

    private val _tuneStatus = MutableStateFlow<String?>(null)
    /** Ход подбора (для экрана настроек); null — не идёт. */
    val tuneStatus: StateFlow<String?> = _tuneStatus.asStateFlow()

    /**
     * Замеряет варианты запуска энкодера на тестовой строке и запоминает самый быстрый из тех, чей результат совпадает
     * с эталоном (int8 на процессоре). [full] — ещё float/fp16/NNAPI (докачивает float-энкодер ≈48 МБ).
     * [threads] — потоков на прогон, как при распознавании.
     */
    suspend fun tune(full: Boolean, threads: Int): List<EncoderBenchResult> = withContext(Dispatchers.Default) {
        try {
            val variants = arrayListOf(EncoderBackend.INT8_CPU, EncoderBackend.INT8_XNNPACK)
            if (full) {
                if (!floatEncoderFile.isFile) {
                    _tuneStatus.value = "Скачивание float-энкодера…"
                    var done = 0L
                    download(floatSpec) { n -> done += n; _tuneStatus.value = "Скачивание float-энкодера: ${done * 100 / floatSpec.zipBytes}%" }
                    if (!fileOf(floatSpec).renameTo(floatEncoderFile)) throw IllegalStateException("Не удалось сохранить энкодер")
                }
                variants += listOf(EncoderBackend.FLOAT_CPU, EncoderBackend.FLOAT_XNNPACK)
                if (supportsFp16) {
                    if (!fp16EncoderFile.isFile) {
                        _tuneStatus.value = "Подготовка fp16-версии…"
                        val tmp = File(dir, "encoder-fp16.onnx.part")
                        tmp.writeBytes(Fp16Converter.convert(floatEncoderFile.readBytes()))
                        if (!tmp.renameTo(fp16EncoderFile)) throw IllegalStateException("Не удалось сохранить fp16-энкодер")
                    }
                    variants += listOf(EncoderBackend.FP16_CPU, EncoderBackend.FP16_XNNPACK)
                }
                if (android.os.Build.VERSION.SDK_INT >= 27) variants += listOf(EncoderBackend.FLOAT_NNAPI, EncoderBackend.FLOAT_NNAPI_FP16)
            }
            _tuneStatus.value = "Замер скорости…"
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val res = EncoderBench.compare(env, variants.map { b ->
                EncoderVariant(b.name, encoderFor(b).path) { o -> o.setIntraOpNumThreads(threads.coerceAtLeast(1)); b.configure(o, threads) }
            }, EncoderBench.sampleCanvas())
            val best = res.filter { it.ok }.minByOrNull { it.ms }
            best?.let { r -> encoderBackend = EncoderBackend.valueOf(r.name) }
            benchReport = res.joinToString("\n") { r ->
                val b = EncoderBackend.valueOf(r.name)
                val mark = if (r === best) " ✓" else ""
                when {
                    r.error != null -> "${b.title}: не запустился"
                    !r.ok -> "${b.title}: ${r.ms} мс, неточный результат"
                    else -> "${b.title}: ${r.ms} мс$mark"
                }
            } + if (full && !supportsFp16) "\nfp16: процессор не поддерживает аппаратную арифметику fp16" else ""
            res
        } finally { _tuneStatus.value = null }
    }

    /** Копирует энкодер из assets и проверяет SHA-256 (повторно — только если файла нет или он повреждён). */
    @Synchronized private fun ensureEncoder(): File {
        val f = encoderFile
        if (f.isFile && f.length() == ENCODER_BYTES) return f
        val tmp = File(dir, "encoder-int8.onnx.part")
        val md = MessageDigest.getInstance("SHA-256")
        appContext.assets.open("homr/encoder-int8.onnx").use { input -> tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
        } }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (hash != ENCODER_SHA256) { tmp.delete(); throw IllegalStateException("Встроенный энкодер повреждён") }
        if (!tmp.renameTo(f)) throw IllegalStateException("Не удалось сохранить энкодер")
        return f
    }

    /** Скачивает недостающие модели. */
    fun startDownload() {
        val need = specs
        if (job?.isActive == true || need.all { fileOf(it).isFile }) { if (installed()) _state.value = ModelState.Ready; return }
        job = scope.launch {
            try {
                val total = need.sumOf { it.zipBytes }
                if (dir.usableSpace < total * 3) throw IllegalStateException("Недостаточно места: нужно ≈ ${total * 3 / 1_000_000} МБ")
                var done = 0L
                for (s in need) {
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
