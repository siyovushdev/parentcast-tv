package dev.siyovush.parentcast.tv.youtube.ytdlp

import com.fasterxml.jackson.databind.ObjectMapper
import dev.siyovush.parentcast.tv.youtube.YouTubeSourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpResponseParserTest {
    private val mapper = ObjectMapper()
    private val parser = YtDlpResponseParser()

    @Test
    fun parsesSeparateVideoAndAudioAndFiltersSecretHeaders() {
        val root = mapper.readTree(
            """
            {
              "id": "jNQXAC9IVRw",
              "title": "Test video",
              "duration": 19,
              "http_headers": {
                "User-Agent": "allowed-agent",
                "Cookie": "secret-cookie",
                "Authorization": "secret-authorization",
                "Referer": "https://www.youtube.com/"
              },
              "requested_formats": [
                {
                  "url": "https://media.invalid/video.mp4",
                  "protocol": "https",
                  "ext": "mp4",
                  "vcodec": "avc1.640028",
                  "acodec": "none",
                  "height": 1080
                },
                {
                  "url": "https://media.invalid/audio.m4a",
                  "protocol": "https",
                  "ext": "m4a",
                  "vcodec": "none",
                  "acodec": "mp4a.40.2"
                }
              ]
            }
            """,
        )

        val resolved = parser.parse(root, "jNQXAC9IVRw")

        assertEquals("jNQXAC9IVRw", resolved.videoId)
        assertEquals("Test video", resolved.title)
        assertEquals("video/mp4", resolved.video.mimeType)
        assertEquals("audio/mp4", resolved.audio?.mimeType)
        assertEquals("allowed-agent", resolved.video.headers["User-Agent"])
        assertFalse(resolved.video.headers.containsKey("Cookie"))
        assertFalse(resolved.video.headers.containsKey("Authorization"))
        assertTrue(resolved.selectionDescription.contains("1080p"))
        assertTrue(resolved.selectionDescription.contains("раздельные"))
    }

    @Test
    fun parsesProgressiveVideoWithEmbeddedAudio() {
        val root = mapper.readTree(
            """
            {
              "id": "jNQXAC9IVRw",
              "url": "https://media.invalid/progressive.mp4",
              "protocol": "https",
              "ext": "mp4",
              "vcodec": "avc1.64001f",
              "acodec": "mp4a.40.2",
              "height": 720
            }
            """,
        )

        val resolved = parser.parse(root, "jNQXAC9IVRw")

        assertNull(resolved.audio)
        assertTrue(resolved.selectionDescription.contains("720p"))
        assertTrue(resolved.selectionDescription.contains("совмещённые"))
    }

    @Test
    fun rejectsHlsInsteadOfPassingItToProgressivePlayer() {
        val root = mapper.readTree(
            """
            {
              "id": "jNQXAC9IVRw",
              "url": "https://media.invalid/playlist.m3u8",
              "protocol": "m3u8_native",
              "ext": "mp4",
              "vcodec": "avc1.640028",
              "acodec": "mp4a.40.2",
              "height": 1080
            }
            """,
        )

        assertThrows(YouTubeSourceException::class.java) {
            parser.parse(root, "jNQXAC9IVRw")
        }
    }

    @Test
    fun rejectsVideoWithoutAudio() {
        val root = mapper.readTree(
            """
            {
              "id": "jNQXAC9IVRw",
              "url": "https://media.invalid/video.mp4",
              "protocol": "https",
              "ext": "mp4",
              "vcodec": "avc1.640028",
              "acodec": "none",
              "height": 720
            }
            """,
        )

        assertThrows(YouTubeSourceException::class.java) {
            parser.parse(root, "jNQXAC9IVRw")
        }
    }

    @Test
    fun rejectsNonH264Video() {
        val root = mapper.readTree(
            """
            {
              "id": "jNQXAC9IVRw",
              "url": "https://media.invalid/video.webm",
              "protocol": "https",
              "ext": "webm",
              "vcodec": "vp09.00.10.08",
              "acodec": "mp4a.40.2",
              "height": 720
            }
            """,
        )

        assertThrows(YouTubeSourceException::class.java) {
            parser.parse(root, "jNQXAC9IVRw")
        }
    }

    @Test
    fun rejectsResolutionAbove1080p() {
        val root = mapper.readTree(
            """
            {
              "id": "jNQXAC9IVRw",
              "url": "https://media.invalid/video.mp4",
              "protocol": "https",
              "ext": "mp4",
              "vcodec": "avc1.640032",
              "acodec": "mp4a.40.2",
              "height": 1440
            }
            """,
        )

        assertThrows(YouTubeSourceException::class.java) {
            parser.parse(root, "jNQXAC9IVRw")
        }
    }
}
