package ru.partitrainer.omr.homr

/**
 * Распознаватель текста по картинке. На Android — Tesseract (язык «rus»), в тестах — заглушка.
 * Возвращает строки текста сверху вниз; пустые строки допустимы (будут отброшены).
 */
fun interface TextRecognizer {
    fun lines(image: GrayImage): List<String>

    /**
     * Строки текста по слогам с положением центра каждого слога (доля ширины картинки, 0..1).
     * null — распознаватель так не умеет (тогда используется [lines] и слоги раскладываются по порядку).
     */
    fun positioned(image: GrayImage): List<List<Pair<String, Float>>>? = null
}

/** Слог текста и где его центр на странице (пиксели выровненной страницы). */
class Syl(val text: String, val x: Double)

/**
 * Текст песни (слоги) под нотоносцем: вырезаем полосу между нотоносцем и следующим, распознаём,
 * разбиваем на слоги и раскладываем по нотам.
 */
object Lyrics {
    /** Полоса страницы под [staff], где обычно напечатан текст; null, если места нет. */
    fun band(page: GrayImage, staff: DetectedStaff, nextStaff: DetectedStaff?): Pair<Int, Int>? {
        val u = staff.unit
        val xs = (staff.minX..staff.maxX step 8).toList().ifEmpty { listOf(staff.minX) }
        val bottom = xs.maxOf { staff.bottomAt(it.toDouble()) }
        var y0 = bottom + 0.9 * u
        var y1 = y0 + 9.0 * u
        if (nextStaff != null) {
            val nx = (maxOf(staff.minX, nextStaff.minX)..minOf(staff.maxX, nextStaff.maxX) step 8).toList()
            val nextTop = if (nx.isEmpty()) nextStaff.minY.toDouble() else nx.minOf { nextStaff.topAt(it.toDouble()) }
            // над следующим нотоносцем остаётся место для лиг, динамики и штилей
            y1 = minOf(y1, nextTop - 0.5 * u)
        }
        y1 = minOf(y1, page.height.toDouble())
        y0 = maxOf(y0, 0.0)
        return if (y1 - y0 >= 1.3 * u) y0.toInt() to y1.toInt() else null
    }

    /** Картинка полосы с текстом (увеличена для OCR, поля добавлены). */
    fun bandImage(page: GrayImage, staff: DetectedStaff, nextStaff: DetectedStaff?): GrayImage? = bandWithMapping(page, staff, nextStaff)?.first

    private const val PAD = 10

    /** Картинка полосы и перевод x картинки в x страницы. */
    fun bandWithMapping(page: GrayImage, staff: DetectedStaff, nextStaff: DetectedStaff?): Pair<GrayImage, (Double) -> Double>? {
        val (y0, y1) = band(page, staff, nextStaff) ?: return null
        val x0 = (staff.minX - 4).coerceAtLeast(0); val x1 = (staff.maxX + 4).coerceAtMost(page.width)
        val crop = page.crop(x0, y0, x1, y1)
        if (crop.width < 40 || crop.height < 12) return null
        // Высота строчных букв должна быть ≥ ~20 px: масштабируем так, чтобы интервал нотоносца стал ≈ 24 px
        val k = (24.0 / staff.unit).coerceIn(1.0, 2.5)
        val w = (crop.width * k).toInt(); val h = (crop.height * k).toInt()
        val scaled = if (k > 1.05) ImageOps.resizeBicubic(crop, w, h) else crop
        val kk = scaled.width.toDouble() / crop.width
        // белая рамка — распознаватели хуже читают текст, прижатый к краю
        val pad = PAD
        val out = GrayImage(scaled.width + 2 * pad, scaled.height + 2 * pad)
        java.util.Arrays.fill(out.data, 255.toByte())
        for (y in 0 until scaled.height) System.arraycopy(scaled.data, y * scaled.width, out.data, (y + pad) * out.width + pad, scaled.width)
        return out to { xi -> x0 + (xi - pad) / kk }
    }

    /**
     * Текст под нотоносцем: строки (для раскладки по порядку) и, если распознаватель умеет, слоги с положением на странице.
     */
    fun read(rec: TextRecognizer, page: GrayImage, staff: DetectedStaff, nextStaff: DetectedStaff?): Pair<List<String>, List<List<Syl>>> {
        val (img, toPage) = bandWithMapping(page, staff, nextStaff) ?: return emptyList<String>() to emptyList()
        val pos = runCatching { rec.positioned(img) }.getOrNull()
        if (pos != null) {
            val lines = pos.map { line -> line.map { Syl(it.first.trim(), toPage(it.second.toDouble() * img.width)) }.filter { it.text.any(Char::isLetter) } }
                .filter { l -> isLyricLine(l.joinToString(" ") { it.text }) }
            return lines.map { l -> l.joinToString(" ") { it.text } } to lines
        }
        return rec.lines(img).map { it.trim() }.filter { isLyricLine(it) } to emptyList()
    }

    /**
     * Раскладка слогов по нотам по горизонтали: каждый слог — к ближайшей ноте, порядок сохраняется (динамическое
     * программирование). Нота без слога — распевка (мелизм); слог слишком далеко от нот отбрасывается.
     * [noteX] — центры «слоговых» нот на странице.
     */
    fun alignByX(syls: List<Syl>, noteX: List<Double>): List<String?> {
        val k = noteX.size; val m = syls.size
        if (k == 0) return emptyList()
        if (m == 0) return List(k) { null }
        val gaps = noteX.zipWithNext { a, b -> Math.abs(b - a) }.filter { it > 1 }.sorted()
        val step = gaps.getOrNull(gaps.size / 2) ?: 40.0
        val maxDist = 1.2 * step                 // дальше — уже не «под этой нотой»
        val skip = 1.5 * step                    // штраф за отброшенный слог
        val inf = Double.MAX_VALUE / 4
        // dp[i][j] — лучшая стоимость для первых i слогов на первых j нотах
        val dp = Array(m + 1) { DoubleArray(k + 1) { inf } }
        val from = Array(m + 1) { IntArray(k + 1) }          // 0 — нота пропущена, 1 — слог на ноту, 2 — слог отброшен
        for (j in 0..k) dp[0][j] = 0.0
        for (i in 1..m) {
            dp[i][0] = dp[i - 1][0] + skip; from[i][0] = 2
            for (j in 1..k) {
                var best = dp[i][j - 1]; var how = 0
                val d = Math.abs(syls[i - 1].x - noteX[j - 1])
                if (d <= maxDist && dp[i - 1][j - 1] + d < best) { best = dp[i - 1][j - 1] + d; how = 1 }
                if (dp[i - 1][j] + skip < best) { best = dp[i - 1][j] + skip; how = 2 }
                dp[i][j] = best; from[i][j] = how
            }
        }
        val out = arrayOfNulls<String>(k)
        var i = m; var j = k
        while (i > 0 && j >= 0) {
            when (if (j == 0) 2 else from[i][j]) {
                0 -> j--
                1 -> { out[j - 1] = syls[i - 1].text.trim('-', ' ').ifBlank { null }; i--; j-- }
                else -> i--
            }
        }
        return out.toList()
    }

    /** Распознаёт строки текста под нотоносцем; шум (динамика, цифры) отфильтровывается. */
    fun readLines(rec: TextRecognizer, page: GrayImage, staff: DetectedStaff, nextStaff: DetectedStaff?): List<String> {
        val img = bandImage(page, staff, nextStaff) ?: return emptyList()
        return rec.lines(img).map { it.trim() }.filter { isLyricLine(it) }
    }

    /** Похоже ли на строку текста: достаточно букв, а не одиночные значки («p», «mf», «cresc.», цифры). */
    fun isLyricLine(line: String): Boolean {
        val letters = line.count { it.isLetter() }
        if (letters < 2) return false
        val dyn = setOf("p", "pp", "ppp", "mp", "mf", "f", "ff", "fff", "cresc", "dim", "rit", "legato", "rall", "poco", "a tempo")
        val plain = line.lowercase().filter { it.isLetter() || it == ' ' }.trim()
        if (plain in dyn) return false
        return letters.toDouble() / line.count { !it.isWhitespace() }.coerceAtLeast(1) >= 0.5
    }

    private const val VOWELS = "аеёиоуыэюяaeiouyАЕЁИОУЫЭЮЯAEIOUY"
    private const val SOFT = "йьъЙЬЪ"
    private fun isVowel(c: Char) = c in VOWELS

    /** Слоги, как напечатаны: слово делится дефисами. Слово без дефиса — один слог. Цифры и значки отбрасываются. */
    fun printedSyllables(line: String): List<String> = words(line).flatMap { it }

    /** Слова строки: каждое — список слогов (по дефисам). */
    fun words(line: String): List<List<String>> {
        val norm = line.replace('—', '-').replace('–', '-').replace('‐', '-').replace('‑', '-').replace('−', '-')
            .replace('_', ' ').replace('~', ' ').replace('|', ' ')
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        fun flush() { if (cur.isNotEmpty()) out += cur; cur = ArrayList() }
        for (tok in norm.split(Regex("\\s+"))) {
            if (tok.isBlank()) continue
            val parts = tok.split('-')
            var any = false
            for (p in parts) {
                if (p.none { it.isLetter() }) continue
                cur += p.trim().trimStart('(', '"', '«', '\''); any = true
            }
            // слово заканчивается, если токен не оканчивается дефисом
            if (!tok.endsWith("-") || !any) flush()
        }
        flush()
        return out
    }

    /** Деление русского (и латинского) слова на слоги: правило «открытого слога» с поправками. */
    fun syllabify(word: String): List<String> {
        val v = word.indices.filter { isVowel(word[it]) }
        if (v.size < 2) return listOf(word)
        val cuts = ArrayList<Int>()
        for (k in 0 until v.size - 1) {
            var a = v[k] + 1
            val b = v[k + 1]
            while (a < b && word[a] in SOFT) a++           // й, ь, ъ остаются со своим слогом
            val cons = b - a
            val cut = when {
                cons <= 0 -> a
                cons == 1 -> a
                cons == 2 && word[a + 1].lowercaseChar() in "рл" && word[a].lowercaseChar() !in "рлнмй" -> a   // «по-пра-вить»
                else -> a + 1                                   // «гос-по-ди», «кон-церт»
            }
            cuts += cut
        }
        val res = ArrayList<String>()
        var from = 0
        for (c in cuts) { res += word.substring(from, c); from = c }
        res += word.substring(from)
        return res
    }

    /**
     * Слоги строки для [slots] нот. Если слов без дефисов много, а нот больше слогов — слова делятся автоматически
     * (берётся вариант, число слогов которого ближе к числу нот).
     */
    fun syllablesFor(lines: List<String>, slots: Int): List<String> {
        val ws = lines.flatMap { words(it) }
        val printed = ws.flatten()
        val auto = ws.flatMap { w -> if (w.size == 1) syllabify(w[0]) else w }
        return if (Math.abs(auto.size - slots) < Math.abs(printed.size - slots)) auto else printed
    }

    /** Раскладывает слоги по [slots] нотам по порядку; лишние слоги отбрасываются, недостающие остаются null. */
    fun align(syllables: List<String>, slots: Int): List<String?> =
        List(slots) { syllables.getOrNull(it)?.trim('-', ' ')?.ifBlank { null } }

    /**
     * Слоги для [TokenParser.parse]: по нотоносцу — первая строка текста под ним (остальные — другие куплеты),
     * разложенная по «слоговым» нотам нотоносца. Нотоносцы без текста получают пустой список.
     */
    fun forStaffs(tokens: List<List<EncodedSymbol>>, lines: List<List<String>>, syls: List<List<List<Syl>>> = emptyList()): List<List<String?>> =
        tokens.mapIndexed { i, t ->
            val ls = lines.getOrNull(i).orEmpty()
            if (ls.isEmpty() || t.isEmpty()) emptyList()
            else {
                val slots = TokenParser.syllableSlots(t)
                val positioned = syls.getOrNull(i)?.firstOrNull()
                val xs = slotX(t)
                if (positioned != null && positioned.isNotEmpty() && xs.size == slots && xs.none { it.isNaN() }) alignByX(positioned, xs)
                else align(syllablesFor(ls.take(1), slots), slots)
            }
        }

    /** Центры «слоговых» нот строки (как в [TokenParser.syllableSlots]) на странице. */
    fun slotX(staff: List<EncodedSymbol>): List<Double> {
        val out = ArrayList<Double>()
        var prevTied = false
        for (e in TokenParser.parse(listOf(staff)).measures.flatMap { it.events }) {
            if (e.midi == null) { prevTied = false; continue }
            if (!prevTied) out += e.x
            prevTied = e.tie
        }
        return out
    }
}
