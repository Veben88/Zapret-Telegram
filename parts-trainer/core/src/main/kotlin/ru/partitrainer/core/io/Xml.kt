package ru.partitrainer.core.io

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

internal fun newParser(input: InputStream): XmlPullParser {
    val f = XmlPullParserFactory.newInstance()
    val p = f.newPullParser()
    // Внешние сущности/DTD не загружаем (безопасность).
    try { p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (_: Exception) {}
    p.setInput(input, null)
    return p
}

/** Читает текст текущего элемента до его закрывающего тега. */
internal fun XmlPullParser.readText(): String {
    var r = ""
    if (next() == XmlPullParser.TEXT) { r = text; nextTag() }
    return r.trim()
}

/** Пропускает элемент целиком (парсер стоит на START_TAG). */
internal fun XmlPullParser.skipElement() {
    check(eventType == XmlPullParser.START_TAG)
    var depth = 1
    while (depth != 0) {
        when (next()) {
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}
