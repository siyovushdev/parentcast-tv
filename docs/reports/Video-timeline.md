# Длительность и перемотка — 2026-11-05

## IN SCOPE: изменения

Ветка `codex/video-timeline`, база PR #15 `5ca842a4fd294aab90aa30e6fe9d8a13a5d5640c`.
Разработка/сборка/проверки выполнены через домашний ПК `/home/seva/parentcast-tv`.

- Длительность в программе/редакторе, предпросмотре, сохранённых подборках и загрузках;
  свои видео уже имели duration, формат теперь поддерживает часы.
- Сначала используются готовые локальные метаданные/предпросмотр. Для старых неизвестных
  длительностей — отдельный bounded IO worker, только metadata resolve текущим pinned
  yt-dlp, без загрузки медиа и без изменения очереди. Один worker/8pending, cache10000ID,
  неуспех повторяется через5мин. Неизвестное не подменяется0:00. Данные производные,
  отдельный cache JSON; формат queuev3 и saved-collections не изменён.
- В загрузках используется fallback к сохранённым метаданным даже до скачивания файла.
  Длинные названия с подчёркиваниями переносятся и не растягивают мобильную страницу;
  обложка текущего ролика имеет собственную высоту и не перекрывает состояние плейлиста.
- Контроллер под текущим роликом: позиция/общая длина, range drag/tap/keyboard,−10/+10с.
  Положение обновляется во время воспроизведения; drag не отправляет HTTP на каждый пиксель.
- POST `/api/playback/seek` с текущими queueItemId/queueVersion/playbackGeneration,
  целочисленной positionMs и существующим idempotencyKey. Устаревший контекст отклоняется,
  повтор не перематывает другой ролик. Существующие token/Host/Origin/CSRF сохранены.
- Координатор сохраняет новую позицию до вызова плеера, сохраняет PLAY/PAUSE/плейлист,
  откатывает при исключении seek, при проблеме записи не вызывает player.seekTo.
- Media3 использует штатные [seekTo/duration/isCurrentMediaItemSeekable](https://developer.android.com/reference/androidx/media3/common/Player).
  Длительность для seek берётся из реального плеера. Конечная точка ограничена duration−1ms,
  чтобы пауза у конца не стала явным переходом в следующий ролик.
- Последний корректный PLAYING не перетирается искусственным BUFFERING после вызова seek:
  сохранены реальные player callbacks, включая быстрый seek без нового onPlaying.
- Поллинг не перезаписывает drag; смена ролика во время жеста отменяет старый жест.

## Проверки на домашнем Ubuntu

```
ANDROID_HOME=/home/seva/Android/Sdk ./gradlew testDebugUnitTest assembleDebug lintDebug compileReleaseKotlin --no-daemon
for script in app/src/main/assets/web/*.js; do node --check "$script"; done
for script in tools/*web*test.mjs; do node "$script"; done
git diff --check
```

278JVM PASS,0failures/errors/skipped. Lint0errors/46warnings. Debug APK и release Kotlin
compilation PASS. 6JS syntax checks и7web contract scripts PASS.

Новые JVM проверки: вперёд/назад, сохранение PAUSE/PLAY/плейлиста/порядка, старые version и
request generation после NEXT, неверные/граничные позиции, unavailable/STOP, ошибки записи,
исключение seek/rollback, восстановление позиции, актуальная позиция без записи на каждый GET,
асинхронные/синхронные playback callbacks, metadata duration для ожидающих загрузок
без изменения готовности и без запуска скачивания. API: token/Host/Origin, integer input,
идемпотентность/устаревший контекст. Кэш: bounded/dedup/один worker/перезапуск/negative retry.
JS: input без POST до release, устойчивость drag при поллинге, старый жест после NEXT,
±10с, unavailable/unknown и восстановление controls после сетевой ошибки.
Эти fixture-тесты не выдаются за реальный YouTube/reboot.

## Реальная H728 и Chrome

Подтверждена H728 serial…84201a, Android14. Обновление debugv12→v13 через adb install-r,
без очистки. Начальная программа пользователя уже129роликов/3группы: прежние38/99/2
из старых отчётов не восстанавливались.

| Сценарий | Результат | Свидетельство |
|---|---|---|
| Обычное обновление | PASS | исходная debug-подпись, install Success, PAUSE110494ms/тот же ролик и группа |
| API seek вперёд/назад на PAUSE | PASS |60000ms→35000ms; текущий ролик/группа/PAUSE сохраняются |
| Повтор команды | PASS | повтор с тем же key возвращает идентичный ответ |
| Пауза на конце | PASS |429405/429406ms, через1с тот же ролик, PAUSE |
| Chrome mouse drag | PASS | реальный Input mouse down/move/up;172s;0POST во время удержания/поллинга,1POST при release |
| Chrome−10с | PASS |172000→162000ms, PAUSE |
| Chrome PLAY и+10с | PASS | PLAYING174709ms, localPlayback=true, позиция после seek растёт |
| Финальное восстановление | PASS | PAUSE110494ms/тот же videoId/group; массив queueItemId/videoId и3группы неизменны |
| Метаданные старого VK | PASS | COMPLETE61 entries существующего публичного VK-плейлиста, только preview/cancel, без apply; время подтянуто |
| Длительности в реальном браузере | PASS | в браузерном прогоне126/129, при финальной проверке129/129;429406ms у текущего |
| Интерфейс | PASS | Chrome headless desktop1280×900/mobile viewport390×844,0JS exceptions, scrollWidth=clientWidth=390, scale1, без horizontal overflow |
| Сохранность доступа/данных | PASS | hashes PIN/access/tokens/collections/parent-mode/YouTube-session/USB preferences совпали |
| Реальный телефон/USB/reboot/неподготовленный стрим | NOT_RUN | не нужны для принятой проверки, границы не расширялись |

В браузерной автоматизации ожидалось завершение команды и включение следующей кнопки;
ранний click в disabled control не засчитывался как аппаратная регрессия. В финальном
прогоне использовался реальный установленный APK и реальные LAN API/Media3, без подмены JS.

Приватные snapshots/логи/browser screenshots — `.dev/timeline-*`, не в Git.
Cookies не читались и не подменялись; существующая сессия сохранена. Обновление метаданных
YouTube при необходимости может штатно использовать её тем же существующим extractor.
HOME/Launcher/Happ/DO/system/privileged не менялись. Reboot не выполнялся.

## APK и ограничения

Обычный debug, applicationId `dev.siyovush.parentcast.tv.debug`, versionCode13,
versionName `0.1.0-video-timeline-beta-debug`.
Домашний артефакт `.dev/parentcast-tv-v0.1-video-timeline-debug.apk`, локальная копия
`artifacts/video-timeline/parentcast-tv-v0.1-video-timeline-debug.apk`.
SHA-256 `7dc8bcbf661b3240b9635f18e0b43f349028038755776f297a39d9cd338b9fd6`.
Signer SHA-256 `de2cf22c47b6e4039da04107631b31d92ecddeb6984eeae5d43107337cceecf8`.
Platform key не использовался. Временная защищённая копия обычного debug-key удалена.
Предварительные APK этой задачи заменены финальным7dc8…

Для недоступных источников длительность может остаться неизвестной; это не мешает уже
готовым файлам. Начальное заполнение старой программы идёт в фоне, cache сохраняется.
Seek доступен после подготовки текущего ролика в настоящем плеере; он может потребовать
короткой подготовки декодера. Физический телефон не заменяется viewport-тестом.
Следующий шаг: обновить веб-пульт Ctrl+Shift+R, потянуть шкалу и проверить±10с.
Без Merge/Release/M4.
