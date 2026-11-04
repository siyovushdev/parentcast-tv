# Защита очереди от дублей — 2026-11-04

IN SCOPE: tasks/Queue-deduplication.md. Ветка codex/queue-deduplication от5ba1a51,
stacked к Draft PR #10. Обычная debug-сборкаv8, без Merge/Release/M4.

## Поведение

Все способы добавления проверяют уже находящиеся в очереди videoId. Список новых элементов
фильтруется атомарно вместе с queueVersion и receipt; повторы внутри запроса тоже пропускаются.
Текущий ролик, история, позиция/PLAY/PAUSE/STOP и порядок существующих строк не меняются.
При no-op версия очереди не растёт; receipt с точными added/skipped всё равно сохраняется,
поэтому повтор потерянного ответа не меняет более позднюю очередь. Не используется временное
окно: новый ключ, другой браузер, плейлист → подборка тоже не создают повтор.

Обычные ссылки теперь используют тот же атомарный batch. Legacy M1 ответы без fingerprint
остаются replayable; новые команды проверяют fingerprint. Формат queuev2 совместим, новые
счётчики receipt необязательны при чтении. Существующие дубли в старых данных автоматически
не удаляются. После удаления записи/очистки родитель может добавить ID новой командой снова.
Собственные файлы различаются по их local-id; дедупликация содержимого разных импортов не добавлялась.

PLAY_NOW для существующего ID выбирает прежнюю строку с начала и не вставляет новую копию.
Новый PLAY_NOW сохраняет прежнее поведение. UI блокирует отправку во время запроса и показывает
«Уже в очереди» для полностью добавленного выбора/подборки; при частичном совпадении сообщает
точные added/skipped. «Сохранить как подборку» по-прежнему не добавляет в очередь.

## Проверки

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/siovush/Android/Sdk \
  ./gradlew testDebugUnitTest assembleDebug lintDebug compileReleaseKotlin --no-daemon
node --check app/src/main/assets/web/app.js
node --check app/src/main/assets/web/media.js
node tools/m2_web_contract_test.mjs
node tools/local_media_web_test.mjs
node tools/prepared_web_test.mjs
node tools/pin_login_web_test.mjs
```

230 JVM tests PASS,0 failures/errors; debug/release Kotlin PASS; lint0 errors/50 warnings.
Две syntax и четыре JS contracts PASS, diff whitespace PASS. Добавлены проверки повторов
в текущем/истории/local, входных повторов/порядка, no-op version и сохранения позиции/PAUSE,
receipt после codec/restart, stale/conflict, playlist → collection из другого браузера,
URL-алиасов одного ID, legacy M1 replay, переиспользования строки PLAY_NOW. Синтетический DOM
проверяет двойное нажатие во время запроса, disabled и точные сообщения; это не браузерная приёмка.

## H728

Цель подтверждена SoC h728, Android14, serial…84201a. Другая rk3568 не использовалась.
Перед update сохранены приватные baseline/queue snapshot с режимом600 вне Git; текущий PAUSE,
38 уникальных строк. Подготовка кратко приостановлена штатной API-командой с сохранением partial.

| Проверка | Статус | Свидетельство |
|---|---|---|
| Update-r v7→v8 | PASS | Прежний debug signer/applicationId,38 исходных queueItemId/videoId/order/current/position/PAUSE сохранены |
| Та же сохранённая подборка | PASS | Реальный API: added0/skipped38;38 строк и queueVersion не изменились |
| Повтор ссылок | PASS | Две ссылки на существующий ID: added0/skipped2, версия не изменилась |
| Смешанный список/повтор команды | PASS | added1/skipped3,39 строк, повтор не вставил вторую новую строку; PAUSE/current/position сохранены |
| Stale queueVersion | PASS |409, новых изменений нет |
| Replay после process restart | PASS | Force-stop/start только ParentCast; ответ no-op added0/skipped1 сохранён, очередь38 |
| Сохранность | PASS | Временная строка удалена через API; исходные38 IDs/order/current/position/PAUSE сохранены, три SHA256 auth/session/tokens совпали; подготовка возобновлена с сохранёнными байтами |
| Web assets на устройстве | PASS | Отданные настоящим HTTP app.js/media.js побайтно совпали с исходниками APK |
| Реальный Chrome UI | NOT_RUN | CUA выдаёт внутреннюю ошибку timeoutMs до чтения DOM/снимка; сброс инструмента не помог. Не подменено synthetic DOM |

Happ, PIN/cookies/tokens, HOME, системные настройки, Media3/yt-dlp не менялись. Готовые файлы
не удалялись. Никаких reboot/privileged/DO. Новые длинные YouTube/USB испытания не повторялись:
изменение касается вставки в очередь. Итоговые свидетельства в игнорируемом artifacts/queue-dedup:
h728-existing.json, h728-mixed-restart.json, final-state.json. Приватный baseline в .dev/queue-dedup.

## APK

artifacts/queue-dedup/parentcast-tv-v0.1-queue-dedup-debug.apk
SHA-256: 09d5763bbf47bca941ed7d36fa23837b112af4020ae2f667677b8142eca4a698
ApplicationId dev.siyovush.parentcast.tv.debug; versionCode8; versionName0.1.0-queue-beta-debug.
APK signing verification PASS, certificate SHA256
 de2cf22c47b6e4039da04107631b31d92ecddeb6984eeae5d43107337cceecf8.
Обычная сборка; platform-ключ не использован. Следующий шаг владельца — обновить страницу
пульта и проверить подписи «Уже в очереди» на своей подборке; дополнительные подтверждения
для обычного использования не нужны.
