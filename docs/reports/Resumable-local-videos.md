# Докачка, свои видео, USB и порядок — 2026-11-03

IN SCOPE: `tasks/Resumable-local-videos.md`. Ветка `codex/resume-local-videos` от9f9c7de,
stacked поверх Draft PR #9. Без Merge/Release. Приоритет детского приложения закреплён в
AGENTS: только явный выбор родителя, проверенные файлы, честные статусы и сохранность данных.

## Реализация

YouTube-подготовка сохраняет partial при сетевой ошибке/паузе/отмене. Свежий resolve получает
новый URL; URL/cookies/заголовки авторизации в resume-index не сохраняются. Sidecar содержит
идентичность формата/ревизии, total, сильный ETag либо допустимый Last-Modified, complete/SHA.
Range/If-Range append разрешён только при206, точном Content-Range/длине и совпадении validator.
200 означает restart; некорректный206/416 не склеивает файлы. Проверенный416 может подтвердить
полный partial. Завершённая дорожка с той же provider revision и SHA переиспользуется при
повторе соседней дорожки. Corruption не переименовывается в новую готовность. Проверяется
каждый CDN redirect; произвольные URLs не принимаются. Диагностика RESUMED_206 содержит
только событие/offset, без URL/секретов.

Сетевые retry автоматические:5→10→20…до300с, Retry-After до600с. Восстановление validated
Android-сети ускоряет попытку; Happ не изменяется. Приоритет у ближайшего незавершённого
элемента программы: он не ждёт скачивания следующего большого ролика. Авторизация, недоступный
ролик, неподдерживаемый формат, место/ошибка хранения не превращаются в network busy loop.
Пауза и STOP не блокируются передачей; отмена закрывает socket и проверку SHA. Старый индекс
без resumeVersion один раз переводит DOWNLOAD_FAILED в автоматический повтор.

Свои видео копируются в тот же private media-бюджет10GiB/reserve1GiB. Browser upload — отдельный
потоковый endpoint, без JSON-буферизации большого файла; Host/Origin/bearer/custom-header,
точная длина и ограничение размера проверяются до чтения body. Upload id принадлежит браузеру,
повтор готового upload не создаёт второй файл. Две передачи ограничены semaphore, все writers
проверяют общий бюджет перед записью. Прерывание/отмена/ошибка формата не дают READY.
MediaExtractor/MetadataRetriever проверяют контейнер/длительность, MediaCodecList — декодеры,
DRM не поддерживается. MP4/MKV/WebM/3GP зависят от реальных кодеков H728; транскодирования нет.

Импортированное видео имеет отдельный `local_<uuid>` namespace и явный source-признак очереди.
Его source никогда не делает fallback на YouTube и работает при выключенном streaming/session.
YouTube cleanup/автовытеснение не удаляют свои видео. Их удаление отдельно подтверждается.
Импорт сохраняет файл в библиотеку, явное добавление выбранных — в очередь без PLAY.
«Смотреть» выбирает существующую строку без вставки дубликата. Queue snapshotv1 читается,
новая записьv2 сохраняет source-признак; старый decoder не может принять local-id за YouTube.

USB/локальная папка приставки выбирается один раз через PIN-защищённую кнопку и штатный
ACTION_OPEN_DOCUMENT_TREE, READ only. Используется только системный externalstorage provider;
браузер получает opaque keys из явно выбранного дерева, не произвольные URI/path. Папки
просматриваются по запросу родителя, копируются только отмеченные файлы, исходники не меняются.
Отзыв/извлечение/ошибка чтения дают ошибку, скопированные файлы остаются независимыми от USB.
Перезапуск прерванного USB/browser import требует повторного выбора исходного файла.

Редактор порядка работает с ожидающим suffix. Стрелки, позиция, desktop drag и сортировка
по названию/готовности меняют черновик до подтверждения. QueueVersion и receipt сохраняются
атомарно с новым порядком; stale/replay не перетирают новые изменения. Текущий ролик, позиция,
намерение PLAY/PAUSE/STOP и история не сдвигаются. Появилась навигация по разделам; автоопрос
не пересоздаёт неизменившийся редактор/checkbox-список и не сбрасывает фокус. Строки имён
выводятся textContent, технические ошибки объясняются родителю.

## Проверки кода

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/siovush/Android/Sdk \
  ./gradlew testDebugUnitTest assembleDebug lintDebug compileReleaseKotlin --no-daemon
node --check app/src/main/assets/web/app.js
node --check app/src/main/assets/web/media.js
node tools/local_media_web_test.mjs
node tools/prepared_web_test.mjs
node tools/pin_login_web_test.mjs
node tools/m2_web_contract_test.mjs
```

224 JVM tests PASS,0 failures/errors; debug/release Kotlin PASS; lint0 errors/50 warnings.
Две проверки syntax и четыре JS contracts PASS. Diff whitespace PASS.

- MockWebServer проводит реальный HTTP-обмен с разрывом socket и Range206,200,416,
  изменением validator/URL, неизвестной длиной, отменой и corruption завершённой дорожки.
  Это синтетический сервер, **не YouTube**.
- Настоящий NanoHTTPD в JVM принимает raw upload без JSON parser и отвечает401 на запрос с
  большим Content-Length без ожидания body. Проверены Host/Origin/token/CSRF/length/type,
  отмена InputStream, quota, невалидное медиа, idempotency, local-only source и migration.
- USB unit fixture проверяет выбор/копирование/извлечение без изменения очереди; он не
  объявляется физическим USB-испытанием. Android MediaExtractor не заменён JVM-заглушкой в APK;
  в unit-тестах validator намеренно fake и это не доказывает воспроизведение.
- В реальном Chrome на Ubuntu проверен frontend на явно помеченном fixture backend:
  редактор, перемещение/применение, версия1→2, intent STOP; локальные названия/checkbox UI.
  Это проверка интерфейса, **не интеграция нового APK с H728**. На узкой эмулированной области
  DOM width=scrollWidth468; это не физический телефон и не обещание точного device viewport.

## APK и совместимость

Локальный артефакт: `artifacts/resume-local-videos/parentcast-tv-v0.1-resume-local-debug.apk`.
SHA-256: `559d0df34be7624147719877f0adfe2a7c42260e1987f59e7636fe32fcfadd5b`.
ApplicationId `dev.siyovush.parentcast.tv.debug`; versionCode7, versionName0.1.0-local-beta-debug.
Обычная debug-подпись, APK v2 verification PASS; certificate SHA-256
`de2cf22c47b6e4039da04107631b31d92ecddeb6984eeae5d43107337cceecf8`.
Нет PrivilegedKioskActivity/ALWAYS в обычном manifest; platform-ключ не используется.

## H728 и реальный Chrome

Цель повторно подтверждена read-only: SoC h728, Android14, serial…84201a. rk3568 не
использовалась. Обычный versionCode7 установлен через `adb -s <H728> install -r` без очистки.
Владелец разрешил обновить сразу с повторной загрузкой незавершённого старого ролика.
До установки старая передача сама завершилась ошибкой; следующая была ещё в INFO/0 bytes.
Готовый пользовательский YouTube-файл206493895 bytes не удалялся.

| Сценарий | Статус | Свидетельство |
|---|---|---|
| Update и сохранение данных | PASS |20 исходных queueItemId/videoId в исходном порядке, currentIndex13/position0; SHA256 родительских настроек, session и device-tokens совпали с baseline |
| Browser upload → Android validation | PASS | Настоящий raw HTTP upload744413 bytes, Android validation, READY/duration20; очередь/version/intent не изменились |
| Chrome: библиотека/выбор/добавление | PASS | Реальная панель `.local:8765`, checkbox, явное добавление двух временных local-строк; PLAY не вызван |
| Chrome: изменение порядка | PASS | До «Сохранить» API порядок прежний; после сохранения версия55→56, переставлены только две временные строки, текущий/позиция/PAUSE сохранены |
| Pause/resume с YouTube | PASS | Первый ролик получил950272 bytes; PAUSE сохранил partial. Свежий resolve, реальный CDN206: `ParentCastTransfer: RESUMED_206 offset=950272`, затем2072576 bytes |
| Перезапуск процесса | PASS | Force-stop/start только ParentCast, без reboot:20 строк, STOP, сохранённый SAF grant и все три auth-digest сохранены; подготовка возобновилась, реальный206 с2506736 bytes |
| Автоматический retry после разрыва HTTP | PASS (синтетический сервер) | MockWebServer + worker/backoff tests; реальный VPN/сетевой сбой на H728 намеренно не вызывался, не выдаётся за испытание YouTube |
| Штатный SAF grant/копирование | PASS (внутренняя папка) | Действующий PIN принят; DocumentsUI READ-grant только Movies/ParentCast-check. API и Chrome показывают файл744413 bytes; отмеченный файл скопирован и проверен, очередь20/STOP не изменилась |
| Физический USB/извлечение | NOT_RUN | Только private/emulated volumes; владелец сможет подключить флешку завтра. Unit fixture и внутренняя папка не подменяют USB |
| File picker в автоматизированном Chrome | NOT_RUN | Extension не имеет «Allow access to file URLs»; настройки расширения не менялись. Сам endpoint проверен настоящим HTTP, дальнейший UI — Chrome |
| Local playback со звуком | FAIL | Видна первая картинка H.264/AAC, но Media3 actual position остаётся0 после7–9 секунд и PAUSE; удалённый SHA файла совпал с исходным |
| Диагностика аудиотракта | UNKNOWN, аппаратный блокер | Video-only файл прошёл7603ms. AAC44100 и48000 stereo стоят на0; прежний готовый YouTube с отдельными дорожками также стоит на0. AudioFlinger HDMI mixer не совершает write несколько миллионов ms, server frames0 также у другого приложения. Это не доказанная регрессия local import |
| Повтор без RustDesk | FAIL (зависание осталось) | После явного разрешения владельца выполнен force-stop только com.carriez.flutter_hbb, процессов RustDesk нет. Готовый YouTube со звуком через8s и PAUSE по-прежнему position0; HDMI last-write вырос с9868558 до9886010ms |
| Reboot/Happ/DO/privileged/system | NOT_RUN | Не выполнялись |

Для контроля проигрывания использованы свои прежние демонстрационные video/audio, отдельно
созданы video-only и48kHz stereo варианты. Не объявляем статус PLAYING доказательством просмотра:
проверялись реальная позиция после PAUSE и кадры экрана. Причина остановившегося HDMI-аудиотракта
пока не установлена. Владелец разрешил полностью остановить RustDesk: он оставлен остановленным,
повтор не устранил зависание. Это не доказывает ни вину RustDesk, ни регрессию ParentCast;
активный захват не объясняет проблему целиком. Media3, системное аудио и Happ не менялись.

После каждого теста временные queue-строки удалены, исходные20 строк/текущий/позиция восстановлены,
намерение STOP. Тестовые файлы удалены; доступ к внутренней тестовой папке отозван через родительское меню.
Штатный Launcher открыт, child=false/auto-resume=false, STOP; подготовка продолжается в фоне.
PIN/cookies/токены/готовые пользовательские видео не сбрасывались. Секреты и персональные дампы
не опубликованы. Локальные свидетельства лежат в игнорируемом `artifacts/resume-local-videos`:
`upload-real.json`, `sort-real.json`, `saf-real.json`, `range-pause.json`, `range-resume-real.json`,
`process-restart.json`, `final-state.json`, `local-compare.json`, `ready-youtube-control.json` и кадры экрана.
Повтор без RustDesk: `rustdesk-stopped-control.json`, `rustdesk-stopped-final.json`.
После остановки RustDesk отдельно перезапущен только процесс ParentCast: повтор того же
готового ролика через8s/PAUSE снова position0, AudioTrack активен с заполненным буфером,
HDMI last-write10015296ms. Свидетельство: `rustdesk-stopped-restart-control.json`.
Остановка RustDesk и перезапуск ParentCast не помогли; изменения Media3/прошивки без причины
не вносились. Минимальная дальнейшая диагностика — контроль этого файла штатным плеером
и проверка аудио HAL; перезапуск системного аудио/reboot не выполнять без отдельного разрешения.
После повтора снова подтверждены20 исходных строк/текущий/позиция/STOP и три auth-digest;
открыт штатный Launcher. Перезагрузка/перезапуск системного аудио не выполнялись.

На03.11 приёмка просмотра была заблокирована аудиотрактом H728; кодовые проверки, импорт,
порядок и реальная докачка выполнены. Физический USB перенесён по явному ответу владельца.
Draft PR #10 остаётся Draft, без Merge/Release.

## Повтор с наушниками — 2026-11-04

Владелец подключил наушники/динамики к аналоговому разъёму. Система выбрала headphone(8),
mute=false, громкость11/15; системные настройки агент не менял. На той же обычной сборкеv7
выполнены две короткие проверки ранее зависавших файлов, без изменения Media3/кода/APK:

| Сценарий | Статус | Свидетельство |
|---|---|---|
| Готовый YouTube, H.264/AAC с отдельными дорожками | PASS по движению playback/audio | Через10s actual position после PAUSE9517ms вместо прежнего0; AudioFlinger headphone, последняя запись135ms назад, frames written264818688 вместо264368128 |
| Свой muxed MP4, H.264/AAC44100 stereo | PASS по движению playback | Тот же744413-byte файл повторно импортирован, явно добавлен и запущен; через10s actual position10289ms, ошибок нет |
| Фактическая слышимость из динамиков | NOT_CONFIRMED | Требует подтверждения владельца на месте; движение аудиосчётчиков не заменяет прослушивание |
| HDMI после переключения маршрута | NOT_RUN | Проверка выполнялась с подключённым аналоговым разъёмом; прежний HDMI-сбой не объявляется исправленным |

В vendor dump HDMI теперь указан PCM stereo с ненулевым pcm-дескриптором; это наблюдение,
не доказательство конкретной причины прежнего сбоя или гарантии звука по HDMI. Переключение
маршрута совпало с восстановлением продвижения аудио и playback. Перезапуск системы не выполнялся.
Подготовка пользовательской программы завершилась:7 из7 READY. Проверены два файла, не все7.
После тестов исходные20 строк/текущий/позиция восстановлены, STOP; временная local-строка и файл
удалены, пользовательские готовые файлы сохранены. RustDesk не запускался. Свидетельства:
`headphones-control.json`, `headphones-local-control.json` в игнорируемом каталоге артефактов.
Следующий аппаратный шаг — подтвердить слышимость на месте; HDMI/физический USB остаются
отдельными непроверенными сценариями. APK/SHA и результаты тестов кода выше не изменились.

Механизмы: [HTTP Range/If-Range/validators, RFC9110](https://www.rfc-editor.org/rfc/rfc9110.html),
[Android SAF и USB providers](https://developer.android.com/guide/topics/providers/document-provider),
[Persistable grants и ограничения выбора папок](https://developer.android.com/training/data-storage/shared/documents-files).
