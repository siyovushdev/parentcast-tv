# HOME BETA: вход браузера только по PIN

Дата: 2026-11-02. База main `90978ace5677e483d0e9d982e1cf8b0b08595202`;
ветка `codex/pin-only-browser-auth`. Отдельный Draft PR в main, без Merge/Release.

## Изменение

Новый браузер отправляет действующий родительский PIN в прежний POST `/api/pair`; label
необязателен. Одноразовый код и открытие окна на ТВ больше не требуются. Ответ по-прежнему
содержит отдельные deviceId/token. Формат AtomicTokenStore и ключ localStorage не изменены:
существующие токены работают, новый токен сохраняется, доступ каждого браузера отзывается отдельно.
PIN не используется как bearer и не сохраняется браузером; поле очищается перед ожиданием ответа.

Удалены поле/инструкции кода в панели и устаревшая кнопка его выдачи на ТВ. Повторный клик
во время входа не создаёт лишний токен. Ошибка входа сообщает о PIN/лимите или связи.
Адаптированы тестовые fixtures и старые browser/API helpers, README и актуальная часть ТЗ.

Сохранены прежние Host/Origin/Referer/custom-header/CORS и закрытые управляющие API.
PinSecurity, TokenStore и RequestSecurity не менялись. Лимитер M1 оставлен прежним:
5 ошибок за10 минут для адреса клиента, в памяти процесса; синхронизация не позволяет потерять
параллельные попытки. Локальный родительский gate M3 и его persistent limiter не менялись.

Playback, queue, collections, HOME/DeviceMode, privileged-код и build version не изменялись.
Из MainActivity удалено только создание/отображение старого одноразового кода.

## Проверки

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/siovush/Android/Sdk \
  ./gradlew testDebugUnitTest assembleDebug lintDebug compileReleaseKotlin --no-daemon
node --check app/src/main/assets/web/app.js
node tools/pin_login_web_test.mjs
node tools/m2_web_contract_test.mjs
python3 -m py_compile tools/m1_hardware_test.py
git diff --check
```

PASS: 178 JVM/unit/integration тестов, 0 failures/errors/skipped; lint0 errors/43 warnings;
обычный debug build и release Kotlin compilation. После последней правки теста unit suite повторён.
Новые JVM-проверки: только PIN без label/кода/действий на ТВ; пустой/неверный PIN; лимит и истечение
окна; конкурентные ошибки/правильные входы; независимые токены, старые записи и отзыв одного;
PIN не bearer; Host/Origin/Referer/header guards. JS проверяет реальный app.js: payload без кода,
token persistence/reload, отсутствие PIN в storage, двойной клик, отказ/сеть/возврат к входу после отзыва.
JVM/JS — синтетические проверки, отдельно от H728.

## H728 и реальный Chrome Ubuntu

Цель подтверждена по h728/Android14. Только `adb install -r`, тот же package/signer/versionCode,
без очистки данных. ParentCast открыт на переднем плане в STOP; окно сопряжения на ТВ не открывали.
Новый Chrome origin — непосредственно `http://192.168.68.121:8765`, без ADB forward.

| Сценарий | Статус | Наблюдение |
|---|---|---|
| Вход только по PIN | PASS | В новой панели нет поля кода; правильный PIN открывает очередь/подборку |
| Неверный PIN | PASS | Один неверный PIN отклонён, поле очищено; правильный затем принят |
| Reload без PIN | PASS | Панель открыта с сохранённым токеном, повторного PIN нет |
| Независимый отзыв | PASS | Отозван только новый тестовый браузер; reload вернул PIN-форму; повторный вход успешен |
| Старый браузер | PASS | Прежний Chrome M2 token (localhost origin через прежний forward) продолжает читать состояние |
| Защита API | PASS | Direct LAN: bad Host421, Origin/Referer/header403, без bearer401, PIN как bearer401 |
| Данные | PASS | Все10 исходных token records сохранились неизменными; queue/collections/mode files совпали по hash |
| PIN/cookies/UID | PASS | Metadata файлов и UID прежние; PIN использован из разрешённого локального файла без вывода, значения cookies не читались |
| Полный lockout5 ошибок/timeout | NOT_RUN на H728 | Полностью покрыт JVM; на устройстве не создавали искусственную10-минутную блокировку |

После проверки отозван только созданный здесь временный API-token; записи не удалялись.
Новый браузер после повторного PIN остаётся авторизован. Исходные токены не отзывались.
Сохранены штатный Launcher, child=false, auto-resume=false, STOP и прежняя очередь24/index21.
Команды playback/queue/playlist apply не отправлялись. Cookies/PIN/настройки не сбрасывались.

**UNKNOWN / OUT OF SCOPE:** при уходе ParentCast в фон за штатный Launcher HTTP перестал отвечать;
возврат приложения на передний план восстановил ответы. Причина и baseline A/B не исследовались;
это не объявляется доказанной регрессией нового входа. Фоновый lifecycle/сервер в этом PR не менялись.
Старая открытая вкладка со старым HTML требует обновления страницы; в свежей панели кода нет.

## APK и границы

Локальный ignored артефакт: `artifacts/pin-browser-login/parentcast-tv-v0.1-pin-login-debug.apk`.
SHA-256: `862bc4698ff6c1a5a40ac86f38878d546be6ccb1cb5375530519fdb2e52e6f18`.
applicationId `dev.siyovush.parentcast.tv.debug`, versionCode5; signing verify PASS, signer
`de2cf22c47b6e4039da04107631b31d92ecddeb6984eeae5d43107337cceecf8`.
Установленный APK побайтно совпал с артефактом; PrivilegedKioskActivity в manifest отсутствует.

Очищенные свидетельства: `artifacts/pin-browser-login/` — api-safe.json, browser-safe.json,
data-safe.json, apk-safe.json и Gradle logs. Секреты — только локально в .dev с ограниченными правами,
не в Git/логах/PR. Reboot/flash/priv-app/Device Owner/Happ не затрагивались, M4 не начинался.
Следующий шаг — ревью отдельного PR. Merge не выполнять.
