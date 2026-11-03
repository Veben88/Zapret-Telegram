# Сторонние компоненты и лицензии

Проект ещё не выбрал собственную лицензию; все зависимости ниже допускают распространение под Apache-2.0, MIT
и аналогичными разрешительными лицензиями. **GPL/AGPL-зависимостей нет.**

| Компонент | Версия | Лицензия | Где используется |
|---|---|---|---|
| Kotlin stdlib, kotlinx.coroutines | 2.0.21 / 1.9.0 | Apache-2.0 | всё приложение |
| kotlinx.serialization | 1.7.3 | Apache-2.0 | JSON моделей и ответов Claude |
| Jetpack Compose (ui, material3, icons, navigation) | BOM 2024.12.01 | Apache-2.0 | интерфейс |
| AndroidX Core, Lifecycle, Activity, ExifInterface | см. `libs.versions.toml` | Apache-2.0 | |
| Room | 2.6.1 | Apache-2.0 | проекты, кэш распознавания |
| DataStore | 1.1.1 | Apache-2.0 | настройки |
| WorkManager | 2.10.0 | Apache-2.0 | распознавание в фоне |
| kXML2 (только тесты `:core`) | 2.3.0 | BSD-style (Public Domain / BSD) | парсер XML в JVM-тестах |
| xmlpull API (compileOnly) | 1.1.3.1 | Public Domain | API парсера; на Android — из платформы |
| JUnit (только тесты) | 4.13.2 | EPL-1.0 | тесты |

Написано с нуля (без сторонних библиотек): определение высоты тона (YIN), синтезатор на `AudioTrack`, разбор
MusicXML/MXL (`XmlPullParser`, `ZipInputStream`) и MIDI (SMF), экспорт MIDI/MusicXML, раскрытие повторов.

## Не включено намеренно
* **TarsosDSP** — GPL-3.0 (определение высоты написано самостоятельно).
* **homr / Andromr** — AGPL-3.0 (см. `docs/OMR_ON_DEVICE.md`).
