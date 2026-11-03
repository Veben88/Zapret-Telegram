package ru.partitrainer.omr

import java.io.File

/**
 * Число потоков для ONNX Runtime. На телефонах с big.LITTLE (например 4+4) потоки на «маленьких» ядрах тормозят
 * весь вычислительный пул, поэтому берём только производительные ядра: те, чья максимальная частота близка к наибольшей.
 */
object CpuTopology {
    fun performanceThreads(): Int {
        val all = Runtime.getRuntime().availableProcessors()
        return try {
            val freqs = (0 until all).mapNotNull { i ->
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
            }
            if (freqs.size < all) return all.coerceAtLeast(1)
            val max = freqs.max()
            val fast = freqs.count { it >= max * 0.85 }
            // ядра с максимальной частотой могут быть одним «прайм»-ядром: тогда берём и «большие» (≥ 70 %)
            val n = if (fast < 3) freqs.count { it >= max * 0.70 } else fast
            n.coerceIn(2, 8).coerceAtMost(all)
        } catch (e: Exception) {
            all.coerceAtLeast(1)
        }
    }
}
