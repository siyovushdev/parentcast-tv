# Уведомления сторонних компонентов M0

Проект использует зависимости как внешние артефакты; их исходный код не скопирован в этот
репозиторий. При передаче APK необходимо сохранять применимые лицензии и уведомления.

| Компонент | Версия | Лицензия | Первичный источник |
|---|---|---|---|
| NewPipe Extractor | `v0.26.5`, commit `f9e6bb808f82bf3e4dc1f2a29a16fd376931c8ef` | GPL-3.0-or-later | https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5 |
| AndroidX Media3 Exoplayer/UI | `1.5.1` | Apache-2.0 | https://github.com/androidx/media/tree/1.5.1 |
| OkHttp | `4.12.0` | Apache-2.0 | https://github.com/square/okhttp/tree/parent-4.12.0 |
| Kotlin stdlib / coroutines | `2.0.21` / `1.9.0` | Apache-2.0 | https://github.com/JetBrains/kotlin и https://github.com/Kotlin/kotlinx.coroutines |

Полный текст GPL-3.0 из выбранного тега NewPipe Extractor и текст Apache-2.0 находятся в
`app/src/main/assets/licenses/` и упаковываются в APK. NewPipe Extractor не перелицензируется:
его свободные условия, включая обязательства при распространении производной работы, сохраняются.
Публичная публикация APK в M0 не выполняется.

Транзитивные зависимости остаются под собственными лицензиями. Их точные координаты можно
получить командой `./gradlew app:dependencies --configuration debugRuntimeClasspath`.
Тестовые JUnit 4.13.2, kotlinx-coroutines-test 1.9.0 и org.json 20240303 не включаются в debug APK
как runtime-компоненты приложения.
