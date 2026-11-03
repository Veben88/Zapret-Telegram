package ru.partitrainer.omr

import ru.partitrainer.core.omr.OmrParsed
import ru.partitrainer.data.EngineId

/**
 * Движок «На устройстве» (homr). ПРОБНЫЙ ЭТАП НЕ ПРОЙДЕН — см. docs/OMR_ON_DEVICE.md:
 * конвейер homr — это ~25 модулей классического CV на numpy/OpenCV (поиск линеек, выпрямление,
 * разбор нотоносцев) плюс 2 нейросети; нужны конвертация моделей в LiteRT/ONNX и полный порт на Kotlin.
 * Кроме того, homr/Andromr распространяются под AGPL-3.0, а лицензия этого проекта не выбрана.
 * Поэтому движок честно сообщает «недоступен», а интерфейс [OmrEngine] готов к подключению.
 */
class HomrOnDeviceEngine : OmrEngine {
    override val id = EngineId.ON_DEVICE

    override suspend fun availability(): Availability =
        Availability.Unavailable("Движок «На устройстве» ещё не готов: порт homr не завершён.")

    override suspend fun recognize(request: OmrRequest, onProgress: (OmrProgress) -> Unit): OmrParsed =
        throw OmrException("Движок «На устройстве» пока недоступен. Выберите «Claude Vision» в настройках или импортируйте MusicXML/MIDI.", offerManual = true)
}
