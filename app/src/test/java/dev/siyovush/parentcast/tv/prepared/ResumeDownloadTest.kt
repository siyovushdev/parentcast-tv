package dev.siyovush.parentcast.tv.prepared

import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ResumeDownloadTest {
    @get:Rule val temp=TemporaryFolder()
    private val data=ByteArray(131072) { (it%251).toByte() }
    private fun full(bytes: ByteArray=data,tag: String="\"one\"")=MockResponse().setHeader("ETag",tag).setBody(Buffer().write(bytes))
    private fun spec(server: MockWebServer)=StreamSpec(server.url("/signed?temporary=one").toString(),"video/mp4",emptyMap(),data.size.toLong(),"137|avc1|none")
    private fun downloader()=HttpTrackDownloader(DisabledYouTubeSession,allowed={it.host=="localhost"})
    private fun interrupted(server: MockWebServer,target: File): Long {
        server.enqueue(full().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertThrows(IOException::class.java) { runBlocking { downloader().download(spec(server),target,1_000_000) { _,_-> } } }
        return File(target.path+".part").length().also { assertTrue(it in 1 until data.size.toLong()) }
    }
    @Test fun resumesExactRangeAfterNetworkDropAndRefreshOfSignedUrl() = runBlocking {
        MockWebServer().use { server ->
            val target=File(temp.newFolder(),"video000001.video.mp4");val offset=interrupted(server,target)
            server.enqueue(full(data.copyOfRange(offset.toInt(),data.size)).setResponseCode(206).setHeader("Content-Range","bytes $offset-${data.lastIndex}/${data.size}"))
            val seen=mutableListOf<Long>()
            downloader().download(spec(server).copy(url=server.url("/new-signed?temporary=two").toString()),target,1_000_000) { n,_->seen+=n }
            assertArrayEquals(data,target.readBytes());assertEquals(offset,seen.first())
            server.takeRequest();val second=server.takeRequest()
            assertEquals("bytes=$offset-",second.getHeader("Range"));assertEquals("\"one\"",second.getHeader("If-Range"))
            val metadata=File(target.path+".resume.json").readText();assertFalse(metadata.contains("http"));assertFalse(metadata.contains("temporary"))
        }
    }
    @Test fun ignoredRangeRestartsInsteadOfAppendingDifferentRepresentation() = runBlocking {
        MockWebServer().use { server ->
            val target=File(temp.newFolder(),"video000001.video.mp4");interrupted(server,target)
            val replacement=ByteArray(data.size) { 99 };server.enqueue(full(replacement,"\"two\""))
            downloader().download(spec(server),target,1_000_000) { _,_-> }
            assertArrayEquals(replacement,target.readBytes())
        }
    }
    @Test fun invalidContentRangeOrChangedValidatorIsNeverAppended() = runBlocking {
        for(wrongTag in listOf(false,true)) MockWebServer().use { server ->
            val target=File(temp.newFolder(),"video000001.video.mp4");val offset=interrupted(server,target)
            server.enqueue(full(data.copyOfRange(offset.toInt(),data.size),if(wrongTag) "\"changed\"" else "\"one\"")
                .setResponseCode(206).setHeader("Content-Range","bytes ${if(wrongTag)offset else offset+1}-${data.lastIndex}/${data.size}"))
            server.enqueue(full());downloader().download(spec(server),target,1_000_000) { _,_-> }
            assertArrayEquals(data,target.readBytes());server.takeRequest();server.takeRequest();assertNull(server.takeRequest().getHeader("Range"))
        }
    }
    @Test fun expiredUrlRetainsPartialForFreshResolve() = runBlocking {
        MockWebServer().use { server ->
            val target=File(temp.newFolder(),"video000001.video.mp4");val offset=interrupted(server,target)
            server.enqueue(MockResponse().setResponseCode(403))
            assertThrows(RetryableTransfer::class.java) { runBlocking { downloader().download(spec(server),target,1_000_000) { _,_-> } } }
            assertEquals(offset,File(target.path+".part").length());assertFalse(target.exists())
        }
    }
    @Test fun weakValidatorsAndUnknownLengthsDoNotBecomeUnsafeResume() {
        assertNull(strongResumeValidator("W/\"x\"",null,null))
        assertNull(strongResumeValidator(null,"Tue, 03 Nov 2026 11:00:00 GMT","Tue, 03 Nov 2026 11:00:30 GMT"))
        assertNotNull(strongResumeValidator(null,"Tue, 03 Nov 2026 11:00:00 GMT","Tue, 03 Nov 2026 11:02:00 GMT"))
        assertNull(parseContentRange("bytes 3-2/8"));assertNull(parseContentRange("bytes 0-9/9"))
        MockWebServer().use { server ->
            val target=File(temp.newFolder(),"video000001.video.mp4");server.enqueue(MockResponse().setChunkedBody(Buffer().write(data),100))
            assertThrows(IllegalStateException::class.java) { runBlocking { downloader().download(spec(server),target,1_000_000) { _,_-> } } };assertFalse(target.exists())
        }
    }
    @Test fun completedRevisionTrackIsReusableButChecksumCorruptionIsNot() = runBlocking {
        MockWebServer().use { server ->
            val target=File(temp.newFolder(),"video000001.video.mp4");val stream=spec(server).copy(resumeIdentity="137|avc1|none|revision=12345")
            server.enqueue(full());downloader().download(stream,target,1_000_000) { _,_-> }
            downloader().download(stream,target,1_000_000) { _,_-> };assertEquals(1,server.requestCount)
            target.writeBytes(ByteArray(data.size));server.enqueue(full());downloader().download(stream,target,1_000_000) { _,_-> }
            assertEquals(2,server.requestCount);assertArrayEquals(data,target.readBytes())
        }
    }
    @Test fun completePartial416IsAcceptedOnlyWithMatchingValidatorAndLength() = runBlocking {
        for(valid in listOf(true,false)) MockWebServer().use { server ->
            val target=File(temp.newFolder(),"video000001.video.mp4");val files=ResumeFiles(target);files.partial.writeBytes(data)
            files.write(ResumeRecord(spec(server).resumeIdentity,data.size.toLong(),"\"one\"",true,blocks=listOf(IntegrityBlock(data.size,sha256(files.partial)))))
            server.enqueue(MockResponse().setResponseCode(416).setHeader("ETag",if(valid)"\"one\"" else "\"other\"").setHeader("Content-Range","bytes */${data.size}"))
            if(!valid)server.enqueue(full())
            downloader().download(spec(server),target,1_000_000) {_,_->}
            assertArrayEquals(data,target.readBytes());assertEquals(if(valid)1 else 2,server.requestCount)
        }
    }
    @Test fun cancellationKeepsPartialButDoesNotPublishPlayableTarget() = runBlocking {
        MockWebServer().use { server ->
            val target=File(temp.newFolder(),"video000001.video.mp4");val started=CompletableDeferred<Unit>()
            val larger = ByteArray(4 * data.size) { (it % 251).toByte() }
            server.enqueue(full(larger).throttleBody(8192,100,java.util.concurrent.TimeUnit.MILLISECONDS))
            val work=launch(Dispatchers.IO) {downloader().download(spec(server).copy(sizeBytes=larger.size.toLong()),target,1_000_000) {bytes,_->if(bytes>0)started.complete(Unit)}}
            withTimeout(5000) {started.await()};withTimeout(5000) {work.cancelAndJoin()}
            assertFalse(target.exists());assertTrue(File(target.path+".part").length()>0);assertNotNull(ResumeFiles(target).read())
        }
    }
    @Test fun receivedDigestDetectsStorageChangeBeforePublication() = runBlocking {
        MockWebServer().use { server ->
            val target=File(temp.newFolder(),"video000001.video.mp4");server.enqueue(full())
            val writer=HttpTrackDownloader(DisabledYouTubeSession,transferEvent={event,_->
                if(event=="VERIFY_TRACK")java.io.RandomAccessFile(target.path+".part","rw").use { it.seek(4096);it.writeByte(99) }
            },allowed={it.host=="localhost"})
            val error=assertThrows(MediaIntegrityFailure::class.java) { runBlocking {writer.download(spec(server),target,1_000_000){_,_->}} }
            assertEquals("WRITE_VERIFY_FAILED",error.code);assertFalse(target.exists())
        }
    }
    @Test fun damagedPartialIsRestartedBeforeRangeRequest() = runBlocking {
        MockWebServer().use { server ->
            val target=File(temp.newFolder(),"video000001.video.mp4");interrupted(server,target)
            java.io.RandomAccessFile(target.path+".part","rw").use {it.seek(17);it.writeByte(99)}
            server.enqueue(full());downloader().download(spec(server),target,1_000_000){_,_->}
            server.takeRequest();assertNull(server.takeRequest().getHeader("Range"));assertArrayEquals(data,target.readBytes())
        }
    }
    @Test fun crashTailBeyondCheckpointIsDiscardedWhileTrustedPrefixResumes() = runBlocking {
        MockWebServer().use { server ->
            val target=File(temp.newFolder(),"video000001.video.mp4");val files=ResumeFiles(target)
            val prefix=data.copyOfRange(0,32768);files.partial.writeBytes(prefix)
            files.write(ResumeRecord(spec(server).resumeIdentity,data.size.toLong(),"\"one\"",true,blocks=listOf(IntegrityBlock(prefix.size,sha256(files.partial)))))
            files.partial.appendBytes(ByteArray(8192){99})
            server.enqueue(full(data.copyOfRange(prefix.size,data.size)).setResponseCode(206).setHeader("Content-Range","bytes ${prefix.size}-${data.lastIndex}/${data.size}"))
            downloader().download(spec(server),target,1_000_000){_,_->}
            assertEquals("bytes=${prefix.size}-",server.takeRequest().getHeader("Range"));assertArrayEquals(data,target.readBytes())
        }
    }
    @Test fun multipleIntegrityCheckpointsResumeAfterLargeTransferBreak() = runBlocking {
        MockWebServer().use { server ->
            val big=ByteArray(9*1024*1024) { (it%251).toByte() };val target=File(temp.newFolder(),"video000001.video.mp4")
            val stream=spec(server).copy(sizeBytes=big.size.toLong())
            server.enqueue(full(big).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            assertThrows(java.io.IOException::class.java) {runBlocking {downloader().download(stream,target,16_000_000){_,_->}}}
            val record=ResumeFiles(target).read()!!;val offset=File(target.path+".part").length()
            assertTrue(record.blocks.size>=2);assertTrue(record.blocks.all {it.bytes in 1..INTEGRITY_BLOCK_BYTES})
            assertEquals(offset,record.blocks.sumOf {it.bytes.toLong()})
            server.enqueue(full(big.copyOfRange(offset.toInt(),big.size)).setResponseCode(206).setHeader("Content-Range","bytes $offset-${big.lastIndex}/${big.size}"))
            downloader().download(stream,target,16_000_000){_,_->};assertArrayEquals(big,target.readBytes())
        }
    }
    @Test fun legacyUnverifiedPartialRestartsInsteadOfBecomingTrusted() = runBlocking {
        MockWebServer().use { server ->
            val target=File(temp.newFolder(),"video000001.video.mp4");val files=ResumeFiles(target);files.partial.writeBytes(ByteArray(32768){99})
            files.write(ResumeRecord(spec(server).resumeIdentity,data.size.toLong(),"\"one\"",true,integrityVersion=0))
            server.enqueue(full());downloader().download(spec(server),target,1_000_000){_,_->}
            assertNull(server.takeRequest().getHeader("Range"));assertArrayEquals(data,target.readBytes())
        }
    }
    @Test fun productionUrlPolicyRejectsArbitraryNetworkTargets() {
        val target=File(temp.newFolder(),"video000001.video.mp4")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { HttpTrackDownloader(DisabledYouTubeSession).download(StreamSpec("http://127.0.0.1/secret","video/mp4",emptyMap()),target,100) { _,_-> } } }
    }
}
