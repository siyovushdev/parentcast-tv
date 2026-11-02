package dev.siyovush.parentcast.tv.playlist

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistPreviewManagerTest {
    private val url = "https://www.youtube.com/playlist?list=PL1234567890abcdefghij"
    private val complete = PlaylistResult("Test", emptyList(), PlaylistStatus.COMPLETE, null, 0)

    @Test fun startReturnsLoadingWithoutWaitingAndIsScopedToOwner() = runTest {
        val response = CompletableDeferred<PlaylistResult>()
        val manager = PlaylistPreviewManager(PlaylistSource { response.await() }, backgroundScope)
        val preview = manager.start("a", url)
        assertEquals(PreviewState.LOADING, preview.state)
        assertNull(manager.get("b", preview.id))
        assertNull(manager.cancel("b", preview.id))
        runCurrent()
        response.complete(complete)
        runCurrent()
        assertEquals(complete, manager.get("a", preview.id)?.result)
        manager.close()
    }

    @Test fun cancelMarksPreviewImmediatelyAndNeverPublishesLateResult() = runTest {
        var cancelled = false
        val manager = PlaylistPreviewManager(PlaylistSource {
            try { awaitCancellation() } finally { cancelled = true }
        }, backgroundScope)
        val preview = manager.start("a", url)
        runCurrent()
        assertEquals(PreviewState.CANCELLED, manager.cancel("a", preview.id)?.state)
        runCurrent()
        assertTrue(cancelled)
        assertNull(manager.get("a", preview.id)?.result)
        assertEquals(PreviewState.CANCELLED, manager.get("a", preview.id)?.state)
        manager.close()
    }

    @Test fun secondPreviewCancelsOnlySameOwnersActiveRequest() = runTest {
        val manager = PlaylistPreviewManager(PlaylistSource { awaitCancellation() }, backgroundScope)
        val first = manager.start("a", url)
        val other = manager.start("b", url)
        val second = manager.start("a", url)
        assertEquals(PreviewState.CANCELLED, manager.get("a", first.id)?.state)
        assertEquals(PreviewState.LOADING, manager.get("b", other.id)?.state)
        assertEquals(PreviewState.LOADING, manager.get("a", second.id)?.state)
        manager.close()
    }

    @Test fun timeoutIsErrorAndExpiredPreviewCannotBeApplied() = runTest {
        var clock = 0L
        val manager = PlaylistPreviewManager(PlaylistSource { awaitCancellation() }, backgroundScope,
            now = { clock }, timeoutMs = 1000, ttlMs = 10_000)
        val preview = manager.start("a", url)
        runCurrent()
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(PlaylistStatus.ERROR, manager.get("a", preview.id)?.result?.status)
        clock = 10_001
        assertNull(manager.get("a", preview.id))
        manager.close()
    }

    @Test fun activePreviewsAreBoundedAndCloseCancelsThem() = runTest {
        val manager = PlaylistPreviewManager(PlaylistSource { awaitCancellation() }, backgroundScope)
        val previews = (1..4).map { manager.start("owner$it", url) }
        assertThrows(IllegalStateException::class.java) { manager.start("fifth", url) }
        manager.close()
        assertNull(manager.get("owner1", previews.first().id))
        assertThrows(IllegalStateException::class.java) { manager.start("a", url) }
    }
}
