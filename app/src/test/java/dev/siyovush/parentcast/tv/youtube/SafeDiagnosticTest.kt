package dev.siyovush.parentcast.tv.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeDiagnosticTest {
    @Test
    fun redactsSignedUrlsAndSensitiveParameters() {
        val error = IllegalStateException(
            "Failed https://googlevideo.example/videoplayback?expire=123&sig=secret token=private",
        )

        val diagnostic = error.safeDiagnostic()

        assertFalse(diagnostic.contains("googlevideo"))
        assertFalse(diagnostic.contains("secret"))
        assertFalse(diagnostic.contains("private"))
        assertTrue(diagnostic.contains("<URL>"))
        assertTrue(diagnostic.contains("token=<redacted>"))
    }

    @Test
    fun includesUsefulExceptionChainWithoutUrls() {
        val error = ExtractionFailureForTest()

        val diagnostic = error.safeDiagnostic()

        assertTrue(diagnostic.contains(ExtractionFailureForTest::class.java.name))
        assertTrue(diagnostic.contains("Video unavailable"))
        assertTrue(diagnostic.contains(IllegalArgumentException::class.java.name))
    }
}

private class ExtractionFailureForTest : Exception(
    "Video unavailable",
    IllegalArgumentException("Unsupported stream"),
)
