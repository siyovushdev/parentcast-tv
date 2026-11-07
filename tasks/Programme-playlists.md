# Плейлисты в программе — 2026-11-04

IN SCOPE: владелец попросил сохранять импортированный плейлист отдельным плейлистом, выбирать
просмотр по нему и сделать понятным порядок разных источников. Связанные замечания: готовность
вместо «Смотреть» у незагруженных, нормальные названия вместо первичного videoId.
Ветка codex/programme-playlists отe316bb4 (PR #13), отдельный Draft PR, без Merge/Release.

План: расширить существующий atomic queue snapshot (v3 читаетv1/v2), а не менять службы,
Media3 или yt-dlp. Группы — фиксированные ordered references на уникальные videoId;
название/источник/порядок/active scope сохраняются. Общий файл один; новые видео не появляются
автоматически. Выбранный плейлист заканчивается STOP; общая очередь/старые данные читаются.
Изменение порядка/импорт не вызывают PLAY. Старое без надёжной информации об источнике не
выдавать за известный YouTube-плейлист; использовать сохранённую родительскую подборку.

UI: карточки и отдельные видео по источнику; подготовка выбранного списка без остановки
плеера, явный запуск готового первого видео, editable порядок плейлистов/всех его роликов.
Titles из preview и saved selection сохранять; IDs без названия вторичны. Runtime readiness
проверяется также API. Прежние PIN/токены/Origin/Host/CSRF, version/receipts и media budget.
При новом приоритете подготовки остановить позднюю передачу с сохранением partial.

Проверки: codec/migration, shared IDs, grouping/replay/concurrency, title safety, group end,
порядок без прерывания, readiness gate и targeted preparation/preemption; JS contracts,
unit/debug/release/lint; H728 update без очистки, реальные UI/import/save/start/end и данные.
DONE. Без reboot/Happ/системы/privileged/M4; cookies/PIN не трогать.

Результат:259 JVM tests/5 JS contracts/debug/release/lint PASS (0errors/50warnings).
H728:38 исходных роликов в одной именованной группе из saved collection; реальный VK61,
shared references, подготовка35,309,901bytes,1080p playback и natural end→STOP; UI позиции1/2/3,
порядок/отмена/ready gate. Свои тестовые группы/файлы удалены, исходные данные сохранены,
PAUSE и подготовка восстановлены. Отчёт/границы/артефакт: [Programme-playlists](../docs/reports/Programme-playlists.md).
