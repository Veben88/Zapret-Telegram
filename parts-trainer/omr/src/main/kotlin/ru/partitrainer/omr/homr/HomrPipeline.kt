package ru.partitrainer.omr.homr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Файлы моделей homr (ONNX). */
class HomrModelFiles(val segnet: File, val encoder: File, val decoder: File) {
    fun exist() = encoder.isFile && decoder.isFile
}

/** Быстрый поиск линеек не справился, а модель сегментации не скачана. */
class SegnetMissingException : Exception("модель сегментации не скачана")

/** Результат по одной странице. */
class PageTokens(
    /** Сколько нотоносцев найдено на странице всего. */
    val stavesFound: Int,
    /** Токены выбранных нотоносцев в порядке чтения. */
    val staffs: List<List<EncodedSymbol>>,
    /** Диапазоны найденных нотоносцев (для подсветки/отладки). */
    val detected: List<DetectedStaff>,
    val selected: List<DetectedStaff>,
    /** Строки текста под каждым выбранным нотоносцем (пусто, если распознавание текста выключено). */
    val lyrics: List<List<String>> = emptyList(),
    /** Колонки тактов системы каждого выбранного нотоносца (для показа страницы исходника с указателем). */
    val columns: List<SysColumns?> = emptyList(),
    /** Слоги текста с положением на странице (по строкам текста) для каждого выбранного нотоносца; пусто — без положений. */
    val lyricSyls: List<List<List<Syl>>> = emptyList(),
)

/**
 * Конвейер homr на Kotlin: подготовка страницы → segnet → нотоносцы → трансформер.
 * Упрощения относительно оригинала: нет автообрезки фото и выпрямления линеек (страницы PDF прямые).
 */
class HomrPipeline(
    private val files: HomrModelFiles, threads: Int = 0, private val staffWorkers: Int = defaultWorkers(),
    /** Если задан — под каждым прочитанным нотоносцем распознаётся текст (слова песни). */
    private val text: TextRecognizer? = null,
    /** Потоков на один шаг декодера (0 — как [threads]). Шаг декодера мал, поэтому выгоднее 1 поток и несколько нотоносцев параллельно. */
    decoderThreads: Int = 0,
    /** Потоков на энкодер (0 — как [threads]). При параллельном чтении нотоносцев выгоднее 1. */
    encoderThreads: Int = 0,
    /** Энкодер — по одному прогону за раз (экономия памяти при параллельном чтении строк). */
    private val serialEncoder: Boolean = false,
    /** Способ запуска энкодера (ускоритель); файл модели должен ему соответствовать. null — обычные ядра процессора. */
    private val encoderBackend: EncoderBackend? = null,
) : AutoCloseable {
    private val encLock = Any()
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { if (threads > 0) setIntraOpNumThreads(threads) }
    private val decOpts = OrtSession.SessionOptions().apply {
        val n = if (decoderThreads > 0) decoderThreads else threads
        if (n > 0) setIntraOpNumThreads(n)
    }
    private val encOpts = OrtSession.SessionOptions().apply {
        val n = if (encoderThreads > 0) encoderThreads else threads
        if (n > 0) setIntraOpNumThreads(n)
        // Арена ORT растёт под каждый одновременный прогон и не отдаёт память; при параллельном чтении это сотни МБ
        if (System.getenv("HOMR_ARENA") != "1") setCPUArenaAllocator(false)
        encoderBackend?.configure(this, if (n > 0) n else Runtime.getRuntime().availableProcessors())
    }
    private val segSession by lazy { env.createSession(files.segnet.path, opts) }
    private val encSession by lazy { env.createSession(files.encoder.path, encOpts) }
    private val decSession by lazy { env.createSession(files.decoder.path, decOpts) }
    private val vocab = HomrVocab.fromResources()

    /** Время этапов последней страницы, мс (для профилирования и тестов). */
    val timings: MutableMap<String, Long> = java.util.Collections.synchronizedMap(LinkedHashMap())
    @Volatile var lastS2s: Staff2Score? = null
    /** Использовался ли быстрый поиск линеек без нейросети (последняя страница). */
    @Volatile var usedClassic = false
    private inline fun <T> timed(name: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try { return block() } finally { timings[name] = (timings[name] ?: 0L) + (System.nanoTime() - t0) / 1_000_000 }
    }

    /** Результат поиска нотоносцев: выровненная страница, нотоносцы и системы. */
    class Located(val page: GrayImage, val staves: List<DetectedStaff>, val systems: List<List<DetectedStaff>>, val angle: Double = 0.0)

    /**
     * Находит нотоносцы. Быстрый путь — без нейросети (выравнивание по тёмным пикселям + поиск длинных тонких линий);
     * если так ничего не найдено или включён [accurate], используется нейросеть сегментации.
     */
    fun locate(
        gray: GrayImage, accurate: Boolean,
        onProgress: (done: Int, total: Int, message: String) -> Unit, isCancelled: () -> Boolean,
    ): Located {
        var page = timed("prep") { ImageOps.clahe(ImageOps.resizeToHomrWidth(gray)) }
        if (!accurate) {
            val located = timed("classic") {
                val dark = ClassicStaffMask.dark(page)
                val slope = ClassicStaffMask.estimateSlope(dark, page.width, page.height)
                val angle = if (Math.abs(slope) > 0.005) Math.atan(slope) else 0.0
                val p = if (angle != 0.0) ImageOps.rotate(page, angle) else page
                val d = if (p === page) dark else ClassicStaffMask.dark(p)
                val staves = StaffDetector.detect(ClassicStaffMask.mask(p, d))
                if (staves.isEmpty()) null else Located(p, staves, StaffDetector.systems(staves, p), angle)
            }
            if (located != null) { usedClassic = true; return located }
        }
        usedClassic = false
        if (!files.segnet.isFile) throw SegnetMissingException()
        onProgress(0, 1, "Сегментация нот")
        val segnet = Segnet(env, segSession)
        fun segment(img: GrayImage) = timed("segnet") { segnet.segment(img) { d, t -> onProgress(0, 1, "Сегментация нот ($d/$t)"); if (isCancelled()) throw InterruptedException("Отменено") } }
        var seg = segment(page)
        val slope = timed("deskew") { StaffDetector.estimateSlope(seg) }
        val angle = if (Math.abs(slope) > 0.005) Math.atan(slope) else 0.0
        if (angle != 0.0) timed("deskew") { page = ImageOps.rotate(page, angle); seg = seg.rotated(angle) }
        val staves = timed("detect") { StaffDetector.detect(seg) }
        return Located(page, staves, if (staves.isEmpty()) emptyList() else StaffDetector.systems(staves, page), angle)
    }

    /** Шаги: сегментация (0..1), затем по одному на нотоносец. */
    fun recognizePage(
        gray: GrayImage, selection: StaffDetector.Staff,
        /** Если задан — номер нотоносца в системе (0 — верхний), вместо [selection]. Нужен для хоровых партитур S/A/T/B. */
        staffInSystem: Int? = null,
        accurate: Boolean = false,
        onProgress: (done: Int, total: Int, message: String) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
        /** Уже найденные нотоносцы (подготовлены заранее, пока читалась предыдущая страница). */
        located: Located? = null,
    ): PageTokens {
        timings.clear()
        onProgress(0, 1, "Подготовка страницы")
        val loc = located ?: locate(gray, accurate, onProgress, isCancelled)
        val page = loc.page; val staves = loc.staves; val systems = loc.systems
        if (staves.isEmpty()) return PageTokens(0, emptyList(), emptyList(), emptyList())
        val chosen = systems.mapNotNull { sys -> (staffInSystem?.takeIf { it < sys.size } ?: if (staffInSystem != null) null else StaffDetector.indexInSystem(selection, sys.size))?.let { sys[it] } }
        val s2s = Staff2Score(env, encSession, decSession, vocab, if (serialEncoder) encLock else null)
        val canvases = chosen.map { st -> staffCanvas(page, st, staves) }
        val result = predictAll(s2s, canvases, isCancelled) { done -> onProgress(done, chosen.size, "Чтение нотоносца $done из ${chosen.size}") }
        result.forEachIndexed { i, t -> mapToPage(t, chosen[i], staves) }
        val read = text?.let { rec ->
            onProgress(chosen.size, chosen.size, "Распознавание текста")
            chosen.map { st -> if (isCancelled()) throw InterruptedException("Отменено"); Lyrics.read(rec, page, st, nextStaffOf(st, staves)) }
        } ?: emptyList()
        val lyrics = read.map { it.first }
        onProgress(chosen.size, chosen.size, "Страница готова")
        val columns = chosen.map { st ->
            val sys = systems.firstOrNull { st in it } ?: listOf(st)
            Barlines.columns(page, st, sys, loc.angle, TokenParser.parse(listOf(result[chosen.indexOf(st)])).measures.size)
        }
        return PageTokens(staves.size, result, staves, chosen, lyrics, columns, read.map { it.second })
    }

    /**
     * Читает ВСЕ нотоносцы страницы (для хоровых партитур, где голос определяется по ключу и регистру).
     */
    fun recognizeAll(
        gray: GrayImage,
        accurate: Boolean = false,
        onProgress: (done: Int, total: Int, message: String) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
        located: Located? = null,
    ): List<List<StaffRead>> {
        timings.clear()
        onProgress(0, 1, "Подготовка страницы")
        val loc = located ?: locate(gray, accurate, onProgress, isCancelled)
        val page = loc.page; val staves = loc.staves; val systems = loc.systems
        if (staves.isEmpty()) return emptyList()
        val s2s = Staff2Score(env, encSession, decSession, vocab, if (serialEncoder) encLock else null)
        lastS2s = s2s
        val total = staves.size
        val jobs = ArrayList<Triple<Int, Int, DetectedStaff>>()           // система, номер в системе, нотоносец
        for ((si, sys) in systems.withIndex()) for ((i, st) in sys.withIndex()) jobs += Triple(si, i, st)
        val canvases = jobs.map { (_, _, st) -> timed("canvas") { staffCanvas(page, st, staves) } }
        val allTokens = timed("transformer") {
            predictAll(s2s, canvases, isCancelled) { done -> onProgress(done, total, "Чтение нотоносца $done из $total") }
        }
        allTokens.forEachIndexed { i, t -> mapToPage(t, jobs[i].third, staves) }
        val out = ArrayList<List<StaffRead>>()
        for ((si, sys) in systems.withIndex()) {
            val reads = ArrayList<StaffRead>()
            for ((i, st) in sys.withIndex()) {
                val tokens = allTokens[jobs.indexOfFirst { it.third === st }]
                val clef = tokens.firstOrNull { it.rhythm.startsWith("clef_") }?.rhythm?.getOrNull(5)
                val notes = tokens.filter { it.rhythm.startsWith("note_") }.mapNotNull { TokenParser.midi(it.pitch, it.lift) }.sorted()
                // начало строк в системе выровнено — берём медиану (в сканах край отдельной строки «плавает»)
                val leftEdge = sys.map { it.minX }.sorted().let { it[it.size / 2] }.toDouble()
                val octScore = StaffDetector.octaveMarkScore(page, st, sys.getOrNull(i + 1)?.minY, leftEdge)
                val oct = octScore > OCTAVE_MARK_THRESHOLD
                val txt = text?.let { rec -> Lyrics.read(rec, page, st, nextStaffOf(st, staves)) }
                reads += StaffRead(st, si, i, tokens, clef, oct && clef != 'F', notes.getOrNull(notes.size / 2), octScore, txt?.first.orEmpty(),
                    Barlines.columns(page, st, sys, loc.angle, TokenParser.parse(listOf(tokens)).measures.size), txt?.second.orEmpty())
            }
            out += reads
        }
        onProgress(total, total, "Страница готова")
        return out
    }

    /** Следующий по высоте нотоносец страницы (ограничивает область текста снизу). */
    private fun nextStaffOf(st: DetectedStaff, all: List<DetectedStaff>): DetectedStaff? =
        all.filter { it.minY > st.minY && it !== st }.minByOrNull { it.minY }

    /**
     * Читает нотоносцы (энкодер+декодер) в нескольких потоках: шаг декодера — много мелких операций, одному потоку
     * ядра не загрузить. Сессии ONNX Runtime потокобезопасны. Порядок результатов сохраняется.
     */
    /**
     * Быстрый просмотр партий: самая полная система страницы, у каждой строки читается только начало ([maxSteps] символов) —
     * хватает, чтобы узнать ключ, «8», примерную высоту и есть ли на строке два голоса.
     */
    fun scanSystem(gray: GrayImage, accurate: Boolean = false, maxSteps: Int = 80, isCancelled: () -> Boolean = { false }, located: Located? = null): List<StaffRead> {
        val loc = located ?: locate(gray, accurate, { _, _, _ -> }, isCancelled)
        val sys = loc.systems.maxByOrNull { it.size } ?: return emptyList()
        val s2s = Staff2Score(env, encSession, decSession, vocab, if (serialEncoder) encLock else null)
        val canvases = sys.map { staffCanvas(loc.page, it, loc.staves) }
        val tokens = predictAll(s2s, canvases, isCancelled, maxSteps)
        val leftEdge = sys.map { it.minX }.sorted().let { it[it.size / 2] }.toDouble()
        return sys.mapIndexed { i, st ->
            val t = tokens[i]
            val clef = t.firstOrNull { it.rhythm.startsWith("clef_") }?.rhythm?.getOrNull(5)
            val notes = t.filter { it.rhythm.startsWith("note_") }.mapNotNull { TokenParser.midi(it.pitch, it.lift) }.sorted()
            val oct = StaffDetector.octaveMarkScore(loc.page, st, sys.getOrNull(i + 1)?.minY, leftEdge)
            StaffRead(st, loc.systems.indexOf(sys), i, t, clef, oct > OCTAVE_MARK_THRESHOLD && clef != 'F', notes.getOrNull(notes.size / 2), oct)
        }
    }

    private fun predictAll(
        s2s: Staff2Score, canvases: List<GrayImage>, isCancelled: () -> Boolean, maxSteps: Int = Staff2Score.MAX_SEQ_LEN, onDone: (Int) -> Unit = {},
    ): List<List<EncodedSymbol>> {
        if (canvases.isEmpty()) return emptyList()
        val workers = staffWorkers.coerceIn(1, canvases.size)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        if (workers == 1) return canvases.map { c ->
            if (isCancelled()) throw InterruptedException("Отменено")
            s2s.predict(c, isCancelled, maxSteps).also { onDone(done.incrementAndGet()) }
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(workers) { r -> Thread(r, "homr-staff").also { it.isDaemon = true } }
        try {
            val futures = canvases.map { c -> pool.submit<List<EncodedSymbol>> {
                if (isCancelled()) throw InterruptedException("Отменено")
                s2s.predict(c, isCancelled, maxSteps).also { onDone(done.incrementAndGet()) }
            } }
            return futures.map {
                try { it.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
            }
        } finally { pool.shutdownNow() }
    }

    override fun close() {
        runCatching { segSession.close() }; runCatching { encSession.close() }; runCatching { decSession.close() }
        opts.close(); decOpts.close(); encOpts.close()
    }

    companion object {
        /**
         * Порог доли тёмных пикселей под ключом для признака «8». После отсева хвоста крупного ключа и слов подтекстовки
         * на 159 строках (5 изданий): у строк без «8» ≤ 0,011, у теноров с «8» ≥ 0,027 (бледная «8» в сканах).
         */
        const val OCTAVE_MARK_THRESHOLD = 0.02

        /**
         * Число потоков чтения нотоносцев. По замерам параллельное чтение не ускоряет: энкодер уже занимает все ядра,
         * а второй поток только конкурирует за них и память. Поэтому по умолчанию один.
         */
        fun defaultWorkers(): Int = 1

        /** Изображение строки для трансформера: 256×1280, белый фон, область по правилам homr (prepare_staff_image). */
        /** Перенос точки с холста строки (1280×256) обратно на страницу — обратная к [staffCanvas] разметка. */
        fun canvasToPage(staff: DetectedStaff, all: List<DetectedStaff>, vPad: Double = 4.0, hPad: Double = 2.0): (Double, Double) -> Pair<Double, Double> {
            val u = staff.unit
            val xMin = staff.minX - hPad * u; val xMax = staff.maxX + hPad * u
            val xm = (staff.minX + staff.maxX) / 2.0
            val topMid = staff.topAt(xm); val botMid = staff.bottomAt(xm)
            val above = all.filter { it.maxY < staff.minY + 1 && it !== staff && (it.minY + it.maxY) < (staff.minY + staff.maxY) }.maxOfOrNull { it.bottomAt(xm) }
            val below = all.filter { it !== staff && (it.minY + it.maxY) > (staff.minY + staff.maxY) }.minOfOrNull { it.topAt(xm) }
            val relMin = max(-vPad * u, (above ?: -1e9) - topMid)
            val relMax = min((botMid - topMid) + vPad * u, (below ?: 1e12) - topMid)
            val regionH = relMax - relMin; val regionW = xMax - xMin
            val (cw, ch) = canvasSize(regionH.toInt().coerceAtLeast(1), regionW.toInt().coerceAtLeast(1))
            val yOff = (Staff2Score.HEIGHT - ch) / 2
            return { cx, cy ->
                val sx = xMin + cx * regionW / cw
                val rel = relMin + (cy - yOff) * regionH / ch
                sx to ((staff.top?.at(sx) ?: topMid) + rel)
            }
        }

        /** Центры символов строки — с холста на страницу. */
        fun mapToPage(symbols: List<EncodedSymbol>, staff: DetectedStaff, all: List<DetectedStaff>) {
            val f = canvasToPage(staff, all)
            for (s in symbols) if (!s.x.isNaN() && !s.y.isNaN()) { val (px, py) = f(s.x, s.y); s.x = px; s.y = py }
        }

        fun staffCanvas(page: GrayImage, staff: DetectedStaff, all: List<DetectedStaff>, vPad: Double = 4.0, hPad: Double = 2.0): GrayImage {
            val u = staff.unit
            val xMin = staff.minX - hPad * u; val xMax = staff.maxX + hPad * u
            val xm = (staff.minX + staff.maxX) / 2.0
            val topMid = staff.topAt(xm); val botMid = staff.bottomAt(xm)
            val above = all.filter { it.maxY < staff.minY + 1 && it !== staff && (it.minY + it.maxY) < (staff.minY + staff.maxY) }.maxOfOrNull { it.bottomAt(xm) }
            val below = all.filter { it !== staff && (it.minY + it.maxY) > (staff.minY + staff.maxY) }.minOfOrNull { it.topAt(xm) }
            // область по вертикали — относительно верхней линейки (так строка «выпрямляется» вдоль её изгиба)
            val relMin = max(-vPad * u, (above ?: -1e9) - topMid)
            val relMax = min((botMid - topMid) + vPad * u, (below ?: 1e12) - topMid)
            val regionH = relMax - relMin; val regionW = xMax - xMin
            val (cw, ch) = canvasSize(regionH.toInt().coerceAtLeast(1), regionW.toInt().coerceAtLeast(1))
            val canvas = GrayImage(Staff2Score.WIDTH, Staff2Score.HEIGHT).also { java.util.Arrays.fill(it.data, 0xFF.toByte()) }
            val yOff = (Staff2Score.HEIGHT - ch) / 2
            val top = staff.top
            for (cy in 0 until ch) {
                val ty = cy + yOff
                if (ty < 0 || ty >= Staff2Score.HEIGHT) continue
                val rel = relMin + (cy + 0.5) * regionH / ch
                for (cx in 0 until min(cw, Staff2Score.WIDTH)) {
                    val sx = xMin + (cx + 0.5) * regionW / cw
                    val sy = (top?.at(sx) ?: topMid) + rel
                    canvas.data[ty * Staff2Score.WIDTH + cx] = sampleBilinear(page, sx - 0.5, sy - 0.5).toByte()
                }
            }
            return canvas
        }

        private fun sampleBilinear(img: GrayImage, x: Double, y: Double): Int {
            if (x < 0 || y < 0 || x > img.width - 1 || y > img.height - 1) return 255
            val x0 = x.toInt(); val y0 = y.toInt()
            val x1 = min(x0 + 1, img.width - 1); val y1 = min(y0 + 1, img.height - 1)
            val ax = x - x0; val ay = y - y0
            val v = (img[x0, y0] * (1 - ax) + img[x1, y0] * ax) * (1 - ay) + (img[x0, y1] * (1 - ax) + img[x1, y1] * ax) * ay
            return Math.round(v).toInt().coerceIn(0, 255)
        }

        /** (ширина, высота) изображения так, чтобы оно вписалось в 1280×256 (get_tr_omr_canvas_size). */
        fun canvasSize(height: Int, width: Int): Pair<Int, Int> {
            val ratio = Staff2Score.HEIGHT.toDouble() / Staff2Score.WIDTH
            return if (height.toDouble() / width > ratio) Pair((width.toDouble() / height * Staff2Score.HEIGHT).toInt().coerceAtLeast(1), Staff2Score.HEIGHT)
            else Pair(Staff2Score.WIDTH, (height.toDouble() / width * Staff2Score.WIDTH).toInt().coerceAtLeast(1))
        }
    }
}
