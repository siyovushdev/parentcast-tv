package dev.siyovush.parentcast.tv.web

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistCoversTest {
    private val jpeg = byteArrayOf(0xff.toByte(),0xd8.toByte(),3,5,0xff.toByte(),0xd9.toByte())
    @Test fun uploadIsContentAddressedAndOnlyLinkedImagesAreReadable() {
        val dir=Files.createTempDirectory("covers").toFile()
        try {
            val covers=PlaylistCovers(dir,{jpeg},{_,_->null})
            val first=covers.upload(3,byteArrayOf(1,2,3).inputStream())
            val second=covers.upload(3,byteArrayOf(1,2,3).inputStream())
            assertEquals(200,first.code);assertEquals(first.body,second.body)
            val id=JSONObject(first.body).getString("coverImageId")
            assertEquals(1,dir.listFiles()!!.size)
            assertEquals(404,covers.read(id,false).code);assertEquals(200,covers.read(id,true).code)
            assertEquals(400,covers.read("../token",true).code)
        } finally {dir.deleteRecursively()}
    }
    @Test fun oversizedTruncatedAndInvalidInputsNeverCreateImages() {
        val dir=Files.createTempDirectory("covers").toFile()
        try {
            val covers=PlaylistCovers(dir,{null},{_,_->null})
            assertEquals(413,covers.upload(PlaylistCovers.MAX_INPUT_BYTES+1,byteArrayOf().inputStream()).code)
            assertEquals(400,covers.upload(5,byteArrayOf(1,2).inputStream()).code)
            assertEquals(400,covers.upload(2,byteArrayOf(1,2).inputStream()).code)
            assertEquals(0,dir.listFiles()!!.size)
        } finally {dir.deleteRecursively()}
    }
    @Test fun quotaKeepsExistingImagesAndFrameFailureDoesNotInventSuccess() {
        val dir=Files.createTempDirectory("covers").toFile()
        try {
            var image=jpeg
            val covers=PlaylistCovers(dir,{image},{_,_->null},jpeg.size.toLong())
            assertEquals(200,covers.upload(1,byteArrayOf(1).inputStream()).code)
            image=jpeg.copyOf().also {it[2]=9}
            assertEquals(409,covers.upload(1,byteArrayOf(2).inputStream()).code)
            assertEquals(1,dir.listFiles()!!.size)
            assertEquals(409,covers.frame("jNQXAC9IVRw",0).code)
            assertEquals(400,covers.frame("file:///secrets",0).code)
        } finally {dir.deleteRecursively()}
    }
}
