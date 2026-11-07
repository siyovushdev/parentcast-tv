# Сообщения разрушали desktop-раскладку — 2026-11-07

REGRESSION / IN SCOPE. Владелец прислал screenshot настоящего Chrome:
навигация наложена на карточку, заголовок переносится по одной букве.
Причина подтверждена целевым Chrome fixture, не предположением о браузерном zoom.

## Причина и минимальная правка

`controlSection` — grid с колонками174px + content. У nav/app-pages не было явных
позиций, а command-notice имел grid-column2. Когда notice становился видимым,
авторазмещение отправляло app-pages в первую колонку. Целевой тест до исправления
FAIL: navRight202px, pagesLeft28px, pagesWidth174px, currentTitle.width0,
noticeWidth1005px. Эти размеры объясняют screenshot владельца.

Заданы именованные области `nav notice / nav pages` и явные grid-area для каждого
из трёх блоков. Nav занимает левую область, сообщения и страницы — правую независимо
от видимости сообщений. На mobile остаётся прежний block layout и нижняя навигация.
Также исправлено скрытие notice: он пуст только если нет ни одного непустого message
и ни одной видимой кнопки. Прежний селектор ошибочно скрывал и непустое уведомление,
если другое сообщение было пустым.

Playback/HTTP API/очередь/авторизация/режимы не менялись. Нативное отличие APK —
только обычные versionCode26/versionName, чтобы установить проверенный web asset.
Обновление не считается применённым до установки на H728 и reload браузера.

## Проверки

```bash
node tools/family_web_test.mjs
node tools/experience_web_test.mjs
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/siovush/Android/Sdk \
  ./gradlew :app:lintDebug :app:assembleDebug :app:compileReleaseKotlin --console=plain
```

Layout regression FAIL→PASS. Проверены0/1/2сообщения, пустой notice с видимой retry
и полностью пустой notice; ширины1856/1280/1000/850/701/700/390/320px с сообщениями
и без, правая колонка на desktop/tablet, отсутствие overflow. Оба Chrome fixture
suite PASS; screenshots осмотрены. Это настоящий Chrome с синтетическим API,
не screenshot подключённой пользовательской вкладки. Отдельные JVM-тесты не
перезапускались для CSS-изменения; прежние405 относятся к неизменённому native-коду.
Lint0errors, debug/release PASS. Снимок критического состояния внеGit:
`.dev/family-web-test/desktop-command-notice.png`.

## Проверка окружения без прерывания просмотра

Через домашний Ubuntu read-only обнаружено: `parentcast-tv.local` разрешается в
192.168.1.10, HTTP200; прежний192.168.1.16 не отвечает. ADB подключён только к адресу,
полученному из этого hostname, serialH728 …84201a подтверждён. Сеть не сканировалась,
Happ/настройки сети/ADB не менялись. Пульт показал PLAYING/PLAY, позицию503545ms,
136роликов. STOP/PAUSE/PLAY/NEXT и изменения программы не отправлялись.

Инструмент браузера видит Chrome на другом ПК; домашний Chrome не запущен с remote
 debugging. Его профиль/история/расширения/масштаб и открытые вкладки не менялись.
Выполнены проверка серверных assets/статуса и отдельные layout fixtures; не заявляется
управление именно вкладкой со screenshot. Для уже открытой страницы Ctrl+Shift+R
временно убирает сообщения и возвращает раскладку; это не устраняет кодовую причину.

**Установка H728: NOT_RUN — запрошен выбор времени обновления**, поскольку сейчас
идёт просмотр. Первый комментарий обещал его не прерывать; после проверки выяснено,
что CSS поставляется из APK и нужен краткий restart для обновления. Код/тесты/APK
подготовлены до вопроса. Инструкция AGENTS разрешает обычные debug update, но не
требует отдельного подтверждения; здесь уточняется время активного просмотра.
`--dont-kill` не использовался: нет подтверждённого непрерывного полного обновления
этого APK на данной прошивке. Только read-only `pm help`, без установки/эксперимента.

## Артефакт и следующий шаг

Ветка `codex/web-layout-notice`, Draft PR к `codex/online-recovery` (PR #21).
Обычный debugv26, `dev.siyovush.parentcast.tv.debug`, прежний debug signer,
apksigner verify PASS. APK:
`artifacts/web-layout/parentcast-tv-v0.1-web-layout-debug.apk`.
SHA-256: `cd5708ce11ba02c4b7fca973c370450b4e3e2a2ad445d7dec285245ae198b856`.

Данные приставки/доступы/текущий просмотр не изменялись. Следующий шаг — применить
готовый update-r в выбранный владельцем момент с сохранением актуального
queueVersion/ролика/позиции/намерения, затем reload и проверка live CSS.
Без Merge/Release/reboot/system/privileged/platform key.
