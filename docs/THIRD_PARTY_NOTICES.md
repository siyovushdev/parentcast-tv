# Уведомления сторонних компонентов M0–M1

Проект использует зависимости как внешние артефакты; их исходный код не скопирован в этот
репозиторий. При передаче APK необходимо сохранять применимые лицензии и уведомления.

| Компонент | Версия | Лицензия | Первичный источник |
|---|---|---|---|
| NewPipe Extractor | `v0.26.5`, commit `f9e6bb808f82bf3e4dc1f2a29a16fd376931c8ef` | GPL-3.0-or-later | https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5 |
| youtubedl-android library/common | `0.18.1` | GPL-3.0 | https://github.com/yausername/youtubedl-android |
| yt-dlp zipapp | `2026.10.26`, SHA-256 `e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3` | Unlicense | https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26 |
| yt-dlp-ejs | `0.8.0` | Unlicense; bundled MIT/ISC components | https://github.com/yt-dlp/ejs |
| QuickJS | `2025-04-26` | MIT | https://github.com/bellard/quickjs |
| AndroidX Media3 Exoplayer/UI | `1.5.1` | Apache-2.0 | https://github.com/androidx/media/tree/1.5.1 |
| Jackson databind | `2.11.1` | Apache-2.0 | https://github.com/FasterXML/jackson-databind |
| Commons IO | `2.5` | Apache-2.0 | https://github.com/apache/commons-io |
| OkHttp | `4.12.0` | Apache-2.0 | https://github.com/square/okhttp/tree/parent-4.12.0 |
| Kotlin stdlib / coroutines | `2.0.21` / `1.9.0` | Apache-2.0 | https://github.com/JetBrains/kotlin и https://github.com/Kotlin/kotlinx.coroutines |
| NanoHTTPD | `2.3.1` | BSD-3-Clause | https://github.com/NanoHttpd/nanohttpd |

Полные тексты лицензий находятся в `app/src/main/assets/licenses/` и упаковываются в APK,
включая GPL-3.0, Unlicense, MIT, ISC, BSD-3-Clause и Apache-2.0. NewPipe Extractor и
youtubedl-android не перелицензируются; их свободные условия, включая обязательства при
распространении производной работы, сохраняются. Публичный релиз APK не выполняется.

Транзитивные зависимости остаются под собственными лицензиями. Их точные координаты можно
получить командой `./gradlew app:dependencies --configuration debugRuntimeClasspath`.
Тестовые JUnit 4.13.2, kotlinx-coroutines-test 1.9.0 и org.json 20240303 не включаются в debug APK
как runtime-компоненты приложения.

## Домашний пульт

- ZXing Core3.5.4 — Apache-2.0, https://github.com/zxing/zxing/tree/zxing-3.5.4 .
  Используется только генератор/проверка QR в приложении; отдельное приложение Barcode Scanner не устанавливается.
- JmDNS3.6.5 — Apache-2.0, https://github.com/jmdns/jmdns . Объявление собственного локального
  имени/HTTP-сервиса. Уведомление JmDNS сохранено в assets/licenses/JmDNS-NOTICE.txt.
- Транзитивный SLF4J API2.0.7 — MIT, https://github.com/qos-ch/slf4j . Точные версии — Gradle runtime classpath.
  Тексты лицензий, присутствующие в опубликованных JAR, дополнительно сохранены в assets/licenses.
