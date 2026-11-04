package ru.partitrainer.omr.homr

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Минимальный разбор/сборка protobuf: только то, что нужно для правки моделей ONNX на устройстве. */
internal object Proto {
    class Field(val number: Int, val wire: Int, val varint: Long = 0, val bytes: ByteArray? = null, val fixed: Long = 0)

    fun parse(b: ByteArray): MutableList<Field> {
        val out = ArrayList<Field>()
        var p = 0
        fun varint(): Long {
            var r = 0L; var shift = 0
            while (true) {
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }
        while (p < b.size) {
            val key = varint()
            val num = (key ushr 3).toInt(); val wire = (key and 7).toInt()
            when (wire) {
                0 -> out += Field(num, 0, varint = varint())
                1 -> { var v = 0L; for (i in 0 until 8) v = v or ((b[p + i].toLong() and 0xFF) shl (8 * i)); p += 8; out += Field(num, 1, fixed = v) }
                2 -> { val len = varint().toInt(); out += Field(num, 2, bytes = b.copyOfRange(p, p + len)); p += len }
                5 -> { var v = 0L; for (i in 0 until 4) v = v or ((b[p + i].toLong() and 0xFF) shl (8 * i)); p += 4; out += Field(num, 5, fixed = v) }
                else -> throw IllegalArgumentException("Неизвестный тип поля protobuf: $wire")
            }
        }
        return out
    }

    private fun ByteArrayOutputStream.varint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) { write(x.toInt()); return }
            write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
        }
    }

    fun serialize(fields: List<Field>): ByteArray {
        val o = ByteArrayOutputStream()
        for (f in fields) {
            o.varint((f.number.toLong() shl 3) or f.wire.toLong())
            when (f.wire) {
                0 -> o.varint(f.varint)
                1 -> for (i in 0 until 8) o.write(((f.fixed ushr (8 * i)) and 0xFF).toInt())
                2 -> { o.varint(f.bytes!!.size.toLong()); o.write(f.bytes) }
                5 -> for (i in 0 until 4) o.write(((f.fixed ushr (8 * i)) and 0xFF).toInt())
            }
        }
        return o.toByteArray()
    }

    fun str(f: Field) = String(f.bytes!!, Charsets.UTF_8)
    fun bytes(n: Int, b: ByteArray) = Field(n, 2, bytes = b)
    fun string(n: Int, s: String) = Field(n, 2, bytes = s.toByteArray(Charsets.UTF_8))
    fun int(n: Int, v: Long) = Field(n, 0, varint = v)
}

/**
 * Перевод float-модели энкодера в половинную точность (fp16) прямо на устройстве.
 * Веса и константы — в fp16; вход и выход остаются float (на входе и выходе добавляются Cast), поэтому остальной код
 * не меняется. Подходит для простых графов (энкодер homr: Conv, MatMul, Add, Mul, Div, Erf, LayerNormalization,
 * Transpose, Reshape), для которых все операции принимают любой тип с плавающей точкой.
 */
object Fp16Converter {
    private const val FLOAT = 1L
    private const val FLOAT16 = 10L

    /** float32 → биты fp16 (округление к ближайшему чётному; переполнение — бесконечность). */
    fun toHalf(f: Float): Short {
        val x = java.lang.Float.floatToRawIntBits(f)
        val sign = (x ushr 16) and 0x8000
        var e = ((x ushr 23) and 0xFF) - 127 + 15
        var m = x and 0x7FFFFF
        if (((x ushr 23) and 0xFF) == 0xFF) return (sign or 0x7C00 or (if (m != 0) 0x200 else 0)).toShort() // NaN/Inf
        if (e >= 31) return (sign or 0x7C00).toShort()
        if (e <= 0) {
            if (e < -10) return sign.toShort()
            m = m or 0x800000
            val shift = 14 - e
            var hm = m ushr shift
            val rem = m and ((1 shl shift) - 1); val half = 1 shl (shift - 1)
            if (rem > half || (rem == half && (hm and 1) == 1)) hm++
            return (sign or hm).toShort()
        }
        var hm = m ushr 13
        val rem = m and 0x1FFF
        if (rem > 0x1000 || (rem == 0x1000 && (hm and 1) == 1)) { hm++; if (hm == 0x400) { hm = 0; e++ ; if (e >= 31) return (sign or 0x7C00).toShort() } }
        return (sign or (e shl 10) or hm).toShort()
    }

    /** TensorProto float → fp16; остальные тензоры не трогаем. */
    private fun convertTensor(t: ByteArray): ByteArray {
        val tf = Proto.parse(t)
        val type = tf.firstOrNull { it.number == 2 && it.wire == 0 }?.varint ?: return t
        if (type != FLOAT) return t
        val floats: FloatArray = tf.firstOrNull { it.number == 9 && it.wire == 2 }?.let { raw ->
            val fb = ByteBuffer.wrap(raw.bytes!!).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); FloatArray(fb.remaining()) { fb.get(it) }
        } ?: run {
            val packed = tf.firstOrNull { it.number == 4 && it.wire == 2 }
            if (packed != null) { val fb = ByteBuffer.wrap(packed.bytes!!).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); FloatArray(fb.remaining()) { fb.get(it) } }
            else tf.filter { it.number == 4 && it.wire == 5 }.map { java.lang.Float.intBitsToFloat(it.fixed.toInt()) }.toFloatArray()
        }
        val hb = ByteBuffer.allocate(floats.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in floats) hb.putShort(toHalf(v))
        val rest = tf.filter { it.number != 2 && it.number != 4 && it.number != 9 }.toMutableList()
        rest += Proto.int(2, FLOAT16)
        rest += Proto.bytes(9, hb.array())
        return Proto.serialize(rest)
    }

    /** NodeProto: тензорные атрибуты (Constant.value) — в fp16. */
    private fun convertNode(n: ByteArray): ByteArray {
        val nf = Proto.parse(n)
        val opType = nf.firstOrNull { it.number == 4 }?.let(Proto::str)
        require(opType != "Cast") { "В модели уже есть Cast — преобразование не поддержано" }
        return Proto.serialize(nf.map { f ->
            if (f.number != 5 || f.wire != 2) f else Proto.bytes(5, Proto.serialize(Proto.parse(f.bytes!!).map { a ->
                if (a.number == 5 && a.wire == 2) Proto.bytes(5, convertTensor(a.bytes!!)) else a     // AttributeProto.t
            }))
        })
    }

    private fun castNode(input: String, output: String, to: Long, name: String): Proto.Field {
        val attr = Proto.serialize(listOf(Proto.string(1, "to"), Proto.int(3, to), Proto.int(20, 2 /* INT */)))
        return Proto.bytes(1, Proto.serialize(listOf(Proto.string(1, input), Proto.string(2, output), Proto.string(3, name), Proto.string(4, "Cast"), Proto.bytes(5, attr))))
    }

    private fun renameInNode(n: ByteArray, from: String, to: String, inputs: Boolean): ByteArray =
        Proto.serialize(Proto.parse(n).map { f ->
            val field = if (inputs) 1 else 2
            if (f.number == field && f.wire == 2 && Proto.str(f) == from) Proto.string(field, to) else f
        })

    fun convert(model: ByteArray): ByteArray {
        val top = Proto.parse(model)
        val gi = top.indexOfFirst { it.number == 7 && it.wire == 2 }
        require(gi >= 0) { "В модели нет графа" }
        val graph = Proto.parse(top[gi].bytes!!)
        val inputs = graph.filter { it.number == 11 && it.wire == 2 }.map { Proto.str(Proto.parse(it.bytes!!).first { f -> f.number == 1 }) }
        val outputs = graph.filter { it.number == 12 && it.wire == 2 }.map { Proto.str(Proto.parse(it.bytes!!).first { f -> f.number == 1 }) }
        val initNames = graph.filter { it.number == 5 && it.wire == 2 }.map { Proto.parse(it.bytes!!).firstOrNull { f -> f.number == 8 }?.let(Proto::str) }.toSet()
        val realInputs = inputs.filter { it !in initNames }
        val newGraph = ArrayList<Proto.Field>()
        // Cast входов float → fp16
        for (i in realInputs) newGraph += castNode(i, "${i}__fp16", FLOAT16, "cast_in_$i")
        for (f in graph) {
            when {
                f.number == 1 && f.wire == 2 -> {
                    var nb = convertNode(f.bytes!!)
                    for (i in realInputs) nb = renameInNode(nb, i, "${i}__fp16", inputs = true)
                    for (o in outputs) nb = renameInNode(nb, o, "${o}__fp16", inputs = false)
                    // если выход графа сразу же используется другими узлами — им отдаём fp16-версию
                    for (o in outputs) nb = renameInNode(nb, o, "${o}__fp16", inputs = true)
                    newGraph += Proto.bytes(1, nb)
                }
                f.number == 5 && f.wire == 2 -> newGraph += Proto.bytes(5, convertTensor(f.bytes!!))
                f.number == 13 -> {}                                     // value_info: типы изменились — пусть ORT выведет их сам
                else -> newGraph += f
            }
        }
        for (o in outputs) newGraph += castNode("${o}__fp16", o, FLOAT, "cast_out_$o")
        val newTop = top.toMutableList()
        newTop[gi] = Proto.bytes(7, Proto.serialize(newGraph))
        return Proto.serialize(newTop)
    }
}
