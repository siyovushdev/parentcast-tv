# M0 — эксперимент авторизованного YouTube

## 1. Статус: код отдельно от воспроизведения

Ветка `m0/authorized-youtube-spike`, implementation commit:
`ef61541db8eb7dbcf74748a0ad2b081b06cc51fd`.

**Реализовано и проверено на уровне кода/UI:** debug-only импорт owner-supplied Netscape cookies,
включение сессии вручную, передача cookies в NewPipe Downloader и Media3, отключение с удалением.

**Не выполнялось:** импорт реального Google-аккаунта, авторизованное воспроизведение, звук,
синхронность и аппаратный сеанс с реальной сессией. Эти пункты — **NOT_RUN** до ручного импорта
владельцем. Агент cookies не получал и не использовал основной Premium-аккаунт.

## 2. Что подтверждено по NewPipe Extractor

Проверен upstream `v0.26.5`, commit `f9e6bb808f82bf3e4dc1f2a29a16fd376931c8ef`.

- Отдельного полноценного аккаунтного/session API или официального Google Login у NewPipe нет.
- `YoutubeParsingHelper` самостоятельно задаёт только consent cookie `SOCS`.
- Extractor-запросы, включая InnerTube player requests, проходят через интерфейс `Downloader`.
  Поэтому owner-supplied `Cookie` можно добавить transport-уровнем без форка NewPipe.
- Android InnerTube использует `youtubei.googleapis.com`, WEB InnerTube — `www.youtube.com`.
  Cookies домена YouTube передаются первому только явно разрешённым trusted-host правилом.
- Media3 не использует NewPipe Downloader, поэтому сессия должна передаваться отдельно и в player.

Это экспериментальная поддержка watch-запросов, а не утверждение о полноценной поддержке аккаунта
NewPipe. Работоспособность будет признана только после реального воспроизведения.

## 3. Реализация и безопасность

- `app/src/debug` содержит реальный session provider; `app/src/release` возвращает только
  `DisabledYouTubeSession`. Provider общий для жизненного цикла процесса.
- Владелец сам помещает `youtube-cookies.txt` в `filesDir` debug APK. Импорт выполняется кнопкой,
  автоматического включения после restart нет.
- Принимается Netscape format, включая `#HttpOnly_`. Отфильтровываются чужие домены и истёкшие
  записи. Требуется account cookie (`SID`, `SAPISID` или поддерживаемый `__Secure-*PSID*`).
- `CookieJar.NO_COOKIES` сохраняется: ответные `Set-Cookie` не записываются. Импортированный файл
  находится только в приватном хранилище приложения и получает mode 600.
- Consent `SOCS` объединяется с session cookies. Cookies не записываются в `ResolvedVideo`, очередь,
  отчёты или логи. Media3 получает их только в памяти при подготовке потока.
- «Отключить и удалить сессию» сначала останавливает плеер, затем очищает память и удаляет файл.
- Диагностика исключений дополнительно маскирует URL, `Authorization`, Bearer, token и YouTube
  session cookie names.
- WebView, Google Login, автоматический экспорт cookies и изменения Happ отсутствуют.
- `.gitignore` дополнительно исключает `*cookies*.txt`, `*cookies*.json` и `*.netscape`.

## 4. Реальные проверки кода и UI

Команда в рабочем каталоге и в чистом checkout:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME="$HOME/Android/Sdk" \
./gradlew testDebugUnitTest assembleDebug lintDebug compileReleaseKotlin --no-daemon
```

Результат: **PASS**, `BUILD SUCCESSFUL`; 40 tests, 0 skipped/failures/errors; lint 0 errors,
20 warnings. Release Kotlin compilation подтверждает отсутствие debug session implementation.

На H728 (`ro.soc.model=h728`, Android 14) установлен debug APK. На искусственном dummy Netscape-файле
проверено: экран видит готовый файл, кнопка включает сессию и показывает только количество cookies,
кнопка отключения удаляет файл. После теста файл отсутствует. Dummy-сессия к YouTube не применялась.
Это не доказательство авторизованного воспроизведения.

## 5. APK

Путь: `artifacts/authorized-youtube/parentcast-tv-m0-authorized-debug.apk`.

Размер: `17147941` bytes.

SHA-256: `75ab8581f99d4787e038b787d04327fd60b0c26da7337e02613d968420ee68cf`.

APK не включается в Git и не публикуется как Release. Merge не выполняется.

## 6. Ручной импорт владельцем

1. Самостоятельно подготовить Netscape-файл **только тестового Google-аккаунта**. Основной
   Premium-аккаунт не использовать. Не отправлять файл или его содержимое агенту.
2. Передать файл в приватное хранилище debug APK с Ubuntu, подставив актуальный serial H728 и
   локальный путь. Команда ничего не печатает из содержимого:

   ```bash
   adb -s <SERIAL_H728> shell "run-as dev.siyovush.parentcast.tv.debug sh -c 'cat > files/youtube-cookies.txt && chmod 600 files/youtube-cookies.txt'" < /secure/local/path/youtube-cookies.txt
   ```

3. Запустить ParentCast и открыть debug-экран через Menu/Settings или удержание DPAD Center:

   ```bash
   adb -s <SERIAL_H728> shell am start -n dev.siyovush.parentcast.tv.debug/dev.siyovush.parentcast.tv.ui.MainActivity
   ```

4. Нажать «Включить сессию из app-private файла». Ожидается только сообщение о количестве
   YouTube cookies. Затем сообщить агенту «сессия включена», без содержимого файла. После этого
   выполняются реальные проверки видео, звука, очереди и отсутствия YouTube UI.
5. После тестирования нажать STOP, затем «Отключить и удалить сессию». Аварийное удаление без
   вывода содержимого:

   ```bash
   adb -s <SERIAL_H728> shell "run-as dev.siyovush.parentcast.tv.debug rm -f files/youtube-cookies.txt"
   ```

После restart файл не активируется сам: повторное включение только кнопкой. Не использовать
`adb logcat`, текстовый вывод файла или передачу скриншотов с секретами для настройки.

## 7. Следующий шаг

Владельцу выполнить ручной импорт и включить сессию. Затем на H728 проверяются реальные VOD, звук,
раздельные дорожки, очередь и отсутствие постороннего YouTube UI. Если NewPipe отклонит и
авторизованный путь, текущая реализация не заменяется; отдельно согласуется минимальный вариант
источника на базе yt-dlp.
