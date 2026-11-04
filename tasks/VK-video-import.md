# VK: видео и плейлисты — 2026-11-04

IN SCOPE: владелец разрешил прямые VK-ссылки и плейлисты, если установленный yt-dlp их
поддерживает. Ветка codex/vk-video-import отfea823e, stacked к Draft PR #11, без Merge.

- Доступные без входа завершённые VK-видео: canonical provider ID, yt-dlp без cookies,
  только H.264/AAC progressive HTTPS до1080p, подготовка/проверка/private storage до просмотра.
- Плейлисты /playlist/<owner>_<id>: все доступные страницы, до500, явный предпросмотр/выбор,
  UNKNOWN не превращать в AVAILABLE, partial/ошибки/лимит показывать честно. Видео с ?pl= —
  отдельный ролик в «Добавить видео», плейлист в разделе импорта. /season_0 — общий список;
  остальные сезоны/каналы/ленты/закрытые источники не поддерживаются.
- Использовать существующие API/PIN/CSRF/Host/Origin, queueVersion/receipt/dedup, подборки,
  private media budget10GiB/reserve1GiB, retry/Range/cancellation. Без произвольных URL,
  переноса YouTube cookies в VK или загрузки непроверенного файла как READY.
- Новые ID не конфликтуют с YouTube/local; существующие данные читаются без сброса.
- Проверки: URI/security/parser/live/private/DRM/пагинация/500/501/partial/schema,
  cookie isolation, смешанные очереди/replay/сохранение, offline-only, JS; build/unit/lint/release.
  На подтверждённой H728 — предоставленный плейлист61, выбор/применение/сохранение, полная
  подготовка и playback короткого первого видео116s. Синтетические проверки отмечать отдельно.

Реализация DONE. Подтверждено metadata-only на H728:61 ID; дополнительный opt-in
metadata adapter получает title/duration/provider count из тех же ответов VK через pinned
extractor. Сам yt-dlp, Media3 и YouTube-интеграция не обновляются. Существующие38 строк,
PIN/cookies/tokens/готовые файлы и текущий intent сохранять. Без reboot/Happ/DO/privileged/M4.

246 JVM /5 Python tests и4 JS contracts PASS; debug/release Kotlin/lint PASS (0errors/51warnings).
Обычный v9 установлен без очистки. H728: реальный61/61 preview, Chrome PIN/выбор/apply/save,
added1 и последующие skipped1, READY35,309,901bytes и1080p H.264/AAC local playback до18,847ms.
Тестовые VK-строка/подборка/файл удалены, пользовательские38/подборка/ready/PIN/cookies/settings
сохранены. Прежний ролик3/38 PAUSE около15s; подготовка36 продолжена. Ubuntu browser automation
NOT_RUN (CUA timeoutMs), настоящий браузерный сценарий выполнен в Android Chrome.
Подробности/артефакт/ограничения: [отчёт](../docs/reports/VK-video-import.md). Без Merge.
