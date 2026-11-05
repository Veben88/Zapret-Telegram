package ru.partitrainer.omr.homr

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * Профиль распознавания одной страницы (или одного документа): время этапов и пиковая память.
 *
 * Время этапа — сумма длительностей его вызовов; у этапов, идущих в нескольких потоках сразу (энкодер, декодер), это сумма
 * по потокам, а не время по часам — поэтому рядом пишется [wallStages] («по часам»). Память — по [MemoryWatch]: размер
 * процесса в ОЗУ (VmRSS из /proc/self/status, включает память нейросетей ONNX Runtime вне кучи Java) и занятая куча Java;
 * пик берётся по замерам каждые 50 мс, отдельно для каждого этапа, пока он идёт.
 */
class OmrProfile(val label: String = "") {
    private val nanos = ConcurrentHashMap<String, LongAdder>()
    private val calls = ConcurrentHashMap<String, LongAdder>()
    private val stagePeakRss = ConcurrentHashMap<String, AtomicLong>()
    private val active = ConcurrentHashMap<String, Int>()
    /** Этапы, время которых — «по часам» (обёртка над параллельной работой). */
    val wallStages: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val peakRssKb = AtomicLong(0)
    val peakHeapBytes = AtomicLong(0)
    /** Произвольные сведения (число нотоносцев, окон SegNet и т. п.). */
    val info: MutableMap<String, String> = ConcurrentHashMap()

    /** Выполнить [block] как этап [name]: время и пиковая память этапа. */
    fun <T> stage(name: String, wall: Boolean = false, block: () -> T): T {
        val t0 = begin(name, wall)
        try { return block() } finally { end(name, t0) }
    }

    /** Начало этапа (для кода, который нельзя обернуть в [stage], например suspend); вернуть значение в [end]. */
    fun begin(name: String, wall: Boolean = false): Long {
        if (wall) wallStages += name
        active.merge(name, 1, Int::plus)
        MemoryWatch.register(this)
        observe(MemoryWatch.rssKb(), MemoryWatch.heapBytes())       // короткие этапы — хотя бы один замер
        return System.nanoTime()
    }

    fun end(name: String, t0: Long) {
        add(name, System.nanoTime() - t0)
        observe(MemoryWatch.rssKb(), MemoryWatch.heapBytes())
        active.compute(name) { _, n -> if (n == null || n <= 1) null else n - 1 }
        MemoryWatch.unregister(this)
    }

    fun add(name: String, ns: Long) {
        nanos.getOrPut(name) { LongAdder() }.add(ns)
        calls.getOrPut(name) { LongAdder() }.increment()
    }

    internal fun observe(rssKb: Long, heap: Long) {
        if (rssKb > 0) {
            peakRssKb.accumulateAndGet(rssKb, ::maxOf)
            for (s in active.keys) stagePeakRss.getOrPut(s) { AtomicLong(0) }.accumulateAndGet(rssKb, ::maxOf)
        }
        if (heap > 0) peakHeapBytes.accumulateAndGet(heap, ::maxOf)
    }

    /** Время этапов, мс, в порядке первого появления. */
    fun ms(): Map<String, Long> = LinkedHashMap<String, Long>().also { m -> for ((k, v) in nanos) m[k] = v.sum() / 1_000_000 }
    fun calls(name: String): Long = calls[name]?.sum() ?: 0
    fun stagePeakRssKb(): Map<String, Long> = stagePeakRss.mapValues { it.value.get() }

    /** Добавить к себе другой профиль (страницы → документ). */
    fun merge(other: OmrProfile) {
        for ((k, v) in other.nanos) nanos.getOrPut(k) { LongAdder() }.add(v.sum())
        for ((k, v) in other.calls) calls.getOrPut(k) { LongAdder() }.add(v.sum())
        for ((k, v) in other.stagePeakRss) stagePeakRss.getOrPut(k) { AtomicLong(0) }.accumulateAndGet(v.get(), ::maxOf)
        wallStages += other.wallStages
        peakRssKb.accumulateAndGet(other.peakRssKb.get(), ::maxOf)
        peakHeapBytes.accumulateAndGet(other.peakHeapBytes.get(), ::maxOf)
    }

    /** Строка JSON (без сторонних библиотек: :omr не зависит от kotlinx.serialization в рантайме Android-кода). */
    fun toJson(): String {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val ms = ms()
        val stages = ms.entries.joinToString(",") { (k, v) ->
            "${q(k)}:{\"ms\":$v,\"calls\":${calls(k)},\"wall\":${k in wallStages},\"peakRssKb\":${stagePeakRss[k]?.get() ?: 0}}"
        }
        val inf = info.entries.joinToString(",") { "${q(it.key)}:${q(it.value)}" }
        return "{\"label\":${q(label)},\"stages\":{$stages},\"peakRssKb\":${peakRssKb.get()},\"peakHeapMb\":${peakHeapBytes.get() / (1 shl 20)},\"info\":{$inf}}"
    }
}

/**
 * Замер памяти процесса каждые 50 мс, пока есть хоть один открытый этап профиля. Поток один на процесс и засыпает, когда
 * этапов нет.
 */
object MemoryWatch {
    private const val PERIOD_MS = 50L
    private val profiles = ConcurrentHashMap<OmrProfile, Int>()
    @Volatile private var thread: Thread? = null
    private val statusFile = File("/proc/self/status")

    /** Размер процесса в ОЗУ, КБ (0 — если недоступно, например не Linux). */
    fun rssKb(): Long = readStatus("VmRSS:")
    /** Наибольший размер процесса в ОЗУ за всё время его жизни, КБ. */
    fun processPeakRssKb(): Long = readStatus("VmHWM:")
    fun heapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun readStatus(key: String): Long = try {
        statusFile.useLines { lines -> lines.firstOrNull { it.startsWith(key) }?.substring(key.length)?.trim()?.substringBefore(' ')?.toLongOrNull() ?: 0L }
    } catch (_: Exception) { 0L }

    internal fun register(p: OmrProfile) {
        profiles.merge(p, 1, Int::plus)
        if (thread == null) synchronized(this) {
            if (thread == null) thread = Thread({ loop() }, "omr-memwatch").also { it.isDaemon = true; it.start() }
        }
    }

    internal fun unregister(p: OmrProfile) { profiles.compute(p) { _, n -> if (n == null || n <= 1) null else n - 1 } }

    private fun loop() {
        while (true) {
            try { Thread.sleep(if (profiles.isEmpty()) 1000L else PERIOD_MS) } catch (_: InterruptedException) { return }
            if (profiles.isEmpty()) continue
            val rss = rssKb(); val heap = heapBytes()
            for (p in profiles.keys) p.observe(rss, heap)
        }
    }
}
