package ru.partitrainer.core.music

object BeatsFormat {
    /** 0.5 → «½», 1.5 → «1½», 2 → «2», 0.25 → «¼», 0.75 → «¾»; прочее — десятичное. */
    fun format(beats: Double): String {
        val whole = Math.floor(beats + 1e-9).toInt()
        val frac = beats - whole
        val f = when {
            Math.abs(frac) < 1e-6 -> ""
            Math.abs(frac - 0.5) < 1e-6 -> "½"
            Math.abs(frac - 0.25) < 1e-6 -> "¼"
            Math.abs(frac - 0.75) < 1e-6 -> "¾"
            Math.abs(frac - 1.0 / 3) < 1e-3 -> "⅓"
            Math.abs(frac - 2.0 / 3) < 1e-3 -> "⅔"
            else -> return String.format(java.util.Locale.US, "%.2f", beats).trimEnd('0').trimEnd('.')
        }
        return if (whole == 0 && f.isNotEmpty()) f else "$whole$f"
    }

    /** Ноты длиннее этого порога (в долях) помечаются «держать». */
    const val HOLD_THRESHOLD = 6.0
}
