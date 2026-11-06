package dev.siyovush.parentcast.tv.prepared

import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayInputStream
import java.io.FileOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedStorageTest {
    @get:Rule val temporary=TemporaryFolder()
    private val video="vk:-227392256_456239129"
    private val other="video000002"
    private val cardId="sd:1234-ABCD"
    private class Fixture(val inside:File,val card:File,val id:String) {
        var mounted=true;var writable=true;var total=64L*1024*1024*1024;var free=60L*1024*1024*1024
        var intercept:(()->Unit)?=null
        fun volumes():List<MediaVolume> { intercept?.invoke();return if(mounted)listOf(MediaVolume(id,"microSD",card,true,true,writable,total,free)) else emptyList() }
        fun store()=PreparedStore(inside,::volumes)
    }
    private fun fixture()=Fixture(temporary.newFolder("internal"),temporary.newFolder("card"),cardId)
    private fun ready(store:PreparedStore,id:String=video,text:String="video-bytes"):PreparedEntry {
        val file=store.track(id);file.writeText(text)
        val entry=PreparedEntry(id,PreparationStatus.READY,"Example",10,file.length(),file.length(),tracks=listOf(LocalTrack(store.trackName(id),file.length(),sha256(file))))
        store.write(PreparationState(entries=store.read().entries+(id to entry)))
        return entry
    }
    private fun library(store:PreparedStore,scope:CoroutineScope)=PreparedLibrary(store,object:YouTubeSource {
        override suspend fun resolve(videoId:String)=ResolvedVideo(videoId,"Example",10,StreamSpec("https://example.invalid/not-requested","video/mp4",emptyMap()),null,"synthetic")
    },TrackDownloader { _,target,_,progress -> target.writeText("new");progress(3,3);LocalTrack(target.name,3,sha256(target)) },scope,{_,_,_->})
    private fun command(name:String)=StorageCommand(name,"fingerprint-$name")

    @Test fun legacyIndexAndFilesRemainInternalUntilExplicitSelection() {
        val f=fixture();val store=f.store();val entry=ready(store)
        assertEquals(INTERNAL_STORAGE,store.storage.selectedId());assertTrue(store.verify(entry))
        assertEquals(File(f.inside,"$video.video.mp4"),store.track(video))
        store.storage.select(cardId,0,command("select"));store.claim(other)
        assertEquals(cardId,store.storage.placement(other));assertEquals(INTERNAL_STORAGE,store.storage.placement(video))
        assertEquals(2,store.storage.volumes().size)
        store.storage.select(INTERNAL_STORAGE,store.storage.version(),command("back"))
        assertEquals(1,store.storage.volumes().count { it.id==INTERNAL_STORAGE })
        assertEquals(INTERNAL_STORAGE,f.store().storage.selectedId());assertTrue(f.store().verify(entry))
    }
    @Test fun externalVkNamesAreFatSafeAndLogicalIndexRoundTrips() = runTest {
        val f=fixture();val store=f.store();val library=library(store,this)
        store.storage.select(cardId,0,command("select"));library.configure(true,listOf(video));library.runWorker()
        val entry=library.snapshot().entries.getValue(video)
        assertEquals(PreparationStatus.READY,entry.status)
        assertFalse(store.track(video).name.contains(':'));assertEquals("$video.video.mp4",entry.tracks.single().name)
        assertEquals(entry,store.read().entries[video]);assertTrue(store.verify(entry))
        assertTrue(library.localOrWait(video)!!.video.url.contains(f.card.name))
        assertEquals(setOf(video),store.mediaIds())
    }
    @Test fun claimPinsPartialAndReservedUploadsAcrossSelectionChanges() = runTest {
        val f=fixture();val store=f.store();val library=library(store,this)
        File(store.track(video).path+".part").writeText("partial")
        store.storage.select(cardId,0,command("select"));store.storage.claim(listOf(video),cardId,store.storage.version())
        assertEquals(INTERNAL_STORAGE,store.storage.placement(video))
        val local="local_"+"a".repeat(32);library.reserveImport(local)
        store.storage.select(INTERNAL_STORAGE,store.storage.version(),command("back"))
        library.importLocal(local,"own",3,ByteArrayInputStream(byteArrayOf(1,2,3))) { LocalMediaInfo(1,"video/mp4") }
        assertEquals(cardId,store.storage.placement(local));assertEquals(f.card,store.track(local).parentFile)
    }
    @Test fun pinnedInternalPartialDoesNotDependOnMissingDefaultCard() {
        val f=fixture();val store=f.store();File(store.track(video).path+".part").writeText("retained")
        store.storage.select(cardId,0,command("select"));f.mounted=false
        store.claim(video)
        assertEquals(INTERNAL_STORAGE,store.storage.placement(video));assertEquals("retained",File(store.track(video).path+".part").readText())
        assertThrows(StorageUnavailable::class.java) { store.claim(other) }
    }
    @Test fun enableRejectsStaleStorageAndPinsPendingToChosenVolume() = runTest {
        val f=fixture();val store=f.store();val library=library(store,this)
        store.storage.select(cardId,0,command("select"));val version=store.storage.version()
        library.configure(true,listOf(other),storageId=cardId,storageVersion=version)
        assertEquals(cardId,store.storage.placement(other))
        store.storage.select(INTERNAL_STORAGE,store.storage.version(),command("back"))
        assertThrows(IllegalStateException::class.java) { runBlocking { library.configure(true,listOf(video),storageId=cardId,storageVersion=version) } }
        assertEquals(listOf(other),library.snapshot().plan)
    }
    @Test fun missingCardPreservesReadyCatalogueAndRecoversOnReinsert() = runTest {
        val f=fixture();val store=f.store();store.storage.select(cardId,0,command("select"));store.claim(video)
        val entry=ready(store);val library=library(store,this)
        f.mounted=false
        assertEquals("STORAGE_UNAVAILABLE",library.mediaReadiness(video));assertFalse(library.storageAvailable(video));assertNull(library.thumbnailFile(video))
        assertThrows(YouTubeSourceException::class.java) { runBlocking { library.localOrWait(video,preparedOnly=true) } }
        assertEquals(entry,library.snapshot().entries[video]);assertEquals(cardId,store.storage.placement(video))
        assertEquals(false,store.storage.volume(cardId).mounted)
        f.mounted=true
        assertEquals("READY",library.mediaReadiness(video));assertTrue(library.localOrWait(video,preparedOnly=true)!!.video.url.startsWith("file:"))
        assertEquals(entry,library.snapshot().entries[video])
    }
    @Test fun readOnlyCardAllowsReadyPlaybackButRejectsWrites() = runTest {
        val f=fixture();val store=f.store();store.storage.select(cardId,0,command("select"));store.claim(video);ready(store)
        val library=library(store,this);f.writable=false
        assertTrue(library.localOrWait(video,preparedOnly=true)!!.video.url.startsWith("file:"))
        assertThrows(StorageUnavailable::class.java) { store.claim(other) }
        assertFalse(File(f.inside,"$other.video.mp4").exists())
    }
    @Test fun migrationVerifiesSwitchesAndRetainsOriginalUntilSeparateCleanup() = runTest {
        val f=fixture();val store=f.store();val entry=ready(store);val original=store.track(video);val library=library(store,this)
        library.migrateStorage(cardId,0,command("migrate"));advanceUntilIdle()
        assertEquals("COMPLETE",store.storage.migration().status);assertEquals(cardId,store.storage.placement(video))
        assertTrue(store.verify(entry));assertTrue(original.isFile);assertEquals(1,store.storage.retained().size)
        assertTrue(library.allowsPlaybackFile(video,original));assertTrue(library.allowsPlaybackFile(video,store.track(video)))
        val restored=f.store();assertEquals(cardId,restored.storage.placement(video));assertTrue(restored.verify(entry))
        assertTrue(restored.storage.replay(command("migrate")))
        library.cleanStorageOriginals(store.storage.version(),command("cleanup")) { true };advanceUntilIdle()
        assertFalse(original.exists());assertTrue(store.verify(entry));assertTrue(store.storage.retained().isEmpty())
    }
    @Test fun reverseMigrationNeverDeletesActiveCopy() = runTest {
        val f=fixture();val store=f.store();val entry=ready(store);val library=library(store,this)
        library.migrateStorage(cardId,0,command("out"));advanceUntilIdle()
        library.migrateStorage(INTERNAL_STORAGE,store.storage.version(),command("in"));advanceUntilIdle()
        assertEquals(INTERNAL_STORAGE,store.storage.placement(video));assertTrue(store.verify(entry))
        assertEquals(listOf(cardId),store.storage.retained().map { it.storageId })
        library.cleanStorageOriginals(store.storage.version(),command("cleanup")) { true };advanceUntilIdle()
        assertTrue(store.verify(entry));assertTrue(store.storage.retained().isEmpty())
    }
    @Test fun failedCopyKeepsOriginalAndDoesNotPublishTarget() = runTest {
        val f=fixture();val store=f.store();val entry=ready(store,text="x".repeat(9*1024*1024));val library=library(store,this)
        f.intercept={ if(f.card.listFiles().orEmpty().any { it.name.endsWith(".copying") && it.length()>0 }) f.writable=false }
        library.migrateStorage(cardId,0,command("migrate"));advanceUntilIdle()
        assertEquals("ERROR",store.storage.migration().status);assertEquals("STORAGE_READ_ONLY",store.storage.migration().error)
        assertEquals(INTERNAL_STORAGE,store.storage.placement(video));assertTrue(store.verify(entry));assertTrue(store.storage.retained().isEmpty())
        f.intercept=null;f.writable=true
        library.migrateStorage(cardId,store.storage.version(),command("retry"));advanceUntilIdle()
        assertEquals("COMPLETE",store.storage.migration().status);assertTrue(store.verify(entry))
    }
    @Test fun noSpaceAndDamagedSourceKeepOriginalAndCatalogue() = runTest {
        val f=fixture();val store=f.store();val entry=ready(store);val library=library(store,this)
        f.total=FREE_RESERVE+1
        library.migrateStorage(cardId,0,command("no-space"));advanceUntilIdle()
        assertEquals("NO_SPACE",store.storage.migration().error);assertTrue(store.verify(entry));assertEquals(INTERNAL_STORAGE,store.storage.placement(video))
        f.total=64L*1024*1024*1024;store.track(video).writeText("changed")
        library.migrateStorage(cardId,store.storage.version(),command("changed"));advanceUntilIdle()
        assertEquals("SOURCE_CHANGED",store.storage.migration().error);assertEquals(entry,library.snapshot().entries[video]);assertEquals("changed",store.track(video).readText())
    }
    @Test fun cancellationBeforeWorkerStartIsHonouredAndRecoverable() = runTest {
        val f=fixture();val store=f.store();val entry=ready(store);val library=library(store,this)
        library.migrateStorage(cardId,0,command("migrate"))
        library.cancelStorageMigration(store.storage.version(),command("cancel"));advanceUntilIdle()
        assertEquals("CANCELLED",store.storage.migration().status);assertEquals(INTERNAL_STORAGE,store.storage.placement(video));assertTrue(store.verify(entry))
        library.migrateStorage(cardId,store.storage.version(),command("retry"));advanceUntilIdle()
        assertEquals("COMPLETE",store.storage.migration().status)
    }
    @Test fun cancellationDuringCopyRemovesOnlyNewStagingAndKeepsSource() = runTest {
        val f=fixture();val store=f.store();val entry=ready(store,text="x".repeat(9*1024*1024));val library=library(store,this)
        var cancelled=false
        f.intercept={
            if(!cancelled && f.card.listFiles().orEmpty().any { it.name.endsWith(".copying") && it.length()>0 }) {
                cancelled=true;library.cancelStorageMigration(store.storage.version(),command("cancel"))
            }
        }
        library.migrateStorage(cardId,0,command("migrate"));advanceUntilIdle()
        assertTrue(cancelled);assertEquals("CANCELLED",store.storage.migration().status)
        assertTrue(store.verify(entry));assertTrue(f.card.listFiles().orEmpty().isEmpty());assertTrue(store.storage.stagedCopies().isEmpty())
    }
    @Test fun stagedCopyAfterProcessDeathIsAccountedAndExplicitlyCleanable() = runTest {
        val f=fixture();val store=f.store();val entry=ready(store)
        val copy=StagedMediaCopy(video,cardId,entry.tracks.single())
        store.storage.beginMigration(cardId,listOf(entry),0,command("migrate"));store.storage.stage(copy)
        val destination=store.fileAt(video,copy.track.name,cardId,true);destination.writeText("unfinished copy")
        val restored=f.store();assertEquals(listOf(copy),restored.storage.stagedCopies());assertEquals("INTERRUPTED",restored.storage.migration().status)
        val library=library(restored,this)
        library.cleanStorageOriginals(restored.storage.version(),command("cleanup")) { true };advanceUntilIdle()
        assertFalse(destination.exists());assertTrue(restored.verify(entry));assertTrue(restored.storage.stagedCopies().isEmpty())
    }
    @Test fun interruptedJournalAndReceiptsSurviveProcessRestart() {
        val f=fixture();val store=f.store();val entry=ready(store)
        store.storage.beginMigration(cardId,listOf(entry),0,command("migrate"))
        val restarted=f.store()
        assertEquals("INTERRUPTED",restarted.storage.migration().status);assertTrue(restarted.storage.replay(command("migrate")))
        assertThrows(IllegalStateException::class.java) { restarted.storage.replay(StorageCommand("migrate","different")) }
        restarted.storage.beginCleanup(restarted.storage.version(),command("cleanup"))
        assertEquals("INTERRUPTED",f.store().storage.migration().status)
        assertTrue(store.verify(entry))
    }
    @Test fun cleanupWillNotDeleteWhenDestinationMissingCorruptOrPlaybackChanged() = runTest {
        val f=fixture();val store=f.store();val entry=ready(store);val original=store.track(video);val library=library(store,this)
        library.migrateStorage(cardId,0,command("migrate"));advanceUntilIdle()
        f.mounted=false
        library.cleanStorageOriginals(store.storage.version(),command("missing")) { true };advanceUntilIdle()
        assertTrue(original.isFile);assertEquals("ERROR",store.storage.migration().status)
        f.mounted=true;store.track(video).writeText("bad")
        library.cleanStorageOriginals(store.storage.version(),command("bad")) { true };advanceUntilIdle()
        assertTrue(original.isFile);assertEquals("COPY_VERIFY_FAILED",store.storage.migration().error)
        assertThrows(IllegalStateException::class.java) { runBlocking { library.cleanStorageOriginals(store.storage.version(),command("playing")) { false } } }
        assertTrue(original.isFile);assertEquals(entry,library.snapshot().entries[video])
    }
    @Test fun explicitDeleteRemovesRetainedCopiesAndCatalogueDoesNotLeakSpace() = runTest {
        val f=fixture();val store=f.store();ready(store);val original=store.track(video);val library=library(store,this)
        library.migrateStorage(cardId,0,command("migrate"));advanceUntilIdle()
        val destination=store.track(video);library.deleteMedia(video);advanceUntilIdle()
        assertFalse(original.exists());assertFalse(destination.exists());assertTrue(store.storage.retained().isEmpty())
        assertEquals(PreparationStatus.DELETED,library.snapshot().entries[video]?.status)
    }
    @Test fun failedRedownloadNeverDeletesRetainedOriginal() = runTest {
        val f=fixture();val store=f.store();ready(store);val original=store.track(video);val firstLibrary=library(store,this)
        firstLibrary.migrateStorage(cardId,0,command("migrate"));advanceUntilIdle()
        store.track(video).writeText("damaged")
        val source=object:YouTubeSource { override suspend fun resolve(videoId:String):ResolvedVideo=error("UNSUPPORTED") }
        val library=PreparedLibrary(store,source,TrackDownloader { _,_,_,_->error("Must not download") },this,{_,_,_->})
        assertThrows(YouTubeSourceException::class.java) { runBlocking { library.localOrWait(video,preparedOnly=true) } }
        library.configure(true,listOf(video));library.runWorker()
        assertEquals("UNSUPPORTED",library.snapshot().entries[video]?.error)
        assertEquals("video-bytes",original.readText());assertEquals(1,store.storage.retained().size)
    }
    @Test fun retryCannotRedirectOntoRetainedOriginalAndOverwriteIt() = runTest {
        val f=fixture();val store=f.store();ready(store);val original=store.track(video);val library=library(store,this)
        library.migrateStorage(cardId,0,command("migrate"));advanceUntilIdle()
        store.track(video).delete()
        assertThrows(YouTubeSourceException::class.java) { runBlocking { library.localOrWait(video,preparedOnly=true) } }
        store.storage.select(INTERNAL_STORAGE,store.storage.version(),command("internal"))
        assertThrows(StorageUnavailable::class.java) { runBlocking { library.configure(true,listOf(video),storageId=INTERNAL_STORAGE,storageVersion=store.storage.version()) } }
        assertEquals(cardId,store.storage.placement(video));assertEquals("video-bytes",original.readText());assertEquals(1,store.storage.retained().size)
    }
    @Test fun admissionWriteFailureProducesTerminalErrorAndNoPermanentBusy() = runTest {
        val f=fixture();val store=f.store();val entry=ready(store);val library=library(store,this)
        File(f.inside,"index.tmp").mkdir()
        assertThrows(Exception::class.java) { runBlocking { library.migrateStorage(cardId,0,command("broken")) } }
        assertEquals("ERROR",store.storage.migration().status);assertEquals(INTERNAL_STORAGE,store.storage.placement(video));assertTrue(store.verify(entry))
        File(f.inside,"index.tmp").delete()
        library.migrateStorage(cardId,store.storage.version(),command("retry"));advanceUntilIdle()
        assertEquals("COMPLETE",store.storage.migration().status)
    }
    @Test fun budgetChargesPinnedPhysicalDestinationAfterSelectionChanges() {
        val f=fixture();val store=f.store();store.storage.select(cardId,0,command("select"));store.claim(video)
        val target=store.track(video);f.total=FREE_RESERVE+4
        store.storage.select(INTERNAL_STORAGE,store.storage.version(),command("back"))
        val budget=MediaBudget(f.inside,storage=store.storage)
        FileOutputStream(target).use { out ->
            budget.write(out,byteArrayOf(1,2,3),3,target)
            assertThrows(IllegalStateException::class.java) { budget.write(out,byteArrayOf(4,5),2,target) }
        }
        assertEquals(3L,target.length());assertEquals(0L,store.storage.usedBytes(INTERNAL_STORAGE))
    }
    @Test fun eachMediaOrCacheWriteUsesOneFreshExternalVolumeSnapshot() {
        val f=fixture();val store=f.store();store.storage.select(cardId,0);store.claim(video)
        val target=store.track(video);val budget=MediaBudget(f.inside,storage=store.storage)
        var observations=0;f.intercept={observations++}
        FileOutputStream(target).use { out->
            budget.write(out,byteArrayOf(1,2),2,target);assertEquals(1,observations)
            budget.writeCache(cardId,2){out.write(byteArrayOf(3,4))};assertEquals(2,observations)
        }
        assertEquals(4L,target.length())
    }
    @Test fun nextWriteImmediatelyHonoursReadonlyRemovalAndCapacityChanges() {
        val f=fixture();val store=f.store();store.storage.select(cardId,0);store.claim(video)
        val target=store.track(video);val budget=MediaBudget(f.inside,storage=store.storage)
        FileOutputStream(target).use { out->
            budget.write(out,byteArrayOf(1,2),2,target)
            f.writable=false
            assertEquals("STORAGE_READ_ONLY",assertThrows(StorageUnavailable::class.java){budget.write(out,byteArrayOf(3),1,target)}.code)
            assertEquals("STORAGE_READ_ONLY",assertThrows(StorageUnavailable::class.java){budget.writeCache(cardId,1){out.write(3)}}.code)
            f.writable=true;f.mounted=false
            assertEquals("STORAGE_UNAVAILABLE",assertThrows(StorageUnavailable::class.java){budget.write(out,byteArrayOf(3),1,target)}.code)
            assertEquals("STORAGE_UNAVAILABLE",assertThrows(StorageUnavailable::class.java){budget.writeCache(cardId,1){out.write(3)}}.code)
            f.mounted=true;f.total=FREE_RESERVE+2
            assertEquals("NO_SPACE",assertThrows(IllegalStateException::class.java){budget.write(out,byteArrayOf(3),1,target)}.message)
            assertEquals("NO_SPACE",assertThrows(IllegalStateException::class.java){budget.writeCache(cardId,1){out.write(3)}}.message)
            assertEquals(2L,target.length())
        }
    }
    @Test fun untrustedVolumePathsAndSymlinksCannotEscapeAppStorage() {
        val f=fixture();val store=f.store()
        assertThrows(IllegalArgumentException::class.java) { store.storage.select("../../elsewhere",0) }
        assertThrows(IllegalArgumentException::class.java) { store.track("../other") }
        val outside=temporary.newFile("outside");outside.writeText("keep")
        val logical=File(f.inside,"$other.video.mp4")
        java.nio.file.Files.createSymbolicLink(logical.toPath(),outside.toPath())
        assertThrows(IllegalStateException::class.java) { store.track(other) }
        assertEquals("keep",outside.readText())
        assertThrows(IllegalStateException::class.java) { store.storage.select(cardId,999) }
        assertEquals(INTERNAL_STORAGE,store.storage.selectedId())
    }
    @Test fun cacheBytesCountNestedSpansAndIgnoreSymlinkTargetsOutsideCache() {
        val f=fixture();val store=f.store()
        val cache=File(f.card,"stream-cache").apply {mkdir()}
        val spans=File(cache,"0").apply {mkdir()}
        File(spans,"span.v3.exo").writeBytes(ByteArray(4096))
        File(cache,"index-data").writeBytes(ByteArray(17))
        File(f.card,"local.video.mp4").writeBytes(ByteArray(100))
        val outside=temporary.newFile("outside-cache").apply {writeBytes(ByteArray(9000))}
        val link=File(spans,"external.v3.exo").toPath()
        java.nio.file.Files.createSymbolicLink(link,outside.toPath())
        try {
            assertEquals(4213L,PreparedStorage.mediaBytes(f.card))
            assertEquals(4213L,store.storage.usedBytes(cardId))
            assertEquals(9000L,outside.length())
        }finally {java.nio.file.Files.delete(link)}
        val otherRoot=temporary.newFolder("cache-link-root")
        val directoryLink=File(otherRoot,"stream-cache").toPath()
        java.nio.file.Files.createSymbolicLink(directoryLink,cache.toPath())
        try {assertEquals(0L,PreparedStorage.mediaBytes(otherRoot))}
        finally {java.nio.file.Files.delete(directoryLink)}
    }
    @Test fun corruptRoutingJournalFailsClosedWithoutDeletingExistingMedia() {
        val f=fixture();val store=f.store();val entry=ready(store)
        File(f.inside,"storage.json").writeText("broken")
        val recovered=f.store();assertTrue(recovered.read().storageError)
        assertThrows(StorageUnavailable::class.java) { recovered.track(video) }
        assertTrue(File(f.inside,entry.tracks.single().name).isFile)
        assertEquals("broken",File(f.inside,"storage.json").readText())
    }
}
