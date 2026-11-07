# Плейлисты в программе — 2026-11-04

IN SCOPE: tasks/Programme-playlists.md. codex/programme-playlists отe316bb4 (PR #13), отдельный
stacked Draft PR, без Merge/Release/M4. Один Android-модуль, прежние службы/Media3 1.5.1/
yt-dlp2026.10.26, без privileged/platform key/DO/reboot/Happ/системных изменений.

## Результат

В «Моей программе» появились именованные карточки плейлистов и подборок с источником,
счётчиком всех готовых видео и фиксированным порядком. Одиночные/старые видео без достоверной
истории импорта вынесены отдельно по источнику. Новые импорты сохраняют названия из preview;
сохранённые подборки также сохраняют/показывают известные названия. Неизвестное название не
выдаётся за полученное: ID вторичен, метаданные старых видео появляются при подготовке.

Queue snapshotv3 читаетv1/v2. Группы содержат ordered references на уникальные videoId:
два списка могут ссылаться на один ролик, без повторной записи в общей очереди/скачивания.
Импорт, group/name/title и receipt записываются одной atomic queue-транзакцией. Повтор
команды переживает restart/истечение preview; повтор того же списка не создаёт новую группу.
Новая группа, состоящая из уже имеющихся роликов, меняет queueVersion, но добавляет0 видео.

Явный запуск выбирает плейлист; NEXT/естественный конец следуют его порядку, в конце —STOP,
без автоматического запуска другого списка. Порядок плейлистов и всех видео внутри списка
можно изменить, сохранив текущий ролик/позицию/плеер. Видео внутри —черновик до сохранения,
с прежним conflict/replay. Удаление списка сохраняет media/подборки/shared видео; если он
выбран или удаляется текущий exclusive ролик, просмотр останавливается. Обычная полная
очистка и старые операции поддерживаются; скрыты несовместимые с группами плоские UI-кнопки.

Подготовка выбранного списка не останавливает просмотр. Target group сохраняется в прежнем
prepared index; текущий PLAY-ролик имеет приоритет. Если в плане появился более ранний
неготовый ролик, поздняя передача отменяется с сохранением partial. Сортировка готовности
учитывает весь cache, а не только текущий план загрузки. В локальном режиме unready-пункт
показывает состояние/процент вместо активной кнопки «Смотреть»; API также отвергает PLAY
такого пункта с VIDEO_NOT_READY. Прежний потоковый YouTube-режим остаётся явной возможностью.
Файлы по-прежнему проходят полную проверку; готовность первого не гарантирует готовности
всего плейлиста, поэтому UI рекомендует дождаться всех роликов для просмотра без ожиданий.

PIN/token/Host/Origin/CSRF/URL/HTML-защита сохранены. Новые ID/названия ограничены и выводятся
через textContent. Динамических подписок/рекомендаций/обхода ограничений не добавлено.

## Проверки

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/siovush/Android/Sdk \
  ./gradlew testDebugUnitTest assembleDebug lintDebug compileReleaseKotlin --no-daemon
node --check app/src/main/assets/web/app.js
node --check app/src/main/assets/web/media.js
node --check app/src/main/assets/web/groups.js
node --check app/src/main/assets/web/sections.js
node tools/m2_web_contract_test.mjs
node tools/local_media_web_test.mjs
node tools/prepared_web_test.mjs
node tools/pin_login_web_test.mjs
node tools/programme_groups_web_test.mjs
git diff --check
```

PASS:259 JVM,0failures/errors/skipped;5 JS contracts;4 syntax; debug APK/release Kotlin;
lint0errors/50warnings. Новые проверки: v2 migration/roundtripv3, titles/references, group order
и STOP, общие ID/receipt/restart/no-op, atomic storage failure, stale/incomplete order, group
removal/shared preservation, API readiness, targeted preparation и preemption с partial.
JS проверяет безопасные имена, запрет unready, explicit group commands, video draft, сортировку
по ready вне текущего плана и сохранение DOM при прогрессе. Это синтетические проверки.

## H728 — реальные сценарии

Android14/h728/serial…84201a подтверждены; обычный debugv11 install-r без очистки, прежние
applicationId/signer. Приватные снимки исходной queuev2/prepared index/API/hashes сохранены
в .dev/programme-groups; содержимое секретов не выводилось.

- PASS: v2 →v3 чтение всех38 queueItemId/videoId с исходными физическим порядком, выбором,
  PAUSE/позицией. В реальном Android Chrome сохранённая «Исламские мультфильмы» добавлена
  как группа38, added0/skipped38, без изменения текущего просмотра.
- PASS: её логический порядок точно равен фиксированному порядку сохранённой подборки.
  Прежний плоский порядок очереди также сохранён; он может отличаться от порядка подборки.
  Исходный YouTube URL старой очереди не выдумывался. Другой readonly preview не использовался
  как источник этих38, поскольку его ID не совпали.
- PASS: предоставленный VK-плейлист61/61; выбраны1 и1+2 в двух тестовых группах. Во втором
  добавлен один новый videoId, один shared; всего40 уникальных записей/3 группы. Titles из
  preview сохранены. Повтор команды не создал новую группу/ролики.
- PASS: group order и video order тестового списка изменены через реальный API с version,
  позиция/PAUSE пользовательского ролика не изменились. Порядки/названия пережили update
  процесса. Неготовый PLAY —409 VIDEO_NOT_READY, исходный просмотр не изменился.
- PASS: выбранная VK-группа подготовлена отдельно, пользовательский ролик остался PAUSE.
  READY35,309,901bytes; localPlayback=true, PLAYING, фактические1920x1080/avc1.640028 и
  mp4a.40.2, позиция8,262ms без ошибки. Последний member другого тестового списка запущен
  с113s: естественный конец дал ENDED/STOP/finished, ни другой список, ни YouTube не запустился.
  Это короткий смок с seek, не полный просмотр всех роликов.
- PASS: Chrome показывает источник/готово19/38, disabled ожидание у незагруженных, нормальные
  известные названия. Реально открыт редактор списка: позиции1/2/3 редактируемы, строка
  перемещена в черновике и отменена; серверный порядок не изменился. Просмотр через API
  тестировался с открытым экраном ParentCast: Chrome на самой приставке скрывает плеер,
  прежний OPEN_PLAYER_ON_TV не обходился.
- PASS: тестовые группы удалены; shared-ссылка сохранилась, пока оставался другой список,
  скачанный файл пережил удаление группы. Затем удалены только собственные VK test-media.
  Остались38 оригинальных записей, одна именованная группа38, исходная сохранённая подборка
  и все исходные ready-файлы. Исходный текущий videoId/нескопированный режим playback и PAUSE
  восстановлены; позиция4.641s вместо3.94s до теста (короткий PLAY при возврате к PAUSE).
  Подготовка группы38 возобновлена enabled=true/paused=false. Пользователь сам выбирает
  запуск списка; новый active playback scope ему автоматически не навязан.
- PASS: PIN/access/token/parent-mode/session settings и saved-collection file hashes совпали.
  Cookies не удалялись/не подменялись, сессия осталась включённой. После возобновления YouTube
  hash cookie-файла изменился: существующий source передаёт --cookies, а pinned yt-dlp при
  close() вызывает save_cookies()/cookiejar.save(). Побайтовую неизменность jar не заявляем;
  значения не читались/не публиковались, ручного восстановления старого jar не делали.

Основной VK playback-смок выполнен наv11 до уточнения UI-сортировки и приоритета оставшихся
роликов активного плейлиста. Код Media3/API/модели STOP после него не менялся. Для финального
APK отдельно проверено: пока играет пользовательский плейлист, все38 его оставшихся
member стоят в плане перед выбранным VK target. Текущий локальный YouTube PLAY не прерван;
затем тестовая группа/partial удалены, прежние38/PAUSE/режим без active scope восстановлены,
подготовка user group продолжена. Финальные снимки —priority-playing/final-state.json. Тесты относятся к финальному исходному коду.

NOT_RUN: полный просмотр38/61, физическое управление воспроизведением из Ubuntu Chrome/
телефона, принудительный сетевой обрыв/Range именно на H728, USB/reboot/M0–M3/privileged.
IAB browser navigation завершилась timeout Page.navigate; рабочая реальная UI-проверка —
Android Chrome, воспроизведение —LAN API при видимом TV-плеере. Эти границы не скрываем.

## Артефакт и ограничения

`artifacts/programme-playlists/parentcast-tv-v0.1-programme-playlists-debug.apk`
SHA-256: `d3417958ca37a1cd6fa2e00d6ec207c65f02f3067905bbfc466f3dbb90f15c83`.
applicationId dev.siyovush.parentcast.tv.debug, versionCode11,
versionName0.1.0-programme-playlists-beta-debug; APK signing verify PASS,
debug signer de2cf22c47b6e4039da04107631b31d92ecddeb6984eeae5d43107337cceecf8.

До100 групп, до500 member/import. Прежние50 saved collections/10,000 IDs и10GiB/1GiB reserve
сохранены. Старые названия без metadata не восстанавливаются из одного ID. Queuev3 требует
отдельной миграции/приватного backup при откате на старый APK; downgrade/clear не выполнялись.

Следующий шаг: закрыть старый редактор в браузере, обновить пульт и использовать карточку
«Исламские мультфильмы»: подготовить, дождаться готовности и явно запустить плейлист.
