package dev.siyovush.parentcast.tv.prepared

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class MediaBudgetLedgerTest {
    @get:Rule val temporary=TemporaryFolder()

    @Test fun slowScanStartsItsRefreshWindowAfterCompletionAndWritesRemainCharged() {
        val root=temporary.newFolder();val target=File(root,"video.mp4")
        var clock=0L;var scans=0
        val budget=MediaBudget(root,100,nowNs={clock},scanBytes={ scans++;clock+=6_000_000_000L;PreparedStorage.mediaBytes(it) })
        FileOutputStream(target).use { out->
            budget.write(out,byteArrayOf(1),1,target)
            clock+=100_000_000L
            repeat(9){budget.write(out,byteArrayOf(2),1,target)}
            assertEquals(1,scans);assertEquals(10L,budget.usedBytes())
            clock+=5_000_000_001L
            assertEquals(10L,budget.usedBytes());assertEquals(2,scans)
        }
    }

    @Test fun multipleWritersShareQuotaAndBoundaryRescanReclaimsDeletedBytes() {
        val root=temporary.newFolder();val first=File(root,"first.mp4");val second=File(root,"second.mp4")
        var scans=0;val budget=MediaBudget(root,12,nowNs={0},scanBytes={scans++;PreparedStorage.mediaBytes(it)})
        FileOutputStream(first).use {a->FileOutputStream(second).use {b->
            budget.write(a,ByteArray(5),5,first);budget.write(b,ByteArray(4),4,second)
            assertEquals(9L,budget.usedBytes());assertEquals(1,scans)
            assertEquals("NO_SPACE",assertThrows(IllegalStateException::class.java){budget.write(b,ByteArray(4),4,second)}.message)
            assertEquals(2,scans);assertEquals(4L,second.length())
            assertTrue(first.delete())
            budget.write(b,ByteArray(4),4,second)
            assertEquals(3,scans);assertEquals(8L,budget.usedBytes());assertEquals(8L,second.length())
        }}
    }

    @Test fun failedWriteInvalidatesUsageBeforeTheNextQuotaDecision() {
        val root=temporary.newFolder();val file=File(root,"video.mp4");var scans=0
        val budget=MediaBudget(root,3,nowNs={0},scanBytes={scans++;PreparedStorage.mediaBytes(it)})
        FileOutputStream(file).use { out->
            assertThrows(IllegalStateException::class.java){
                budget.writeCacheForTest(out,file)
            }
            assertEquals(2L,file.length())
            assertEquals("NO_SPACE",assertThrows(IllegalStateException::class.java){budget.write(out,ByteArray(2),2,file)}.message)
            assertTrue(scans>=2);assertEquals(2L,file.length())
        }
    }

    @Test fun missingVolumeInvalidatesUsageBeforeReinsertedCardIsWritten() {
        val inside=temporary.newFolder();val card=temporary.newFolder();var mounted=true
        val storage=PreparedStorage(inside,{if(mounted)listOf(MediaVolume("sd:test","card",card,true,true,true,FREE_RESERVE+10,FREE_RESERVE+100))else emptyList()})
        val budget=MediaBudget(inside,storage=storage,nowNs={0});val file=File(card,"video.mp4")
        FileOutputStream(file).use {out->
            budget.write(out,ByteArray(5),5,file)
            mounted=false
            assertThrows(StorageUnavailable::class.java){budget.write(out,byteArrayOf(1),1,file)}
            File(card,"restored.mp4").writeBytes(ByteArray(4))
            mounted=true
            assertEquals("NO_SPACE",assertThrows(IllegalStateException::class.java){budget.write(out,ByteArray(2),2,file)}.message)
            assertEquals(5L,file.length())
        }
    }

    private fun MediaBudget.writeCacheForTest(out:FileOutputStream,file:File) {
        // The normal media path can surface an unchecked failure after a partial OS write.
        val broken=object:FileOutputStream(file) {
            override fun write(bytes:ByteArray,offset:Int,length:Int){out.write(bytes,offset,2);throw IllegalStateException("WRITE_FAILED")}
        }
        broken.use { write(it,ByteArray(3),3,file) }
    }
}
