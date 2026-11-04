# Превью и продолжение — 2026-11-04

## IN SCOPE: результат

Работа/сборка выполнены через SSH на домашнем Ubuntu в `/home/seva/parentcast-tv`,
ветка `codex/video-thumbnails`, база PR #14 `4c59658177aaf57e56d199738f3e28deb3796c3a`.
Владелец отдельно разрешил остановить просмотр для обновления. Для продолжения улучшен
веб-интерфейс; существующие PlaybackCoordinator/Media3/политика запуска не переписаны.

- Изображения в текущем ролике, программе/редакторе, предпросмотре, загрузках,
  сохранённых подборках и библиотеке своих видео.
- YouTube: обложка с i.ytimg.com, при отсутствии сети — кадр готового файла.
- VK: известная обложка, кадр готового файла; для неготового видео — только анонимные
  метаданные существующим pinned yt-dlp, без YouTube cookies и загрузки медиа.
- Локальные файлы: масштабированный кадр через MediaMetadataRetriever, без запуска player.
- Авторизованный JSON JPEG endpoint; строгие mediaId и проверка принадлежности программе,
  локальной библиотеке, сохранённой подборке или предпросмотру данного браузера.
- HTTPS allowlist CDN, публичный DNS, проверка redirect, ограниченные image bytes/dimensions;
  только перекодированный JPEG. Браузер не получает внешние URL или токены в img.src.
- Производный приватный cache32MiB, JPEG96KiB, один IO worker/16заданий; запросы только
  видимых строк, browser dedup/cache. Нет изображений → «Нет превью», готовность видео
  отдельно. Настройки API Host/Origin/CSRF/PIN/отзыва токенов сохранены.
- У текущего ролика «Продолжить с …» использует PLAY без сброса position/group;
  «Смотреть плейлист сначала» остаётся явным запуском с0. Позиция видна и в состоянии
  выбранного плейлиста. PAUSE/выключенное автовозобновление продолжают исключать автозапуск.

При реальной проверке Chrome выявлена особенность hidden+loading=lazy: наблюдать нужно
видимую рамку, а уже полученный data JPEG декодировать eager. Исправлено минимально,
добавлен регрессионный контракт; финальный APK проверен без browser overrides.

## Команды и локальные результаты на домашнем ПК

```
ANDROID_HOME=/home/seva/Android/Sdk ./gradlew testDebugUnitTest assembleDebug lintDebug compileReleaseKotlin --no-daemon
for script in app/src/main/assets/web/*.js; do node --check "$script"; done
for script in tools/*web*test.mjs; do node "$script"; done
git diff --check
```

266 JVM tests PASS,0failures/errors/skipped. Lint0errors/46warnings.
Debug APK, release Kotlin compilation, пять JS syntax checks и шесть contract scripts PASS.
Новые проверки: async/dedup/один worker/cache budget/retry/invalidJPEG, auth/Host/revoked
browser token, image URL policy, text-only title/MIME validation, visibility wrapper/decode,
очистка browser image cache при выходе, сохранение playlist/position на диске, продолжение
через PLAY без select(position0). Это синтетические тесты, не испытания YouTube/reboot.

Существующий JDK21 использован без системных изменений. Добавлены SDK35/build-tools
и platform-tools только в домашний пользовательский каталог, проектный wrapper8.9.
APK подписан исходным обычным debug-сертификатом. Platform key не использовался;
временная защищённая копия debug-keystore для подписи на домашнем ПК удалена.

## H728 / реальные проверки

Идентичность: serial…84201a, SoCh728, Android14, LAN192.168.1.16:5555.
Не выполнялись reboot, flash, pmclear, uninstall, HOME/DO/privileged/Happ/system изменения.

| Сценарий | Результат | Свидетельство |
|---|---|---|
| Обновление обычного debugv11→v12 без очистки | PASS | adb install-r / Success; signer совпадает |
| Данные после обновления | PASS |99queueItemId/videoId в прежнем порядке,2исходных группы/activeGroup/currentIndex; PAUSE158726ms |
| YouTube thumbnail на приставке | PASS | реальный JPEG7236bytes, изображение просмотрено |
| Готовый VK: кадр без нового media download | PASS | JPEG11217bytes, изображение просмотрено |
| Неготовый VK: анонимная исходная обложка | PASS | JPEG14214bytes до готовности файла |
| Свой файл с ПК: импорт/кадр | PASS | реальный upload существующего тестового MP4 через LAN API, READY и JPEG10733bytes; файл удалён, в очередь не добавлялся |
| Продолжение из PAUSE | PASS | PLAY158726→164075ms, PLAYING/localPlayback=true; тот же videoId/group |
| Прерывание процесса | PASS | после amforce-stop и amstart восстановились PAUSED164075ms и тот же videoId/group |
| Chrome на домашнем Ubuntu | PASS | headless Chrome, реальный LAN API/токен,5видимых изображений,0JS exceptions; screenshots1280×900/390×844 |
| Сохранность доступа/настроек | PASS | hashes PIN/access/token store/collections/parent-mode/YouTube-session/USB prefs совпадают |
| Полный reboot / фактический телефон / USB | NOT_RUN | reboot запрещён; мобильный viewport проверен, физические телефон/USB не подменяются им |

Финальная версия оставлена PAUSE164075ms;99оригинальных роликов/2плейлиста сохранены.
Тестовый локальный файл удалён. Cookies не читались/не удалялись/не подменялись; включение
существующей сессии сохранено. yt-dlp может штатно обновлять свой jar во время подготовки.
Приватные baseline/API snapshots и screenshots находятся в `.dev`, не в Git.

## APK

ApplicationId `dev.siyovush.parentcast.tv.debug`, versionCode12,
versionName `0.1.0-video-thumbnails-beta-debug`.
На домашнем ПК `.dev/parentcast-tv-v0.1-video-thumbnails-debug.apk`, локальная копия
`artifacts/video-thumbnails/parentcast-tv-v0.1-video-thumbnails-debug.apk`.
SHA-256 `af00ea9c6003c841a4d8e69c14d54fc8e6b12ec5e8b653a7407c1dbadf0e0155`.
Signer SHA-256 `de2cf22c47b6e4039da04107631b31d92ecddeb6984eeae5d43107337cceecf8`.
Ранние аппаратные APK этой задачи с digest5cc4…/9aa1… заменены финальным af00…

## Ограничения и один следующий шаг

Обложки иногда недоступны у источника; существует текстовый fallback и кадр готового файла.
При внезапном питании возможно повторение последних примерно5с. Реальный reboot не проверен;
автоматический запуск приложения при включении не добавлялся. После включения открыть
ParentCast и продолжить с сохранённого места; существующая настройка автовозобновления
не менялась. PAUSE никогда не становится PLAY сама.

Следующий шаг: обновить домашнюю веб-страницу Ctrl+Shift+R и нажать «Продолжить с2:44».
Без Merge/Release/M4.
