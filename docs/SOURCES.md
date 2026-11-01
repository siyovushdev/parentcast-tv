# Первичные источники

Проверены при подготовке 31 октября 2026 года. Это сведения об инструментах,
а не свидетельства работоспособности нашего приложения.

## S1 — NewPipe Extractor

https://github.com/TeamNewPipe/NewPipeExtractor

README: независимое использование библиотеки; поддержка YouTube;
GPL версии 3 или более поздней; требования desugaring при minSdk ниже 33;
правила подключения и сведения о временных snapshot-артефактах.
Агент обязан проверить фактически выбранный tag/commit и зафиксировать зависимости.
Не копировать плавающий пример версии в production-сборку.

## S2 — Media3: начало работы

https://developer.android.com/media/media3/exoplayer/hello-world

Нативное создание ExoPlayer, MediaItem, подключение к View и освобождение ресурсов.
Согласовывать версии модулей Media3, а не смешивать их.

## S3 — Media3: форматы

https://developer.android.com/media/media3/exoplayer/supported-formats

Поддержка потоков/контейнеров и зависимость декодирования от возможностей устройства.
Таблица поддержки не заменяет тест конкретного H728.

## S4 — Android: Lock Task

https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode

Отличие Lock Task от screen pinning, разрешение DPC и ограничения системного интерфейса.
HOME сам по себе не доказывает запрет выхода.

## S5 — YouTube Premium и авторизация

https://support.google.com/youtube/answer/7437519?hl=en
https://support.google.com/youtube/answer/6308244?hl=en
https://newpipe.net/FAQ/bugs/

Преимущества Premium связаны со входом в соответствующий аккаунт в поддерживаемом клиенте.
NewPipe не реализует обычный Google-логин. В нашем M0 Google-авторизации нет;
следовательно, платная подписка владельца не является техническим механизмом отсутствия рекламы.
Это не меняет согласованное решение использовать собственный плеер и неофициальный extractor.

## S6 — GitHub CLI

https://cli.github.com/manual/gh_repo_create

CLI поддерживает создание приватного репозитория из локального проекта.
Перед созданием проверять аккаунт, существование remote и права, не делать слепой повтор.

## S7 — NanoHTTPD

https://github.com/NanoHttpd/nanohttpd
https://github.com/NanoHttpd/nanohttpd/blob/master/LICENSE.md

В M1 используется закреплённая зависимость `org.nanohttpd:nanohttpd:2.3.1` под BSD-3-Clause.
Для JSON POST вызывается `parseBody`, сырой текст доступен как `postData`. Код 421 отсутствует
во встроенном `Response.Status` версии 2.3.1 и реализован через `IStatus` в приложении.

## Что не утверждается

Точная работоспособная версия extractor на H728, фактические декодеры, возможность
Device Owner на данной прошивке, качество домашнего Wi-Fi и внешний доступ SSH пока не проверены.
Нет гарантии бесконечной совместимости YouTube. Лицензии и условия сторонних компонентов
нельзя игнорировать при дальнейшем распространении; публикация APK не входит в текущее задание.
