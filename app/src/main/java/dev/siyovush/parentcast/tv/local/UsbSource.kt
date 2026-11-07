package dev.siyovush.parentcast.tv.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.UUID

internal data class UsbItem(val key: String, val name: String, val size: Long, val folder: Boolean)
internal data class UsbTransfer(val item: UsbItem, val open: () -> InputStream)
internal interface UsbFiles {
    fun list(folder: String): List<UsbItem>
    fun capture(key: String): UsbTransfer
    fun connected(): Boolean
}
/** Only the explicitly granted tree, read-only. Web clients never submit a URI/path. */
internal class UsbSource(context: Context) : UsbFiles {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("usb-source", Context.MODE_PRIVATE)
    private data class Handle(val uri: Uri, val item: UsbItem, val tree: String, val expires: Long)
    private val handles = linkedMapOf<String,Handle>()
    private fun tree(): Uri = prefs.getString("tree",null)?.let(Uri::parse) ?: error("USB_NOT_SELECTED")
    fun grant(uri: Uri, flags: Int) {
        require(uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(uri)) { "USB_NOT_SUPPORTED" }
        app.contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        check(prefs.edit().putString("tree",uri.toString()).commit())
        synchronized(handles) { handles.clear() }
    }
    fun forget() {
        prefs.getString("tree",null)?.let { runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(it),Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        check(prefs.edit().remove("tree").commit()); synchronized(handles) { handles.clear() }
    }
    override fun connected(): Boolean = runCatching { val t=tree(); app.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(t,DocumentsContract.getTreeDocumentId(t)),arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)?.use { it.moveToFirst() } == true }.getOrDefault(false)
    private fun handle(key: String): Handle = synchronized(handles) {
        val h=handles[key] ?: error("USB_LIST_EXPIRED")
        check(h.tree == tree().toString() && h.expires > System.currentTimeMillis()) { "USB_LIST_EXPIRED" }; h
    }
    override fun list(folder: String): List<UsbItem> {
        val tree=tree()
        val id=if(folder=="root") DocumentsContract.getTreeDocumentId(tree) else handle(folder).also { check(it.item.folder) }.let { DocumentsContract.getDocumentId(it.uri) }
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id)
        val result=mutableListOf<UsbItem>()
        app.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use { c ->
            while(c.moveToNext()) {
                check(result.size < 500) { "USB_FOLDER_LIMIT" }
                val mime=c.getString(3).orEmpty(); val directory=mime==DocumentsContract.Document.MIME_TYPE_DIR
                if(!directory && !mime.startsWith("video/") && !c.getString(1).orEmpty().substringAfterLast('.').lowercase().let { it in setOf("mp4","mkv","webm","m4v","mov","3gp") }) continue
                val item=UsbItem(UUID.randomUUID().toString(),c.getString(1).orEmpty().take(200),if(c.isNull(2)) -1 else c.getLong(2),directory)
                synchronized(handles) {
                    val time=System.currentTimeMillis(); handles.entries.removeAll { it.value.expires <= time }
                    check(handles.size < 2000) { "USB_FOLDER_LIMIT" }
                    handles[item.key]=Handle(DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0)),item,tree.toString(),time+900_000)
                }
                result+=item
            }
        } ?: error("USB_UNAVAILABLE")
        return result.sortedWith(compareBy<UsbItem> { !it.folder }.thenBy { it.name.lowercase() })
    }
    override fun capture(key: String): UsbTransfer {
        val h=handle(key);check(!h.item.folder && h.item.size>0) { "USB_UNKNOWN_SIZE" }
        return UsbTransfer(h.item) {
            check(h.tree == tree().toString()) { "USB_LIST_EXPIRED" }
            app.contentResolver.openInputStream(h.uri) ?: error("USB_UNAVAILABLE")
        }
    }
}
