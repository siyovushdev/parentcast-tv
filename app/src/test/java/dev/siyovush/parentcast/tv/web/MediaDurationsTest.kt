package dev.siyovush.parentcast.tv.web

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MediaDurationsTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun deduplicatesBoundsWorkAndPersistsOnlyMetadata()=runTest {
        var calls=0;var active=0;var peak=0;val file=File(temp.root,"durations.json")
        val cache=MediaDurations(file,this,{calls++;active++;peak=maxOf(peak,active);delay(50);active--;123},dispatcher=StandardTestDispatcher(testScheduler))
        assertNull(cache.getOrRequest("video000001"));assertNull(cache.getOrRequest("video000001"))
        assertNull(cache.getOrRequest("../private"));repeat(15){cache.getOrRequest("video%06d".format(it))}
        advanceUntilIdle();assertEquals(8,calls);assertEquals(1,peak);assertEquals(123L,cache.known("video000001"));cache.close()
        val restored=MediaDurations(file,this,{error("unexpected refetch")},dispatcher=StandardTestDispatcher(testScheduler));assertEquals(123L,restored.getOrRequest("video000001"));restored.close()
    }
    @Test fun previewMetadataIsSavedWithoutNetworkAndUnknownNeverBecomesZero()=runTest {
        var calls=0;var now=0L
        val cache=MediaDurations(File(temp.root,"durations.json"),this,{calls++;null},{now},StandardTestDispatcher(testScheduler))
        cache.rememberAll(mapOf("video000001" to 3601,"video000002" to 0,"video000003" to -1))
        assertEquals(3601L,cache.getOrRequest("video000001"));assertNull(cache.known("video000002"));assertEquals(0,calls)
        cache.getOrRequest("video000002");advanceUntilIdle();cache.getOrRequest("video000002");advanceUntilIdle();assertEquals(1,calls)
        now=300_001;cache.getOrRequest("video000002");advanceUntilIdle();assertEquals(2,calls);cache.close()
    }
}
