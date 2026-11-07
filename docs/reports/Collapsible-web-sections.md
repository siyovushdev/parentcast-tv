# Сворачивание блоков — 2026-11-04

IN SCOPE: tasks/Collapsible-web-sections.md. codex/collapsible-web-sections от636173b,
stacked к PR #12, без Merge/Release. Поведение playback/очереди/загрузок/HOME/auth не менялось.

## Изменение

9 больших/вспомогательных блоков используют native details/summary. Заголовок переключает
открытое состояние и показывает действие; внизу есть «Свернуть блок ↑» с focus/scroll к
заголовку. Сверху — «Свернуть все блоки»/«Развернуть все блоки». Плеер и вход не сворачиваются.
Настройки вида хранятся отдельно в localStorage браузера (только9 boolean, без API/секретов).
При недоступном/повреждённом storage native details продолжает работать. Верхнее меню открывает
нужный раздел, включая повторный переход на тот же fragment; reload сохраняет ручное закрытие.
В первый раз всё открыто. DOM содержимого не пересоздаётся при сворачивании: ввод, выделение,
черновик сортировки и работающие операции сохраняются. Сворачивание не означает отмену/паузу.

## Проверки

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/siovush/Android/Sdk \
  ./gradlew testDebugUnitTest assembleDebug lintDebug compileReleaseKotlin --no-daemon
node --check app/src/main/assets/web/app.js
node --check app/src/main/assets/web/media.js
node --check app/src/main/assets/web/sections.js
node tools/m2_web_contract_test.mjs
node tools/local_media_web_test.mjs
node tools/prepared_web_test.mjs
node tools/pin_login_web_test.mjs
git diff --check
```

PASS:246 JVM,0failures/errors/skipped;4 synthetic JS contracts;3 syntax; debug/release Kotlin;
lint0errors/50warnings. Разметка проверена HTMLParser: balanced tags, уникальные ID, все старые
ID сохранены,9 панелей. Новые unit-тесты для этой обратимой UI-правки не добавлялись.

H728 Android14/h728/serial…84201a подтверждена перед update. Обычный debug APKv10 установлен
через `adb -s <identified-target> install -r`, без очистки; applicationId и signer прежние.

- PASS: реальный `/sections.js` возвращает200 и JavaScript MIME.
- PASS: Android Chrome — «Свернуть все» скрывает формы, управление плеером сохраняется;
  «Развернуть все» возвращает формы/очередь; меню открывает закрытую очередь.
- PASS: черновик редактора остаётся при collapse/reopen и polling; отменён без применения.
- PASS: ручное закрытие очереди сохраняется после настоящего Chrome Refresh на том же
  #queueSection; footer другого блока сворачивает его и возвращает к заголовку.
- PASS:38 queueItemId/videoId/порядок, queueVersion, текущий ролик, PAUSE/позиция и подборки
  совпали с текущим снимком пользователя до update. SHA queue/collections/PIN/auth/cookies/
  tokens/parent-mode/session файлов совпали до/после. Секреты не выводились.
  Подготовка временно приостановлена для update и возобновлена в прежнем режиме; экран
  ParentCast возвращён на передний план. HOME/система/Happ не менялись.
- NOT_RUN: отдельный повтор Ubuntu Chrome/физического телефона, клавиатурная приёмка и передача
  большого файла во время сворачивания. Работу на них не выдаём за аппаратный PASS.
- NOT_RUN: повтор YouTube/VK playback, USB, M0–M3/привилегии/reboot — UI-изменение их не требует.

Локальные свидетельства вне Git: .dev/web-panels (API до/после, контрольные hashes и UI dump).

## Артефакт

`artifacts/web-panels/parentcast-tv-v0.1-web-panels-debug.apk`
SHA-256: `7e00ddcdee588a00c11bc54f0d9495193f86b82a204cd42a3679ccfdeb353afe`.
applicationId `dev.siyovush.parentcast.tv.debug`, versionCode10,
versionName0.1.0-web-panels-beta-debug, обычный lockTaskMode normal.
Debug signer SHA-256: `de2cf22c47b6e4039da04107631b31d92ecddeb6984eeae5d43107337cceecf8`.
APK signing verify PASS. Privileged/platform key не использовались.

Следующий шаг: сохранить/отменить несохранённый порядок, если он есть в браузере пользователя,
и обновить страницу пульта; затем свернуть ненужные блоки. Reload несохранённый редактор,
как и прежде, сбрасывает; простое сворачивание сохраняет его.
