# VK: видео и плейлисты — 2026-11-04

IN SCOPE: запрос владельца и tasks/VK-video-import.md. Ветка codex/vk-video-import от
fea823ee856743b544386292c474c8c0e0cd0b6c (PR #11), отдельный stacked Draft PR, без Merge.

## Поведение

Поддержаны анонимно доступные завершённые видео VK с H.264/AAC до1080p и статические
числовые плейлисты. Входные ссылки строго разбираются и пересобираются; произвольные URL,
access_key/закрытый доступ, канальные ленты, эфиры, DRM и перекодирование не добавлены.
VK получает отдельные ID `vk:<owner>_<video>`, несовместимые с 11-символьным YouTube ID.
Старые queue v1/v2, подборки и файлы читаются без сброса. После добавления VK-строк старая
версия приложения их не понимает; откат APK без предварительного удаления таких строк и
VK-подборок через штатный UI не обещается. Для приёмки сохранён защищённый исходный снимок.

Для перечисления используется текущий yt-dlp2026.10.26 и opt-in Python plugin override:
он извлекает названия, длительности и total из уже полученных страниц штатного VK extractor.
Дополнительных запросов к каждому видео для предпросмотра нет. Плагин зависит от схемы pinned
extractor; отсутствие схемы/total, изменение total, пропущенные элементы или ошибка следующей
страницы не становятся COMPLETE. При ошибке поздней страницы сохраняется ранее полученная
часть с PARTIAL; при >500 импорт блокируется. Явно недоступные/unlisted/live/DRM нельзя выбрать.
Анонимный flat-список не подтверждает доступность каждого видео: UNKNOWN остаётся UNKNOWN.

Весь импорт идёт через существующие queueVersion/atomic receipts/dedup; ни импорт, ни READY
не вызывают PLAY. Подборка хранит фиксированный порядок ID, без подписки на новые видео.
Предпросмотр отменяемый и отдельный от playback. Сохранены PIN/token/Origin/Host/CSRF.
Названия выводятся через textContent. VK процесс не получает YouTube cookies; HTTP-передача
VK не открывает YouTubeSession и не переносит Cookie/Authorization из метаданных. HTTPS CDN
ограничены суффиксами провайдера, портом443 и публичным DNS; каждый redirect проверяется снова.

VK воспроизводится только из полностью подготовленных и проверенных private-файлов. Даже
при отключённом режиме подготовки незагруженный VK не переходит в сетевой Media3 playback;
пульт просит сначала подготовить очередь. Готовый файл доступен и при отключённой подготовке.
Общий бюджет10GiB/резерв1GiB, проверка H.264/AAC, SHA, отмена и прежние retry/Range сохраняются.
Докачка зависит от strong validator/размера/206; иначе безопасное скачивание заново.
Media3 1.5.1 и yt-dlp не обновлялись. HOME/системные настройки/privileged не менялись.

## Локальные проверки — PASS

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/siovush/Android/Sdk \
  ./gradlew testDebugUnitTest assembleDebug lintDebug compileReleaseKotlin --no-daemon
python3 tools/vk_metadata_test.py
node --check app/src/main/assets/web/app.js
node --check app/src/main/assets/web/media.js
node tools/m2_web_contract_test.mjs
node tools/local_media_web_test.mjs
node tools/prepared_web_test.mjs
node tools/pin_login_web_test.mjs
git diff --check
```

246 JVM tests,0 failures/errors/skipped;5 Python tests; debug APK/release Kotlin; lint0 errors/
51 warnings. Четыре JS contracts и обе syntax-проверки PASS. Новые тесты: URI/namespace,
приватные ссылки/SSRF, H.264/AAC/live/DRM/доступность, URL/DNS, смешанные очереди/receipt/replay,
фиксированная подборка, VK без YouTube session, offline-only, cookie isolation и redirect fence.
Синтетические страницы через настоящий pinned VK extractor проверяют две страницы, порядок/
метаданные, поздний сетевой сбой с сохранением части, скрытые элементы/пустую страницу,
изменение total и500/501. Kotlin parser отдельно проверяет PARTIAL/схему/UNKNOWN/unlisted.
Это синтетические тесты, а не реальные многостраничные испытания VK.

## H728 — реальные проверки

Перед update повторно проверены h728/Android14 и hardware serial …84201a; установленныйv8
и обычныйv9 имеют одинаковые applicationId и debug signer. `adb -s <identified-target>
install -r` выполнен без очистки. APK versionCode9, versionName0.1.0-vk-beta-debug,
applicationId dev.siyovush.parentcast.tv.debug; signing v2 PASS.

- PASS: `https://vkvideo.ru/playlist/-227392256_1/` — COMPLETE61/61,61 названий и длительностей,
  исходный порядок, доступность UNKNOWN. Предпросмотр не изменил38 пользовательских строк.
- PASS: в реальном Android Chrome на этой H728: PIN-вход, URL, предпросмотр61/61, выбор одного,
  явное применение (added1) и сохранение выбранного в подборку. Во время Chrome пульт и
  загрузки доступны; PLAY требует открытого экрана ParentCast, как раньше.
- PASS: видео `-227392256_456239129?pl=-227392256_1` в импорте даёт тот же список61 в том же
  порядке; `/season_0` нормализуется в общий список, отмена его реального запроса — CANCELLED.
  Другие сезоны отвергаются. В обычном добавлении видео `?pl=` означает один ролик.
- PASS: повтор прямой ссылки и повтор той же команды — added0/skipped1; повтор сохранённой
  подборки — added0/skipped1; stale queueVersion409 и неверные/чужие/private-key URL400
  не изменили очередь. Сохранённая подборка содержит один фиксированный VK ID.
- PASS: первое видео «История Пророков - Конец | Важная информация»,116s: обе дорожки
  полностью скачаны и проверены; READY35,309,901bytes,100%. Затем явный PLAY с private-файлов:
  localPlayback=true, PLAYING, фактические1920x1080/avc1.640028 и mp4a.40.2; кадр виден,
  PAUSE на18,847ms, error=null. Это короткая проверка, не обещание отсутствия любых зависаний.
- PASS: удалены только собственная тестовая VK-строка, подборка и файл. Исходные38 videoId/
  queueItemId и порядок, пользовательская подборка38, готовые файлы/размеры сохранены.
  Вернули исходный текущий ролик3/38 и PAUSE на15,878ms (до теста15,170ms; +708ms при возврате).
  Подготовка исходных36 оставшихся роликов возобновлена enabled=true/paused=false.
- PASS: SHA файлов PIN/auth/session/cookies/parent-mode до/после совпали; исходный API-token
  работает. Для реального Chrome входа добавлен отдельный обычный отзываемый браузерный token;
  старые не удалялись. Значения секретов не выводились. Штатный HOME — com.google.android.tvlauncher.
- NOT_RUN: автоматизация Ubuntu Chrome: инструмент CUA падает до чтения вкладки с
  `Cannot read properties of undefined (reading 'timeoutMs')`. Реальная браузерная проверка
  выполнена на Android Chrome; это отдельно от синтетических DOM contracts.
- NOT_RUN: реальные >500/многостраничные VK-плейлисты, принудительный обрыв именно VK CDN,
  долгий ролик3ч13м и полный просмотр всех61. Их не выдаём за аппаратный PASS.
- NOT_RUN: повтор прежних M0–M3, USB/HDMI/reboot/privileged, смена VPN/системы. Не требовались.

Локальные свидетельства (не Git): .dev/vk-acceptance — снимки API до/после, preview/alias,
play-start/play-paused, Chrome/кадр и контрольные hashes. Cookies/PIN/токены в отчёт не входят.

## Артефакт

`artifacts/vk-video-import/parentcast-tv-v0.1-vk-debug.apk` — обычный debug APKv9,43MiB.
SHA-256: `3e40e53fc15b09b6f45e501bb75ef7d4fecb855728fd840e181a42b2af9c5b5c`.
Debug signer SHA-256: `de2cf22c47b6e4039da04107631b31d92ecddeb6984eeae5d43107337cceecf8`.
Обычный manifest lockTaskMode=normal; privileged-кандидат не собирался и не устанавливался.

Короткий playback-смок выполнен наv9 SHA640c3fbb…; после него уточнена только обработка
ошибки поздней страницы (сохранение части/PARTIAL), код плеера/скачивания остался тем же.
Финальный APK3e40e53f… установлен, повторно получил реальный61/61 с тем же порядком и
сохранил данные/PAUSE; подготовка исходной программы возобновлена. Все тесты относятся к
финальному исходному коду. Полный повтор playback после этой metadata-only правки не делался.
