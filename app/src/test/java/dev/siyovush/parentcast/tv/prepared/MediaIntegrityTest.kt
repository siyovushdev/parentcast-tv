package dev.siyovush.parentcast.tv.prepared

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[33],manifest=org.robolectric.annotation.Config.NONE)
class MediaIntegrityTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(name: String): File = temp.newFile(name).also { f ->
        javaClass.getResourceAsStream("/media-integrity/$name")!!.use { f.writeBytes(it.readBytes()) }
    }
    @Test fun readsAllFragmentedAndProgressiveVideoAndAudioSamples() {
        verifyMp4Track(fixture("video.mp4"),false,12)
        verifyMp4Track(fixture("progressive.mp4"),false,12)
        verifyMp4Track(fixture("audio.mp4"),true,12)
    }
    @Test fun rejectsInvalidNalAtSixSecondsAfterValidOpeningFrames() {
        val file = fixture("video.mp4")
        // FFprobe: sample at 6s starts at byte35804. Keep all headers and total size intact.
        RandomAccessFile(file,"rw").use { it.seek(35804);it.writeInt(0) }
        assertEquals("MEDIA_CORRUPT",assertThrows(MediaIntegrityFailure::class.java) { verifyMp4Track(file,false,12) }.code)
    }
    @Test fun rejectsTruncationAndShortTimelineAndWrongTrack() {
        val file = fixture("video.mp4")
        assertThrows(MediaIntegrityFailure::class.java) { verifyMp4Track(file,false,60) }
        assertThrows(MediaIntegrityFailure::class.java) { verifyMp4Track(file,true,12) }
        RandomAccessFile(file,"rw").use { it.setLength(36_000) }
        assertThrows(MediaIntegrityFailure::class.java) { verifyMp4Track(file,false,12) }
    }
    @Test fun cancellationStopsFullScanWithoutReportingCorruption() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            verifyMp4Track(fixture("video.mp4"),false,12) { if(++checks==30) throw CancellationException() }
        }
        assertEquals(30,checks)
    }
    @Test fun retainedBlocksAreVerifiedAgainstIncomingDigests() {
        val first = ByteArray(100_000) { (it%251).toByte() }
        val second = ByteArray(5000) { 17 }
        val file = temp.newFile();file.writeBytes(first)
        val original = IncomingTrackIntegrity.fresh();original.accept(first,first.size)
        val blocks = original.checkpoint()
        val restored = IncomingTrackIntegrity.restore(file,blocks) {}!!
        restored.accept(second,second.size);file.appendBytes(second)
        assertEquals(sha256(file),restored.digest())
        RandomAccessFile(file,"rw").use { it.seek(70000);it.writeByte(99) }
        assertNull(IncomingTrackIntegrity.restore(file,blocks) {})
    }
}
