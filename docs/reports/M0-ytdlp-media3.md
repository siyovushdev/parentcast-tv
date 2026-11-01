# M0 — yt-dlp + Media3 на H728

## 1. Реализация и границы

Ветка `m0/ytdlp-media3`, финальный implementation commit:
`10c0cb4f99b1dad31558202bb2e15eb94f70ac46`.
Draft PR: https://github.com/siyovushdev/parentcast-tv/pull/3.

Реализован самостоятельный источник `YtDlpYouTubeSource` через существующий `YouTubeSource`.
Сохранены `PlaybackCoordinator`, очередь, `AtomicQueueStore`, PlayerView без контроллера, debug UI
и парсер ссылок. NewPipe остаётся в Git, но основной путь использует yt-dlp. M1–M4, HOME, Device
Owner, Lock Task и Launcher не изменяются.

Выбрана официальная `io.github.junkfood02.youtubedl-android:library:0.18.1` с `common:0.18.1`.
Её AAR содержит Python 3.12 и QuickJS `2025-04-26`; заявление README про Python 3.8 устарело.
В APK включён официальный yt-dlp `2026.10.26`, SHA-256
`e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3`.
Zipapp уже содержит EJS `0.8.0`; внешние JS-компоненты и Deno не требуются. Динамическое обновление
`latest` не используется. ffmpeg не включён: полное скачивание и склейка не нужны.

## 2. Потоки, отмена и ошибки

- Запрос выполняет официальный `YoutubeDL.execute` с `--dump-json`, без полного скачивания.
- Selector: progressive MP4 H.264 + M4A/AAC до 1080p, иначе progressive MP4 со встроенным звуком.
- `YtDlpResponseParser` принимает только direct HTTP/HTTPS и отклоняет HLS/DASH, чужие кодеки,
  разрешение выше 1080p и видео без аудио. В Media3 передаются раздельные `StreamSpec` через
  существующий `MergingMediaSource`.
- Разрешённые HTTP-заголовки сохраняются по allowlist. `Cookie`, `Authorization` и прочие секреты
  в список не попадают и не логируются.
- Каждый процесс получает уникальный ID; cancellation вызывает `destroyProcessById`. Поздний ответ
  дополнительно блокируется generation guard координатора.
- yt-dlp retries и fragment retries ограничены двумя, socket timeout — 20 секунд. Существующий
  один refresh после HTTP 403 сохраняется. `LOGIN_REQUIRED` сохраняет STOP без автоповтора.
- Рекомендации YouTube не запрашиваются и не используются.

## 3. Авторизация и безопасность

Используется debug-only session provider. Владелец разрешил включение через ADB; агент не читает,
не копирует и не печатает cookies. Файл остаётся в app-private хранилище с mode 600. Release
использует `DisabledYouTubeSession`; production-авторизация не реализована и не заявляется.

`SharedPreferences` хранит только факт разрешённого владельцем включения, не cookies. Это позволяет
восстановить уже разрешённую сессию после restart. `authorizationGate` не делает запросов при
отключённой сессии и сохраняет PLAY/PAUSE до её включения; STOP не запускает плеер никогда.

`Set-Cookie` не сохраняется. Остановка и удаление сессии сохранены. Cookies, Authorization,
подписанные media URL, yt-dlp JSON/stderr и команды процесса не выводятся в logcat, отчёты или Git.
Google Login/WebView, основной Premium-аккаунт, CAPTCHA-bypass и автоматическое изменение Happ не
используются. Список компонентов и лицензии — `docs/THIRD_PARTY_NOTICES.md` и APK assets/licenses.

## 4. Локальные проверки

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME="$HOME/Android/Sdk" \
./gradlew testDebugUnitTest assembleDebug lintDebug compileReleaseKotlin --no-daemon
```

Чистый checkout commit `10c0cb4`: **PASS**, `BUILD SUCCESSFUL`, 63 actionable tasks.
Итого **50 tests, 0 skipped/failures/errors**. Lint: **0 errors, 26 warnings**. Release Kotlin
compilation подтверждает отключённую debug-сессию. Подпись debug APK проверена `apksigner`.

APK: `artifacts/ytdlp-media3/parentcast-tv-m0-ytdlp-debug.apk`
Размер: `42065425` bytes.
SHA-256: `614bc6e73bcea902d332e9248a6bf09f68f3bfaf8bd75e3c16370da7695a9071`.

## 5. Аппаратная приёмка H728

Устройство подтверждено: Allwinner H728, Android 14 API 34, arm64-v8a, 1920×1080. Владелец разрешил
включение тестовой сессии через ADB. Статусы не заменены Gradle-сборкой или наличием AudioTrack.

| ID | Статус | Фактическое свидетельство |
|---|---|---|
| M0-01 | PASS | Чистый checkout `10c0cb4`, 63 задачи, 50 тестов, APK и lint успешны |
| M0-02 | PASS | 9 parser-тестов; посторонний hostname отклонён и на H728 |
| M0-03 | NOT_RUN | Три VOD реально показаны, но физическое прослушивание звука владельцем не подтверждено |
| M0-04 | NOT_RUN | Раздельные дорожки и AudioTrack подтверждены; слышимая синхронность не проверена |
| M0-05 | PASS | Естественные переходы `aqz-KE-bpKQ` → `eRsGyueVLvQ` → `jNQXAC9IVRw`, строгий порядок |
| M0-06 | PASS | `finished=true`, `intent=STOP`, собственный экран ожидания, рекомендаций нет |
| M0-07 | PASS | NEXT и STOP во время загрузки; `currentIndex=5`, STOP, через 60 с старый ролик не запустился |
| M0-08 | PASS | `video000001` дал управляемую ошибку yt-dlp без браузера/внешнего приложения |
| M0-09 | NOT_RUN | Контролируемый обрыв/возврат сети не выполнялся: Happ и системные настройки не изменялись |
| M0-10 | PASS | PLAY продолжил видео; PAUSE сохранил `positionMs=259090`; STOP не запустился после restart |
| M0-11 | PASS | Реальные кадры всех трёх VOD без YouTube UI и без собственного контроллера |
| M0-12 | PASS | HOME остался `com.google.android.tvlauncher/.MainActivity`; изменён только debug APK |
| 20 минут | PASS | Big Buck Bunny + Sintel + короткий VOD, более 24 минут реального просмотра |

Основные evidence вне Git:

- `artifacts/ytdlp-bbb-first-frame.png` и `ytdlp-first-launch-75s.png`: BBB, реальный кадр и
  1080p H.264/AAC, раздельные дорожки.
- `artifacts/ytdlp-sintel-codecs.png`: Sintel `1920x818 avc1.640028`, `mp4a.40.2`.
- `artifacts/ytdlp-sintel-after-buffer.png` и `ytdlp-short-frame-or-end.png`: реальные кадры
  Sintel и короткого VOD.
- `artifacts/ytdlp-audio-state.txt`: активный AudioTrack 48 kHz, USAGE_MEDIA, AAC; это доказательство
  audio pipeline, но не физического прослушивания.
- `artifacts/ytdlp-pause-state.png`: PAUSE с `positionMs=407419` и продолжение.
- `artifacts/ytdlp-queue-ended-sintel.png` и `ytdlp-short-ended.png`: конец очереди без рекомендаций.
- `artifacts/ytdlp-stop-next-loading.png`: STOP после NEXT во время загрузки, без позднего запуска.
- `artifacts/ytdlp-play-after-restart-frame.png`: реальное продолжение PLAY после restart.
- `artifacts/ytdlp-pause-after-restart-ready.png`: PAUSE с неизменной позицией `259090`.
- `artifacts/ytdlp-stop-after-restart.png`: STOP после restart.
- `artifacts/ytdlp-unavailable-video-error.png`: управляемая ошибка недоступного ролика.
- `artifacts/ytdlp-final-short-ready.png`: конечный APK `10c0cb4`, естественный конец VOD.
- `artifacts/ytdlp-session-deleted.png`: сессия отключена и файл удалён; `SESSION_FILE_ABSENT`.

## 6. Оставшиеся ограничения и один следующий шаг

Полная приёмка M0 не объявляется: физические звук/синхронность и обрыв сети остаются `NOT_RUN`.
Production-авторизация не реализована: debug-файл cookies не является готовым механизмом release.
Совместимость YouTube и yt-dlp может измениться. Первый запуск и JS challenge занимают время;
buffering не выдаётся за воспроизведение до появления реального кадра. Тестовая сессия удалена.

Следующий шаг: владельцу подтвердить слышимый звук/синхронность и при возможности выполнить ручной
обрыв/возврат сети на H728 для M0-03, M0-04 и M0-09. M1 не начинается.
