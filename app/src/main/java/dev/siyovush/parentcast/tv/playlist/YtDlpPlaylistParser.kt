package dev.siyovush.parentcast.tv.playlist

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** Flat extraction deliberately does not fetch formats or assert that a video will play. */
class YtDlpPlaylistParser {
    private val mapper = ObjectMapper()

    fun parse(output: String, diagnostics: String = "", exitCode: Int = 0): PlaylistResult {
        // --dump-json retains per-entry output on extractor errors in youtubedl-android 0.18.1.
        // --dump-single-json adds the playlist metadata as the final JSON line.
        val objects = output.lineSequence().filter { it.startsWith('{') }.mapNotNull {
            try { mapper.readTree(it) } catch (_: Exception) { null }
        }.toList()
        val root = objects.lastOrNull { it.path("_type").asText() == "playlist" }
        val flatEntries = root?.path("entries")?.takeIf { it.isArray }?.toList()
            ?: objects.filter { it.path("id").asText().matches(VIDEO_ID) }.take(501)
        val entries = flatEntries.take(501).mapIndexed { index, item -> entry(item, index + 1) }
        val count = root?.path("playlist_count")?.takeIf { it.isIntegralNumber && it.asLong() in 0..Int.MAX_VALUE }?.asInt()
        val title = root?.text("title")?.take(500).orEmpty()
        val diagnosticReason = diagnosticReason(diagnostics)
        if (root != null && root.text("availability") != "public") {
            return PlaylistResult(title, emptyList(), PlaylistStatus.ERROR,
                "Поддерживаются только подтверждённо публичные плейлисты; доступность плейлиста: ${root.text("availability")?.takeIf { it in setOf("private", "unlisted", "premium_only", "subscriber_only", "needs_auth") } ?: "неизвестна"}.", count)
        }
        val reason: String?
        val status = when {
            entries.size > 500 || (count ?: 0) > 500 -> {
                reason = "В плейлисте более 500 элементов. Лимит одного импорта — 500; импорт этого предпросмотра запрещён."
                PlaylistStatus.LIMIT_EXCEEDED
            }
            root == null && entries.isEmpty() -> {
                reason = diagnosticReason ?: "Не удалось получить публичный плейлист."
                PlaylistStatus.ERROR
            }
            root == null || !root.path("entries").isArray -> {
                reason = diagnosticReason ?: "Список получен частично: итоговые метаданные плейлиста не получены или некорректны."
                PlaylistStatus.PARTIAL
            }
            exitCode != 0 || diagnosticReason != null || (count != null && count != entries.size) -> {
                reason = diagnosticReason ?: if (count != null && count != entries.size) {
                    "YouTube сообщил $count элементов, получено ${entries.size}. Часть элементов или страниц недоступна."
                } else "yt-dlp завершил получение списка с ошибкой (код $exitCode)."
                PlaylistStatus.PARTIAL
            }
            else -> {
                reason = null
                PlaylistStatus.COMPLETE
            }
        }
        // Per-entry salvage cannot prove playlist visibility. Keep metadata for inspection,
        // but require a successful public root before any item may be applied or saved.
        val safeEntries = if (root == null) entries.map { it.copy(selectable = false) } else entries
        val safeReason = if (root == null && entries.isNotEmpty()) {
            "$reason Публичность плейлиста не подтверждена; добавление и сохранение недоступны."
        } else reason
        return PlaylistResult(title, safeEntries, status, safeReason, count)
    }

    private fun entry(item: JsonNode, fallbackIndex: Int): PlaylistEntry {
        val id = item.text("id")?.takeIf { VIDEO_ID.matches(it) }
        val title = item.text("title").orEmpty().take(500)
        val duration = item.path("duration").takeIf { it.isNumber && it.asDouble().isFinite() && it.asDouble() > 0 && it.asDouble() < 315_360_000 }?.asDouble()?.toLong()
        val suppliedAvailability = item.text("availability")
        val unavailable = id == null || suppliedAvailability in setOf("private", "premium_only", "subscriber_only", "needs_auth", "unavailable") ||
            title.lowercase() in setOf("[deleted video]", "[private video]", "deleted video", "private video", "[unavailable video]")
        val unsupported = suppliedAvailability == "unlisted" || item.path("is_live").asBoolean(false) || item.text("live_status") in setOf("is_live", "is_upcoming", "post_live") || item.path("release_timestamp").takeIf { it.isNumber }?.asLong()?.let { it > System.currentTimeMillis() / 1000 } == true
        val availability = when {
            unavailable -> PlaylistAvailability.UNAVAILABLE
            unsupported -> PlaylistAvailability.UNSUPPORTED
            suppliedAvailability == "public" -> PlaylistAvailability.AVAILABLE
            else -> PlaylistAvailability.UNKNOWN
        }
        // A listed completed VOD may be selected with an explicit UNKNOWN badge, never labelled
        // confirmed available. Playback re-resolves the ID and may still fail (region/deletion/etc.).
        val selectable = availability !in setOf(PlaylistAvailability.UNAVAILABLE, PlaylistAvailability.UNSUPPORTED) &&
            id != null && title.isNotBlank() && duration != null
        return PlaylistEntry(
            index = item.path("playlist_index").takeIf { it.isIntegralNumber && it.asInt() > 0 }?.asInt() ?: fallbackIndex,
            videoId = id,
            title = title.ifBlank { "Недоступный или неизвестный элемент" },
            availability = availability,
            selectable = selectable,
            durationSeconds = duration,
            channel = (item.text("channel") ?: item.text("uploader"))?.take(200),
        )
    }

    private fun diagnosticReason(diagnostics: String): String? {
        // Treat all warnings conservatively: flat metadata must not hide a failed continuation,
        // incomplete response, skipped renderer or YouTube's unavailable-item warning.
        val lines = diagnostics.lineSequence().filter { "WARNING:" in it || "ERROR:" in it || "Traceback" in it }
            // Flat metadata does not use ffmpeg. The existing Android wrapper supplies an optional
            // native path even when that component is absent; this warning says nothing about pages.
            .filterNot { line ->
                line.trim().startsWith("WARNING: ffmpeg-location ") &&
                    line.trim().endsWith("/libffmpeg.so does not exist! Continuing without ffmpeg")
            }.toList()
        if (lines.isEmpty()) return null
        return lines.joinToString(" ").replace(Regex("https?://\\S+"), "<URL>")
            .replace(Regex("(?i)(cookie|authorization|token|signature|sig|visitorData|poToken)=[^\\s&]+"), "$1=<redacted>")
            .replace(Regex("\\s+"), " ").take(600)
    }

    private fun JsonNode.text(key: String): String? = path(key).takeIf { it.isTextual }?.asText()

    companion object {
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
