# M3: privileged ALWAYS на H728 — исследование и условный план

Дата: 2026-11-02. Это подготовка кандидата, **не разрешение на системную установку**.
Системные разделы, подпись установленного APK, Device Owner и boot не изменялись.
Приватный ключ не читался и не экспортировался; использован только предоставленный публичный PEM.

## Вывод

**IN SCOPE:** официальный путь без Device Owner существует. На AOSP Android 14 ALWAYS работает
при `ApplicationInfo.isPrivilegedApp()`, независимо от platform signer. Обычного `FLAG_SYSTEM`
или platform-подписи недостаточно. На текущей H728 ParentCast — обычный `/data/app` APK.
Корректное размещение в priv-app представляется возможным на уровне Android, но **безопасный способ
изменения именно этой прошивки и возврат управления после reboot пока не подтверждены**.
Аппаратная совместимость ALWAYS остаётся UNKNOWN/NOT_RUN.

Первичные источники:

- [Manifest lockTaskMode](https://developer.android.com/guide/topics/manifest/activity-element#lmode).
- [ActivityRecord, Android 14 r1](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/wm/ActivityRecord.java#2236): ALWAYS/NEVER понижаются до NORMAL без privileged flag.
- [ApplicationInfo](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/core/java/android/content/pm/ApplicationInfo.java#2509),
  [ScanPackageUtils](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/pm/ScanPackageUtils.java#884): privileged и platform signer — разные признаки.
- [LockTaskController](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/wm/LockTaskController.java#807),
  [ActivityTaskSupervisor](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java#861): ALWAYS-root получает LAUNCHABLE_PRIV и настоящий LOCKED без DPC allowlist.
- [Завершение root](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/wm/LockTaskController.java#253),
  [асинхронная смена режима](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/wm/LockTaskController.java#683): root может выполнить finish; NONE следует проверять отдельно.

Это вывод из точного AOSP tag, а не доказательство поведения изменённой OEM-прошивки.

## Read-only состояние H728

| Свойство | Наблюдение |
|---|---|
| SoC / Android / ABI | h728 / 14 / arm64-v8a |
| Build | ADT-3/adt3/adt3:14/UP1A.231105.001.A1/eng.ubuntu.20250806.161650:userdebug/release-keys |
| Patch / debug | 2024-06-05; userdebug; ro.debuggable=1 |
| ADB shell | Уже uid=0; adb root не вызывался, root-модификаций не было |
| AVB | verifiedbootstate=orange, flash.locked=0; vbmeta.device_state/veritymode пусты |
| Slot / partitions | _a; virtual A/B и dynamic partitions; system_a/product_a/vendor_a, super |
| Mounts | `/` dm-1, `/product` dm-0, `/vendor` dm-3: ext4 **ro** |
| system_ext | `/system/system_ext`, внутри system |
| priv-app | Каталоги есть в system (47), system_ext (14), product (3); root:root 0755 |
| SELinux | `/sys/fs/selinux/enforce=0` уже до работ; не изменялся |
| ADB after reboot | service.adb.tcp.port=5555, persist.adb.tcp.port пуст; USB фактически не проверен |
| Owner | `dpm list-owners`: no owners |

Orange/unlocked и существующая root-shell **не доказывают**, что remount или запись в dynamic
partition обратимы. Размер свободного места, целостность inactive slot и рабочий recovery/USB путь
не подтверждены. `remount`, `disable-verity`, `setprop`, `fastboot`, прошивка и reboot не выполнялись.

## Публичные сертификаты

SHA-256 сертификатов, не ключей:

| Источник | SHA-256 |
|---|---|
| Предоставленный `platform.x509.pem` | `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8` |
| Проверенный системный TvSettings APK | `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8` |
| Embedded certificate framework-res / пакет android | `16af60cf2f399316e974d3c81078cd3afed3c2485922c89e4f7aa64350e0d749` |
| Текущий debug ParentCast и оба кандидата сборки | `de2cf22c47b6e4039da04107631b31d92ecddeb6984eeae5d43107337cceecf8` |

TvSettings проходит apksigner verify. У framework-res **не проходит проверка APK v3 digest**;
SHA-256 скопированного APK совпадает с файлом на устройстве. Публичный embedded certificate
соответствует signature hash PackageManager для `android` (`5f5a234d`), а предоставленный PEM —
hash TvSettings (`b4addb29`). Следовательно, нельзя объявить предоставленный сертификат текущим
platform signer пакета android. Причина модификации framework-res — **UNKNOWN**, в M3 не исправлялась.
Непроверенная целостность прошивки — дополнительный блокер системной установки.

Сам PKCS12 без пароля не отдал certificate chain; пароль и приватный ключ не извлекались.
В Git нет PEM/JKS/PK8, системных APK и сырых dumpsys/logcat. Локальное очищенное свидетельство:
`artifacts/m3-home-kiosk/platform-certificates-safe.json`.

## Реализация кандидата

`./gradlew assemblePrivileged` создаёт debug-кандидат с тем же package
`dev.siyovush.parentcast.tv.debug`, текущим debug signer и versionCode=6 (обычный M3=5).
Нет `sharedUserId=android.uid.system`, новых privileged permissions или platform signing config.
Для самого ALWAYS permission allowlist не требуется; требования OEM scanner проверяются отдельно.

Обычные MainActivity/HOME остаются NORMAL. Только private `PrivilegedKioskActivity` имеет ALWAYS,
собственный task affinity и запускается корнем через NEW_TASK|CLEAR_TASK. Нет HOME/LAUNCHER-фильтра.
Вход: локальный PIN, сохранённый доступный штатный Launcher, выбранный ParentCast HOME,
foreground, системная установка и отсутствие другого Lock Task. Публичный FLAG_SYSTEM — лишь
предварительный guard; успешной защитой признаётся только фактический `LOCK_TASK_MODE_LOCKED`.

Передача авторизации — одноразовая память процесса на 30 секунд, без авторизующих Intent extras.
Проверка LOCKED ограничена 3 секундами: NONE/PINNED, отказ admission или отсутствие task-root
отменяют сеанс. Нет fallback в screen pinning и циклического перезапуска Activity.
Отдельный privileged флаг включения не записывается на диск: **первый пробный режим не включится
автоматически после смерти процесса или boot**. Постоянный kiosk-autostart ещё не принят.

Родительский выход: повторный PIN → сохранить child=false и STOP → finish ALWAYS-root.
Для возврата Launcher обычный HOME ждёт фактический NONE (ограниченная проверка), затем запускает
существующий Android chooser после отключения собственного alias. Recovery разрешение одноразовое,
в памяти процесса. При истечении/смерти процесса родитель повторно вводит PIN в обычном HOME.
`stopLockTask()` не используется как способ оставить ALWAYS-root живым; это исключает его повторное
автоматическое закрепление. Переход Activity передаёт единственное владение Media3/HTTP.

## Условия допуска к установке — сейчас NO-GO

1. Независимо подтвердить восстановление H728 после reboot: рабочий USB ADB либо другой проверенный
   постоянный ADB, stock HOME, recovery/оригинальные образы именно этой версии. Сейчас это отсутствует.
2. Проверить происхождение/целостность firmware, layout/AVB и допустимый OEM способ создания образа.
   Наличие platform key и orange не заменяет эту проверку. Не менять vbmeta или SELinux ради удобства.
3. Сохранить защищённую резервную копию данных M0–M2 с проверкой восстановления, без вывода PIN,
   токенов или cookies. Зафиксировать package/signer/version/appId/UID, активный codePath и HOME.
4. Подготовить конкретный образ и дифф единственного нового каталога, например
   `/system/priv-app/ParentCastTV/ParentCastTV.apk`, с правильными owner/mode/SELinux labels и свободным
   местом. Какой раздел/образ писать — определить после исследования recovery; сейчас не выбран.
5. Отдельно согласовать с владельцем точный способ записи, затрагиваемые разделы, reboot и откат.

Нельзя просто переподписать нынешний package предоставленным ключом. Android 14 при первом
появлении system APK с несовместимым signer может удалить старый data package **без KEEP_DATA**:
[InstallPackageHelper](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/pm/InstallPackageHelper.java#4267),
[DeletePackageHelper](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/pm/DeletePackageHelper.java#534).
Тот же signer/package и appId сохраняют стандартный путь данных; это пока не аппаратный PASS.

Версия/активный manifest важны: старый `/data` APK может остаться активным поверх system base.
Признак privileged наследуется updated-system APK от базы, но manifest старого APK не превращается
в ALWAYS: [ScanPackageUtils](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/pm/ScanPackageUtils.java#489).
Поэтому candidate=6 > normal=5; после любой будущей установки обязательна проверка active codePath,
version, same signer, прежнего UID, SYSTEM/PRIVILEGED и private ALWAYS-root.

## Условная установка и откат после отдельного разрешения

После выполнения условий выше: установить подготовленный образ только согласованным OEM способом,
выполнить согласованный reboot, сначала проверить ADB/stock HOME/данные, затем вручную через PIN
выполнить один trial LOCKED → PIN → finish → NONE → stock Launcher. Проверить SETTINGS/ASSIST/HOME/
RECENTS, UI-петли, веб-пульт и отсутствие второго плеера. При FAIL не сохранять автоматический режим.

Откат проектируется **до** записи: PIN-disarm/STOP → finish root → NONE → stock HOME; подготовить
обычный APK с тем же signer/package и versionCode не ниже candidate, сохранить data APK как опору;
затем восстановить согласованный исходный системный образ/убрать только собственный base согласно
проверенному OEM пути, сохранив данные и UID. Конкретные команды и новый rollback APK готовятся
после выбора способа работы с образом, до согласованной установки. Сейчас такой откат на H728
не проверен и автоматического системного установщика в репозитории нет.

`pm clear`, uninstall, смена signer, слепой downgrade и «удалить обновления» не являются планом
отката: [DeletePackageHelper](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/pm/DeletePackageHelper.java#651)
показывает условия удаления данных при возврате к более старой системной версии.
Device Owner не требуется для этого пути и не назначается. M4 не начинается.
