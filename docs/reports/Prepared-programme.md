# Подготовленная программа — реализация и приёмка 2026-11-03

Отдельно разрешённое расширение HOME BETA: `tasks/Prepared-programme.md`.
Ветка `codex/prepared-programme`, база Draft PR #7 `codex/pin-only-browser-auth`
(2f0e90f1bd2fc45aba5c40c14c17c3a98d364ce4). M4/privileged не входят. Merge не выполнять.
Предыдущий незавершённый buffering WIP сохранён в git stash на прежней ветке.

## Что реализовано

Родитель явно включает подготовку оставшейся очереди в защищённой веб-панели.
Очередь/сохранённые подборки остаются фиксированными videoId. Подготовка не вызывает PLAY,
не меняет queueVersion и порядок. Пока идёт локальный просмотр, служба dataSync готовит
следующие выбранные ролики по одному. Повторяющийся videoId использует один готовый файл.

Файлы и атомарный индекс находятся только в `no_backup/prepared-programme`.
Полные подписанные media URL и cookies в индекс/API не попадают. READY требует полного
Content-Length/EOF, проверки MediaExtractor (H.264/AAC, длительность), размеров и SHA-256.
SHA/размер повторно проверяются перед локальным открытием. `.part` не воспроизводятся.
Поддерживаются публичные обычные VOD с подтверждёнными availability=public/live_status=not_live;
неизвестная доступность, unlisted/private, live, неизвестная длина HTTP-ответа отвергаются.

В локальном режиме source ждёт READY без сетевого fallback. Media3 DefaultDataSource
открывает private file URI; разрешены только фиксированные имена файлов внутри каталога.
Для загрузки разрешён HTTPS443 только поддоменов googlevideo.com, включая каждое
перенаправление; запрос к произвольному URL от родителя невозможен. Отложенный READY после
STOP не запускает плеер. NEXT/удаление пропускаемого элемента отменяют ненужную подготовку.
Первый preview frame больше не объявляет PLAYING до готовности Media3. Технический текст
буферизации скрыт на детской поверхности при локальном старте, реальный статус доступен родителю.

Медиаданные ограничены2GiB, резерв свободного места1GiB. Готовые текущие/будущие элементы
защищены от очистки. Просмотренные/исключённые сохраняются до ручной очистки либо давления
лимита. Ошибки места/авторизации останавливают подготовку с явной причиной; есть retry/пауза.
Отмена закрывает HTTP call и удаляет неполные файлы; socket IOException при отмене не становится
ложной ошибкой загрузки. После смерти процесса READY восстанавливается, incomplete начинается
заново при продолжении. Служба не экспортирована; отказ Android в её старте/timeout ставит паузу.

Новые API используют прежние bearer/Host/Origin/Referer/custom-header guards, idempotency
и fingerprint; ENABLE проверяет queueVersion. Названия выводятся textContent. PIN/токены
не менялись; Android-пульт, extractor и prefetch используют общую сессию процесса, чтобы
штатный импорт/отключение cookies отражались во всех путях. Версии yt-dlp/Media3 прежние.

## Локальные проверки

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/siovush/Android/Sdk \
  ./gradlew testDebugUnitTest assembleDebug lintDebug compileReleaseKotlin --no-daemon
node --check app/src/main/assets/web/app.js
node tools/prepared_web_test.mjs
node tools/pin_login_web_test.mjs
node tools/m2_web_contract_test.mjs
git diff --check
```

PASS:189 tests, 0 failures/errors/skips; lint0 errors/44 warnings, debug APK и release Kotlin.
Новые детерминированные проверки: усечённый/слишком длинный/unknown-length ответ, лимит/резерв,
отмена/IOException, SHA corruption, порядок/повтор ID, восстановление partial/READY, очистка,
поздний READY после STOP, отмена исключённого элемента перед следующим, API защита/конфликт/
повтор/fingerprint и XSS-safe frontend. Фальшивые media-файлы JVM не объявлены YouTube-тестом.

## H728 и реальный браузер Ubuntu

Цель: SoC h728, Android14, hardware serial21c7406c7086084201a. Другая rk3568 не использовалась.
В начале задания пакет и данные ParentCast отсутствовали. Владелец подтвердил чистую установку;
удалённые ранее данные не объявляются сохранёнными. Новый тестовый PIN создан штатным native UI,
значение хранится только в ignored `.dev/prepared/test-pin` (600). На чистой установке YouTube
потребовал bot-confirmation: первоначальная загрузка FAIL, файлы не стали READY. Затем владелец
предоставил cookies-файл: непрозрачная передача прямо в app-private storage и штатный импорт,
значения не выводились. Сессия оставлена owner_enabled=true, файл600; не удалялся.

| Сценарий | Статус | Свидетельство |
|---|---|---|
| Реальная полная подготовка | PASS | jNQXAC9IVRw, Me at the zoo: video+audio, всего742369 bytes, проверка обоих контейнеров и SHA, READY |
| Локальный показ | PASS | Media3:320x240 avc1.4D400C/mp4a.40.2; localPlayback=true; позиции0→6s и далее, кадр на TV |
| Естественный конец/следующий | PASS | Два последовательных элемента того же videoId сыграли до конца; индекс2→3→4, следующий неподготовленный ждёт |
| STOP во время ожидания | PASS | PLAY→LOADING248ms, STOP→STOPPED275ms; поздняя готовность не вызвала PLAY |
| Пауза/неполные файлы | PASS | Частичная загрузка Sintel eRsGyueVLvQ отменена; .part удалён, статус WAITING без error, готовый короткий файл остался |
| Перезапуск/обновление без очистки | PASS | READY2/3 восстановлены; PIN/tokens/session/queue hashes совпали до/после, готовое воспроизводится повторно |
| Web UI | PASS | Реальный встроенный браузер Codex на Ubuntu через localhost8765/ADB-forward: PIN-вход, reload, PLAY/STOP, прогресс, cleanup; прямой LAN API также PASS |
| Chrome automation | NOT_RUN | CDP navigation/focus timeout, использован встроенный браузер; результат не назван Chrome-проверкой |
| Лимит2GiB/низкое место/повреждение на устройстве | NOT_RUN | Проверено JVM, приставку искусственно не заполняли и готовые реальные файлы не повреждали |
| Полное HD-видео Sintel | NOT_RUN | Тест использовал загрузку metadata/части файла и отмену; полный второй уникальный ролик не объявлен готовым |
| Физическое отключение интернета / reboot / Android15 timeout | NOT_RUN | Сеть/прошивка не менялись, file playback не подменяет эти испытания |

Отдельная проверка на финальном APK: локальная позиция6283→13749ms, одновременно объём
следующего DOWNLOADING352256→1761280bytes. Затем NEXT из ожидания незавершённого Sintel
принят за390ms и следующий готовый файл показал PLAYING/localPlayback=true; позиция выросла
до3248ms. Передача отменена отдельной командой PAUSE: в той очереди оставался ещё один выбранный
экземпляр Sintel, поэтому NEXT сохранял его фоновую подготовку. В отдельной очереди без
такого дубликата NEXT принят за418ms, исключил загружаемый videoId и запустил готовый
(localPlayback=true, READY1/1). При этом выявлена задержка завершения blocking execute yt-dlp:
completion-handler заменён отменяемым child, вызывающим штатный destroyProcessById.
Финальный APK: subprocess был активен перед NEXT, команда принята за706ms, последующая
очистка доступна через1553ms; служба завершена. Версия/binary yt-dlp не менялись. Это PASS реального параллельного
получения медиаданных и пропуска загрузки, не только синтетический тест.

Очищенные свидетельства в ignored `artifacts/prepared-programme/`: download-authorized-safe.json,
local-play-final-safe.json (последовательные концы), final-apk-local-safe.json, concurrent-next-safe.json,
local-playback.png, web-ready.png. Попытка поймать FD после полного чтения короткого ролика дала
пустой список, поэтому `local-open-files.json` не считается свидетельством открытого дескриптора.
Локальный источник подтверждён localPlayback/диагностикой/проверенными файлами и движением кадров;
реальное отключение сети не выполнялось. Системные overlay RustDesk/cast видны на скриншоте TV,
их не меняли и не выдавали за интерфейс ParentCast.

## Артефакт и ограничения

Обычный debug APK: `artifacts/prepared-programme/parentcast-tv-v0.1-prepared-programme-debug.apk`.
SHA-256 `6d73da68988f19b042fafe05d37037512d1e7254c87404ad4e4a72484186fbbf`.
Package `dev.siyovush.parentcast.tv.debug`, versionCode5, versionName0.1.0-m3-debug.
Signing verify PASS, debug signer `de2cf22c47b6e4039da04107631b31d92ecddeb6984eeae5d43107337cceecf8`.

Чтобы убрать сетевые остановки в программе, родитель должен дождаться READY всех нужных
элементов. Неподготовленный следующий будет ждать. Декодерный переход между файлами остаётся;
строгая gapless-склейка, многчасовой сеанс и абсолютное отсутствие сбоев не обещаются.
Полная SHA-проверка при открытии большого файла может увеличить задержку локального старта;
проверка большого HD-файла/многочасовой программы в этой приёмке не проводилась.
Byte-range resume не реализован: incomplete скачивается заново. Устаревшие cookies могут
потребовать обновления; production YouTube-авторизация остаётся нерешённой. Когда служба
подготовки не работает, прежнее ограничение веб-пульта в фоне не объявляется исправленным.

Итог: ParentCast STOPPED/STOP, подготовка paused=true, READY1/1 для текущего демонстрационного
ролика. Тестовая история очереди осталась, новые PIN/tokens/session неизменны по hash.
Штатный HOME com.google.android.tvlauncher/.MainActivity; child=false/auto-resume=false
по значениям по умолчанию новой установки (предпочтения не включались). Служба подготовки
завершена, неполных media-файлов нет; готовый файл сохранён. ParentCast оставлен открытым
для веб-пульта. Установленный APK побайтно совпал с артефактом по SHA-256.

M4/privileged/DO/platform signing/reboot/flash/system/vendor/Happ не затрагивались. Merge/Release
не выполнялись. Следующий шаг — ревью отдельного Draft PR и собственная подготовка родительской программы.
