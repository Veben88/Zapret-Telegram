package ru.partitrainer.core.io

import ru.partitrainer.core.model.Voice
import java.io.ByteArrayOutputStream

/** Экспорт MIDI (формат 1: служебная дорожка + дорожка на голос). */
object MidiWriter {
    const val PPQ = 480

    fun write(title: String?, voices: List<Voice>, tempo: Int, timeNum: Int, timeDen: Int, program: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        out.write("MThd".toByteArray()); u32(6); u16(1); u16(voices.size + 1); u16(PPQ)

        fun track(body: ByteArray) { out.write("MTrk".toByteArray()); u32(body.size); out.write(body) }

        // Служебная дорожка
        val c = ByteArrayOutputStream()
        fun vlq(o: ByteArrayOutputStream, v: Long) {
            var x = v; val st = ArrayList<Int>(); st += (x and 0x7F).toInt(); x = x shr 7
            while (x > 0) { st += ((x and 0x7F) or 0x80).toInt(); x = x shr 7 }
            for (i in st.indices.reversed()) o.write(st[i])
        }
        if (!title.isNullOrBlank()) { val b = title.toByteArray(); vlq(c, 0); c.write(0xFF); c.write(0x03); vlq(c, b.size.toLong()); c.write(b) }
        val mpq = 60_000_000 / tempo.coerceIn(20, 300)
        vlq(c, 0); c.write(0xFF); c.write(0x51); c.write(3); c.write(mpq shr 16); c.write((mpq shr 8) and 0xFF); c.write(mpq and 0xFF)
        vlq(c, 0); c.write(0xFF); c.write(0x58); c.write(4); c.write(timeNum); c.write(Integer.numberOfTrailingZeros(timeDen)); c.write(24); c.write(8)
        vlq(c, 0); c.write(0xFF); c.write(0x2F); c.write(0)
        track(c.toByteArray())

        voices.forEachIndexed { vi, v ->
            val t = ByteArrayOutputStream()
            val ch = vi.coerceAtMost(8)
            val name = v.label.toByteArray()
            vlq(t, 0); t.write(0xFF); t.write(0x03); vlq(t, name.size.toLong()); t.write(name)
            vlq(t, 0); t.write(0xC0 or ch); t.write(program)
            data class E(val tick: Long, val on: Boolean, val key: Int)
            val evs = ArrayList<E>()
            v.notes.forEach { n ->
                val s = Math.round(n.startBeat * PPQ); val e = Math.round(n.endBeat * PPQ).coerceAtLeast(s + 1)
                evs += E(s, true, n.midi); evs += E(e, false, n.midi)
            }
            evs.sortWith(compareBy<E> { it.tick }.thenBy { it.on }) // выключения раньше включений
            var last = 0L
            for (e in evs) {
                vlq(t, e.tick - last); last = e.tick
                t.write((if (e.on) 0x90 else 0x80) or ch); t.write(e.key); t.write(if (e.on) 90 else 0)
            }
            vlq(t, 0); t.write(0xFF); t.write(0x2F); t.write(0)
            track(t.toByteArray())
        }
        return out.toByteArray()
    }
}
