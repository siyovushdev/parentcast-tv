package dev.siyovush.parentcast.tv.playlist

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.*
import org.junit.Test

class YtDlpPlaylistParserTest {
    private val mapper = ObjectMapper()
    private val parser = YtDlpPlaylistParser()

    private fun video(index: Int, extra: String = ""): String =
        """{"id":"v${index.toString().padStart(10, '0')}","title":"Video $index","duration":10,"playlist_index":$index$extra}"""

    private fun playlist(entries: List<String>, count: Int? = entries.size, availability: String? = "public"): String =
        """{"_type":"playlist","id":"PL1234567890abcdefghij","title":"Test","entries":[${entries.joinToString(",")}],"playlist_count":${count ?: "null"},"availability":${availability?.let { "\"$it\"" } ?: "null"}}"""

    @Test fun returnsAllEntriesAcrossSyntheticPagesInSourceOrder() {
        val firstPage = (1..100).map { video(it) }
        val secondPage = (101..171).map { video(it) }
        val result = parser.parse(playlist(firstPage + secondPage))
        assertEquals(PlaylistStatus.COMPLETE, result.status)
        assertEquals(171, result.reportedCount)
        assertEquals((1..171).toList(), result.entries.map { it.index })
        assertEquals("v0000000101", result.entries[100].videoId)
        assertTrue(result.entries.all { it.selectable })
    }

    @Test fun failedContinuationIsPartialEvenWhenCountMatchesAndExitIsZero() {
        val result = parser.parse(playlist((1..100).map { video(it) }),
            "WARNING: [youtube:tab] PL123 page 2: Unable to download API page: timed out")
        assertEquals(PlaylistStatus.PARTIAL, result.status)
        assertTrue(result.reason!!.contains("page 2"))
        assertEquals(100, result.entries.size)
    }

    @Test fun mismatchCountAndExitErrorAreNeverComplete() {
        assertEquals(PlaylistStatus.PARTIAL, parser.parse(playlist(listOf(video(1)), 3)).status)
        assertEquals(PlaylistStatus.PARTIAL, parser.parse(playlist(listOf(video(1))), exitCode = 1).status)
    }

    @Test fun salvagesPerEntryOutputWhenFinalPlaylistJsonIsLost() {
        val result = parser.parse(video(1) + "\n" + video(2), "ERROR: page 2 failed", 1)
        assertEquals(PlaylistStatus.PARTIAL, result.status)
        assertEquals(listOf("v0000000001", "v0000000002"), result.entries.map { it.videoId })
        assertTrue(result.entries.none { it.selectable })
        assertTrue(result.reason!!.contains("Публичность плейлиста не подтверждена"))
    }

    @Test fun distinguishesUnknownPublicDeletedPrivateLiveAndMissingMetadata() {
        val result = parser.parse(playlist(listOf(
            video(1), video(2, ",\"availability\":\"public\""),
            """{"id":"v0000000003","title":"[Deleted video]"}""",
            video(4, ",\"availability\":\"private\""),
            video(5, ",\"live_status\":\"is_live\""),
            """{"id":"v0000000006","title":"No duration"}""",
            "null",
            video(8, ",\"live_status\":\"is_upcoming\""),
        )))
        assertEquals(listOf(PlaylistAvailability.UNKNOWN, PlaylistAvailability.AVAILABLE,
            PlaylistAvailability.UNAVAILABLE, PlaylistAvailability.UNAVAILABLE, PlaylistAvailability.UNSUPPORTED,
            PlaylistAvailability.UNKNOWN, PlaylistAvailability.UNAVAILABLE, PlaylistAvailability.UNSUPPORTED),
            result.entries.map { it.availability })
        assertEquals(listOf(true, true, false, false, false, false, false, false), result.entries.map { it.selectable })
        assertEquals(8, result.entries.last().index)
    }

    @Test fun fiveHundredAcceptedButSentinelAndReportedOverflowRejectImport() {
        assertEquals(PlaylistStatus.COMPLETE, parser.parse(playlist((1..500).map { video(it) })).status)
        val sentinel = parser.parse(playlist((1..501).map { video(it) }))
        assertEquals(PlaylistStatus.LIMIT_EXCEEDED, sentinel.status)
        assertTrue(sentinel.reason!!.contains("500"))
        assertEquals(PlaylistStatus.LIMIT_EXCEEDED, parser.parse(playlist(listOf(video(1)), 501)).status)
    }

    @Test fun privateUnlistedAndUnknownPlaylistVisibilityAreNotImported() {
        listOf("private", "unlisted", null).forEach {
            val result = parser.parse(playlist(listOf(video(1)), availability = it))
            assertEquals(PlaylistStatus.ERROR, result.status)
            assertTrue(result.entries.isEmpty())
        }
    }

    @Test fun retainsTitlesAsDataWithoutInterpretingHtmlAndRemovesUrlsInDiagnostic() {
        val title = "<img src=x onerror=alert(1)>"
        val item = mapper.readTree(video(1)) as com.fasterxml.jackson.databind.node.ObjectNode
        item.put("title", title)
        val result = parser.parse(playlist(listOf(item.toString())), "WARNING: failed https://example.invalid/?token=secret")
        assertEquals(title, result.entries.single().title)
        assertFalse(result.reason!!.contains("secret"))
        assertFalse(result.reason!!.contains("https://"))
    }

    @Test fun absentOptionalFfmpegDoesNotHideActualPageFailuresOrMakeFlatMetadataPartial() {
        val warning = "WARNING: ffmpeg-location /data/app/test/lib/arm64/libffmpeg.so does not exist! Continuing without ffmpeg"
        val full = playlist(listOf(video(1)))
        assertEquals(PlaylistStatus.COMPLETE, parser.parse(full, warning).status)
        val failedPage = parser.parse(full, warning + "\nWARNING: [youtube:tab] page 2: Unable to download API page")
        assertEquals(PlaylistStatus.PARTIAL, failedPage.status)
        assertTrue(failedPage.reason!!.contains("page 2"))
    }

    @Test fun absentPlaylistAndMetadataIsError() {
        assertEquals(PlaylistStatus.ERROR, parser.parse("", "ERROR: unavailable", 1).status)
    }

    @Test fun malformedEntryContainerIsPartialWhileExplicitEmptyPlaylistIsComplete() {
        assertEquals(PlaylistStatus.PARTIAL, parser.parse(
            """{"_type":"playlist","availability":"public","entries":{},"playlist_count":null}""",
        ).status)
        assertEquals(PlaylistStatus.COMPLETE, parser.parse(playlist(emptyList(), count = 0)).status)
    }
}
