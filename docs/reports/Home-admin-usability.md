# Домашний пульт и хранилище — 2026-11-03

IN SCOPE: `tasks/Home-admin-usability.md`, отдельно согласованный перенос API/Media3 в службу.
Ветка `codex/home-admin-usability` от `ab645cabd3233b7072baa922517715a2c35a546a`
(Draft PR #8); реализация `27245d5`. Отдельный Draft PR, без Merge/Release.

## Результат

HTTP API, coordinator, Media3 и общая YouTube-сессия принадлежат одной foreground-службе
`ParentCastService` (connectedDevice; mediaPlayback добавляется при PLAY). Activity подключает
PlayerView/observer, её закрытие не освобождает общий плеер и не выключает сервер. Загрузка
остаётся в существующей dataSync-службе. Уход на Launcher ставит просмотр на паузу; новый
скрытый PLAY отклоняется `OPEN_PLAYER_ON_TV`. STOP/очередь/подготовка доступны в фоне.

Приложение объявляет собственное mDNS-имя/HTTP-сервис на текущем LAN-интерфейсе. В родительском
меню есть адреса и переключаемый QR (имя/IP, без PIN/token). Для семьи достаточно открыть
страницу в обычной домашней сети, один раз ввести PIN и сохранить закладку. Host/Origin
принимают только адреса самой приставки и её фактическое локальное имя. Роутер/прошивка/DNS
системы приставки не менялись; Wi-Fi необязателен, Ethernet поддержан.

Добавлены полная очистка очереди с STOP и queueVersion, удаление одного/всех скачанных файлов,
список готовых файлов вне оставшейся очереди. Очистка очереди записывается до изменения
памяти/остановки плеера; stale/repeat/fingerprint проверяются. Удаление медиа асинхронно,
отменяет подготовку, хранит pending targets до удаления, восстанавливается после процесса;
ошибка видима и имеет retry. Текущий файл удаляется только при явном stopPlayback. PIN/cookies,
токены, очередь и подборки не входят в каталог медиа и не сбрасываются этими командами.

Лимит — 10GiB, резерв — 1GiB; API/UI показывают used/free/доступный остаток. Прогресс считает
video+audio вместе по точным размерам (filesize/clen/HTTP Content-Length), не filesize_approx.
Неизвестный total остаётся indeterminate; этапы INFO/VIDEO/AUDIO/VERIFY, максимум99% до проверки,
READY100. Новые ZXing/JmDNS/SLF4J notices включены в APK; yt-dlp/Media3 не обновлялись.

## Проверки

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/siovush/Android/Sdk \
  ./gradlew testDebugUnitTest assembleDebug lintDebug compileReleaseKotlin --no-daemon
node --check app/src/main/assets/web/app.js
node tools/prepared_web_test.mjs
node tools/pin_login_web_test.mjs
node tools/m2_web_contract_test.mjs
```

202 JVM tests PASS; debug APK/release Kotlin PASS; lint0 errors/45 warnings (подробности
в локальном HTML/XML отчёте); JS syntax и три frontend contracts PASS. JVM проверяют full clear,
stale/repeat/fingerprint, ошибку записи без потери очереди/STOP, отсутствие скрытого PLAY,
удаление/cancel/recovery/retry, точные/неизвестные размеры обеих дорожек и QR decode.
Frontend-тесты подтверждают отмену удаления без запроса и versioned payload после подтверждения.
Это синтетические тесты, не YouTube-испытания. `git diff --check` для нашего кода PASS;
в дословных upstream NOTICE/license сохранены исходные пробелы/пустые строки.

## H728 и браузер Ubuntu

Идентифицирована H728 Android14, serial21c7406c7086084201a; другая rk3568 не использовалась.
Обычный debug APK обновлён `adb -s 192.168.68.121:5555 install -r …`, без очистки данных.
Подпись установленного APK сверена перед обновлением; после него SHA APK на приставке
совпадает с финальным локальным артефактом.

| Сценарий | Статус | Свидетельство |
|---|---|---|
| Прямой LAN / локальное имя | PASS | Ubuntu resolver →192.168.68.121; health/page200 по parentcast-tv.local; реальный Chrome открыт по этому имени, без ADB-forward |
| PIN / сохранённый вход | PASS | В Chrome выполнен штатный PIN-вход; новая вкладка использует токен без PIN, прежние браузеры видны и старый API-токен действителен |
| QR на Android | PASS / NOT_RUN | Native UI показывает QR с фактическим URL и кнопку переключения; matrix декодирован JVM. Скан физическим телефоном NOT_RUN |
| API в Launcher / без Activity | PASS | Штатный Launcher foreground; API200. Затем удалён только task ParentCast через am stack remove, Activity отсутствует, API и загрузка продолжают работать |
| Фоновая подготовка / прогресс | PASS | Запущена из Chrome при Launcher. Реальный YouTube: VIDEO→AUDIO,0→81%,34180077/41748701bytes; обе FGS активны, детский PLAY не запущен |
| Полная готовность этого ролика | NOT_RUN | Медленная загрузка остановлена PAUSE перед update; partial удалён, WAITING без ложного READY.100% проверено JVM; прежний полный локальный тест — в отчёте PR #8 |
| STOP и скрытый PLAY | PASS | STOP во время загрузки принят за616ms; без экрана PLAY409 OPEN_PLAYER_ON_TV, очередь не изменилась |
| Удаление медиа | PASS | DELETE удалил только собственный Me at the zoo742369bytes. Его тестовые tracks восстановлены как orphan, DELETE_ALL убрал их; usedBytes0, queue items сохранились |
| Полная очистка пользовательской очереди | NOT_RUN | Сохранены16 исходных записей/порядок/currentIndex12; операция проверена JVM/API. В Chrome показано подтверждение; автоматическое закрытие модального диалога ограничено браузерным инструментом |
| PIN-выход / Launcher | PASS | Native PIN принят, QR виден; штатная кнопка выхода открыла stock Launcher и сохранила STOP; auto-resume=false |
| Место | PASS / NOT_RUN | API10GiB/reserve1GiB, свободно около14.7GiB. Заполнение устройства/реальная нехватка места NOT_RUN, граничные случаи покрыты JVM |
| Reboot / priv-app / DO / M4 | NOT_RUN | Не входят в задачу, не выполнялись |

Проверки UI/фонового скачивания/удаления выполнялись на сборке с тем же переносом службы;
финальная сборка дополнительно содержит notices, необязательный Wi-Fi и уточнение total/error
metadata. Проверка плеера и конечного состояния финального APK приведена ниже.

По отдельному разрешению владельца GNOME manual proxy на Ubuntu получил только исключения
parentcast-tv.local,192.168.68.121,192.168.68.123. Прежний список сохранён в ignored
`.dev/home/ubuntu-proxy-before.json` (600). Mode/endpoints/остальные маршруты не менялись.
Переменные proxy окружения терминала и настройки другого VPN автоматически не переписывались.

## APK

`artifacts/home-admin/parentcast-tv-v0.1-home-admin-debug.apk` (локальный ignored артефакт).
SHA-256: `d3c4d6136a76f2b954654b4524a8bedc40be809caf952bda4830c1a9f9d5a617`.
ApplicationId `dev.siyovush.parentcast.tv.debug`, versionCode5, versionName0.1.0-m3-debug.
APK v2 signing PASS; certificate SHA-256
`de2cf22c47b6e4039da04107631b31d92ecddeb6984eeae5d43107337cceecf8`.
Обычный manifest не содержит PrivilegedKioskActivity/ALWAYS, platform-ключ не использован.

## Ограничения

Force-stop Android останавливает службу: открыть ParentCast снова. START_STICKY не является
гарантией против выключения/убийства процесса прошивкой. BOOT receiver/новый автозапуск не добавлены.
`.local` зависит от роутера/клиента; гостевая Wi-Fi/прокси/VPN могут блокировать LAN, есть IP/QR fallback.
Для нового PLAY нужен открытый экран ParentCast на ТВ. HTTP рассчитан на доверенную домашнюю сеть.
YouTube-сессия временная; скорость/доступность YouTube и готовность всей программы не гарантируются.
Это HOME BETA под наблюдением родителя, полноценный Lock Task/privileged остаётся NO-GO.

## Финальный APK на устройстве

PASS: обычный финальный APK установлен поверх предыдущего, его SHA совпадает с локальным.
Временно выбран потоковый режим для короткой проверки новой службы: реальный выбранный
родителем текущий ролик дал PLAYING, Media3 подтвердил1920x1080 avc1.640028 и mp4a.40.2,
позиция0→39755ms. Затем наблюдалась BUFFERING: медленная сеть сохраняет ограничения
потокового режима; этот тест не объявляется бесшовным просмотром. При HOME после завершения
перехода Activity получены PAUSE/displayReady=false, stock Launcher foreground, API200.

В конце STOP, stock Launcher является HOME, child=false/auto-resume=false. Подготовка снова
включена и приостановлена, как в исходном baseline; готовых пользовательских файлов до теста
не было. Удалены только созданные нами демонстрационные/неполные тестовые медиа. Все16
исходных queueItemId/videoId, порядок и currentIndex12 совпали с baseline. Подборки были пустыми
и остались пустыми. Hash parent-auth и настройки YouTube-сессии не изменились; cookies-файл
сохранён с mode600 и штатно включён, значения не читались/не выводились. Старый браузерный
API-токен действителен, прежние записи доступны; добавлен только штатный токен Chrome.
Служба пульта оставлена работающей за Launcher. Reboot/DO/Happ/системные разделы не менялись.

Локальные свидетельства (ignored): `artifacts/home-admin/browser-progress.png`, `progress.json`,
`final-player-state.json`, `final-check.json`. Полное очищение пользовательской очереди и скан
QR телефоном намеренно не объявлены аппаратным PASS. Следующий шаг — открыть семейный пульт,
нажать «Подготовить очередь» и дождаться READY перед детским просмотром.
