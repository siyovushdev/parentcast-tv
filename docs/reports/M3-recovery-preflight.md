# M3 privileged: read-only подготовка восстановления — NO-GO

Дата: 2026-11-02. Дополнение к PR #6 после независимого ревью.
Scope: восстановление при неработающем Android, исходный образ, откат и причина framework-res digest mismatch.
M0–M3 не перерабатывались и успешные испытания не повторялись. APK не устанавливались, process-stop,
reboot/flash/remount/setprop/slot switching не выполнялись. Platform-ключ не использовался.
PIN/cookies/очередь/Happ не открывались и не изменялись.

## Решение

**NO-GO для privileged-испытания на текущем стенде.** Независимый от Android способ возврата
управления не подтверждён, запасной bootable слот отсутствует, точный откат с сохранением данных
не установлен. Найден официальный образ с совпадающим именем сборки, но это ещё не доказанный
образ восстановления текущего состояния приставки.

## Устройство и независимое восстановление

Цель адресовалась только явно `adb -s 192.168.68.121:5555`. Проверены SoC h728 и Android14;
device-tree compatible: `allwinner,h728`, `arm,sun55iw3p1`. Hardware `sun55iw3p1`, board platform
`diana`, display ID `X96QProPlus_20250806-1630`, fingerprint по-прежнему ADT-3/userdebug.
Физическая PCB/DDR/eMMC/Wi-Fi ревизия по этим строкам не доказана.

| Проверка | Наблюдение | Значение для отката |
|---|---|---|
| USB `lsusb` | Android ADB, fastboot и Allwinner FEL не обнаружены | Не доказывает возможность входа в BootROM |
| `fastboot devices` | Пусто | Нет проверенного host-to-bootloader пути |
| CH340 | USB-serial адаптер виден, `/dev/serial/by-id` отсутствует | Связь именно с H728/UART не установлена; порт не открывался |
| ADB | service port5555, persistent port пуст; доступ сейчас только TCP | Android/LAN могут не подняться при ошибке загрузки |
| `bootctl get-current-slot` | 0 (_a); число slots=2 | Число слотов не означает две рабочие системы |
| `is-slot-bootable 0` / successful0 | 1, exit0 / 1, exit0 | Текущий A отмечен загрузочным и успешным |
| `is-slot-bootable 1` / successful1 | 0, exit70 / 0, exit70 | **B неbootable, не является fallback** |
| `lpdump` metadata0 | product_a/system_a/system_dlkm_a/vendor_a/vendor_dlkm_a, без _b | В текущей таблице нет запасного system_b |
| `snapshotctl dump` | Update state none, active userspace snapshots0 | Нет подтверждённого OTA rollback snapshot |
| Mounts | /, product, vendor ext4 ro; существующие overlays только system_dlkm/vendor_dlkm | framework-res не подменён этими двумя overlays |
| system_a mapper | linear, super sectors655360..4663936 | Не dm-verity target; orange/unlocked не доказывают доверенную целостность |

Официальный [путь производителя PhoenixSuit](https://x96mini.com/blogs/news/how-to-use-phoenixsuit-to-flash-firmware-of-x96-tv-box)
описывает upgrade button в AV-разъёме и USB A–A, но инструкция общая, не доказывает точный порт/кнопку
этой ревизии. Она прямо предусматривает форматирование/потерю данных. Такую процедуру **нельзя
выдать за откат с сохранением PIN/cookies/очереди/Happ**. Никаких кабелей/кнопок/питания не переключали.

FEL — потенциальный путь независимо от Android, описанный [авторами sunxi-tools](https://github.com/linux-sunxi/sunxi-tools#sunxi-fel).
Но наличие механизма у семейства Allwinner не доказывает загрузчик DDR, поддержку H728 инструментом
и выборочное восстановление этой приставки. На Ubuntu `sunxi-fel`/PhoenixSuit не установлены;
инструменты/драйверы/ОС не менялись. Recovery/fastbootd реально не запускались.

## Исходный образ: найден кандидат, точное соответствие не подтверждено

На официальной [странице X96](https://x96mini.com/pages/help-support) раздел **X96Q Pro+** ведёт
в [публичный каталог производителя](https://drive.google.com/drive/folders/17_-nyidxGJAtBq2tupV-cO2vS1VPOEe6).
Там действительно есть `X96QProPlus_20250806-1630.img`, file ID
`1CYg6CjUETtt76LzZ_2FdtPR6oOL4WNsN`, размер **2179137536 bytes**.
Прочитаны HTTP Range, без полного скачивания/запуска содержимого:

- Ответ206 с согласованными Content-Range/размерами, magic IMAGEWTY, header version0x300,
  49 записей контейнера. Таблица содержит boot/vendor_boot/init_boot/super/vbmeta/dtbo и служебные файлы.
- `super.fex`: offset398959616, size1761280672; `boot.fex`: offset289904640, size67108864.
  Это upgrade-контейнер, а не готовый побайтовый образ eMMC или текущей super.
- Прочитаны sys_config/sys_partition/cardscript и три vbmeta. Cardscript содержит запись boot0/boot1;
  его нельзя запускать как заведомо неразрушающий «live recovery».
- Все три vendor vbmeta имеют AVB0, flags0. На устройстве прочитаны соответствующие начала
  `vbmeta_a` (8192 bytes), `vbmeta_system_a`/`vbmeta_vendor_a` (по4096 bytes): **все нули**.
  Затем это проверено полным чтением соответствующих разделов: vbmeta_a131072 bytes,
  vbmeta_system_a65536 bytes, vbmeta_vendor_a65536 bytes — все целиком нулевые.
  Эти байты не совпадают с официальным образом. Отсутствие полного AVB дополнительно согласуется
  с orange, linear system mapper и возможностью загрузить APK с нарушенной целостностью.

Это доказывает различие проверенных метаданных текущего состояния и OEM-пакета; не доказывает,
кто/когда изменил приставку. Совпадение build name не равно совпадению всех разделов/прошивки.
Полный SHA-256 контейнера, восстановление и совместимость PCB/DDR не проверены; файл не объявляется
доверенной готовой копией для flash. Целый локальный исходный image/backup в проверенных Downloads,
Documents, Desktop, Tools, android-root-tools, VM-ISO, локальных облачных каталогах и Transcend не найден.
Поиск относился к именам firmware/images; посторонние проекты и их файлы не менялись.

## framework-res: установлена непосредственная причина mismatch

Повторный `adb pull /system/framework/framework-res.apk` получен в новый локальный файл.
SHA-256 совпал у устройства, новой копии и копии начала M3:
`3a5dcba4d2b77e2849abf817e9cc5599105a6e3b5ae5d6664e7f1858af6373d1`.
ZIP CRC всех3343 entries исправны. Ошибка не вызвана ADB-копированием.

`apksigner verify --verbose --print-certs` воспроизводит v3 CHUNKED_SHA256:

- expected `8827a15e16764b17aef0db8cdc080a3005554f28de4bfac343a2ac373a365877`;
- actual `7e191d7c7ee778f70bd0cd6b3a64b866e21f398e045e1ed2c643fd9c2d0f991c`.

Независимо прочитаны v2/v3 signing blocks: RSA PKCS1 SHA256 подписи **signed-data** математически
верны, certificate соответствует вложенному SPKI. Сертификат16af60cf…d749 совпадает с прежним
PackageManager signature hash5f5a234d. Это доказывает подлинность старых подписанных метаданных,
**а не целостность нынешнего APK**. [Требования проверки v3](https://source.android.com/docs/security/features/apksigning/v3).

Дополнительно проверены v1 данные: CMS-подпись CERT.RSA над CERT.SF верна, digest MANIFEST.MF в SF
совпадает, однако из1555 присутствующих подписанных entries **1442 имеют другой SHA-256**,
включая AndroidManifest.xml и resources.arsc;1779 имён из подписанного manifest отсутствуют;
1785 текущих payload entries вообще не перечислены в нём. Среди новых — ATV animations;
есть переименования resource qualifiers. Это **не только ZIP alignment/метаданные**.

**Вывод IN SCOPE:** APK содержит подписи/хеши от другого содержимого при пересобранных/заменённых
ресурсах; согласованная переподпись текущего APK не выполнена. Именно это вызывает mismatch.
**UNKNOWN:** точный автор, время, инструмент и происхождение пересборки. Без исходного APK из
подтверждённого образа нельзя приписать её OEM, ATV-порту или более позднему вмешательству;
оснований объявлять причиной вредоносный код нет. Файлы системы не исправлялись.

Почему Android вообще может загрузиться: [AOSP14 InstallPackageHelper](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/pm/InstallPackageHelper.java#4248)
позволяет пропустить полную проверку APK при сканировании системного раздела, проверяя signed-data.
Это объясняет совместимость наблюдения с обычным системным scan path, но не доказывает точный
исполняемый OEM-код или доверенность раздела на этой прошивке. При обычной установке такой APK
считать валидным нельзя.

## Откат и условия GO

Пока **нет исполнимого проверенного без потерь способа отката**. PIN → finish → NONE → Launcher
помогает только при работающем Android, не является boot-failure recovery. Переключение на B не
подходит. Full PhoenixSuit/карта с автопрошивкой могут стереть данные и не разрешены.

Для следующего разрешённого испытания необходимы все условия:

1. Отдельно согласовать физическую проверку входа/выхода из независимого recovery/FEL без flash:
   точная PCB, USB-порт/кнопка/кабель/питание, распознавание именно H728 и совместимого loader на host.
   Продемонстрировать возврат исходной Android-системы и ADB. Это требует смены режима/питания,
   поэтому **сейчас NOT_RUN**, а не скрыто выполненный reboot.
2. Получить полный исходный образ с SHA-256 и подтверждением PCB/DDR/layout; выяснить происхождение
   установленной модификации. Официальный stock-пакет с совпадающим именем не заменяет исходное
   состояние с нулевыми vbmeta и изменённым framework-res.
3. До изменения system сделать согласованную консистентную приватную резервную копию состояния
   и доказать восстановление выбранным инструментом **без format/erase userdata**. Нужны исходные
   system/super metadata, boot chain и согласованные PM/UID/данные. Один backup APK/super не гарантирует
   сохранения данных после перехода data-app → system-app: старый data APK может быть удалён при scan.
   Секреты не выводить/не помещать в Git. Оффлайн-копирование/проверка восстановления пока не выполнены.
4. Зафиксировать единственный способ записи и обратную операцию в проверенном recovery: конкретные
   разделы/диапазоны, размеры и SHA до/после; не менять GPT/bootloader/vbmeta/userdata ради удобства.
   Если инструмент умеет только full format flash, **NO-GO остаётся**. Не использовать неподтверждённый
   fastbootd или обещание «FEL восстановит всё» как готовую инструкцию отката.
5. Только после этих доказательств отдельно согласовать priv-app запись/reboot и ручной trial.
   Сохранить текущий signer/package/UID, подготовить совместимый normal APK версии не ниже candidate;
   затем LOCKED → PIN → finish → NONE → stock HOME, без постоянного autoarm первой проверки.

Новые изменения приложения, APK, ключа, DO или M4 не нужны для этой подготовки. Merge не выполнен.

Локальные очищенные свидетельства (ignored): `artifacts/m3-recovery-preflight/` —
device-preflight-safe.json, vendor-image-index-safe.json, vendor-image-ranges-safe.json,
current-vbmeta-full-safe.json, framework-v1-analysis-safe.json, framework-signing-block-safe.json. Системные APK/фрагменты firmware
в Git не помещаются. Это read-only исследование, не успешная аварийная аппаратная приёмка.
