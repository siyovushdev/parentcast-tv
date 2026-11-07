# Скорость подготовки H728 — 2026-11-07

IN SCOPE. Домашняя H728 подтверждена по serial21c7406c7086084201a; адрес получен
из parentcast-tv.local, а не взят из старой переписки. Просмотр STOP, onlinePriority
false, preparation pausedfalse. Настройки сети/VPN/Happ не менялись.

## Измерения до исправления

- Реальная подготовка первой серии VK«Смешарики. ПинКод. … Почему?»:
  94,355,456→95,494,144bytes за11.506s ≈96.6KiB/s (0.79Мбит/с).
  Поздний повтор:131,055,616→132,644,864 за17.761s ≈87.4KiB/s.
- Wi-Fi:2437MHz (2.4GHz), RSSI−46dBm, Rx link172Mbps, Tx97Mbps.
  Это параметры соединения, не скорость Интернета. В snapshot0 lost/retried Tx.
- Собственный ограниченный HTTP LAN RAM тест8MiB, без записи на карту:
  1.188s,56.47Мбит/с на H728. Временный сервер на домашнем ПК закрыт.
- Тот же публичный VK-ролик на H728, pinned yt-dlp metadata5.893s, видео1080p:
  1MiB без Range за0.702s (1458.45KiB/s), с Range за0.138s (7397.9KiB/s).
  Это короткие выборки с возможным cache/startup эффектом, не гарантия скорости
  всего287,216,913-byte трека. Значения подписанных URL не сохранялись/не выводились.
- AAC RAM-проба получила HTTPError; аудиоскорость не измерена, причина UNKNOWN.
- microSD17% занято,48GiB свободно. Thermal status0, CPU57.8°C,
  активный throttling не сообщён. Отдельная Java storage-проба получила
  SecurityException: доступ к измерению не состоялся, скорость ей не доказана.
  Собственный временный dex удалён; пользовательские файлы не затрагивались.

Канал Wi-Fi не объясняет всего разрыва: и LAN, и короткий запрос к текущему
источнику заметно быстрее приложения. В коде обнаружены проверки тома после каждого
короткого network read вместо каждой заполненной128KiB записи.

## Минимальное изменение

Собирать короткие чтения в128KiB блок. Live storage/quota/free reserve gate остаётся
перед каждой физической записью. При сетевой IOException сохранить последний
неполный блок для Range retry; при отмене не писать после отмены. Невыписанный
хвост при отмене ограничен128KiB. Никаких новых загрузчиков/параллелизма/смены
качества, снятия квоты или отключения защиты SD.

PASS:410 unit-тестов,0failures/errors; lint0errors/56warnings; assembleDebug и
compileReleaseKotlin. Команда: JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ANDROID_HOME=/home/siovush/Android/Sdk ./gradlew :app:testDebugUnitTest
:app:lintDebug :app:assembleDebug :app:compileReleaseKotlin --console=plain.
Новые5тестов проверяют packet aggregation, точные bytes, partial после IOException,
отмену и no replay при quota/storage failure; Range-сценарии прошли.
JavaScript не менялся. Синтетические unit-тесты не заменяют VK-проверку на H728.

Обычный debugv27, applicationId dev.siyovush.parentcast.tv.debug, прежняя debug
подпись SHA256de2cf22c47b6e4039da04107631b31d92ecddeb6984eeae5d43107337cceecf8.
APK: artifacts/download-throughput/parentcast-tv-v0.1-download-throughput-debug.apk
SHA256: b0af5a148e81c47633c20c52bec7f836849a5797a6a7e7425213e4c1df3f0a64.
Аппаратная проверка применения/скорости — pending, не PASS.
