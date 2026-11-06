package dev.siyovush.parentcast.tv.queue

import org.json.JSONArray
import org.json.JSONObject

internal object ProgrammeCodec {
    fun group(g: QueueGroup) = JSONObject().put("id",g.id).put("name",g.name).put("videoIds",JSONArray(g.videoIds)).put("source",g.source.name)
        .put("favorite",g.favorite).put("coverVideoId",g.coverVideoId ?: JSONObject.NULL).put("coverImageId",g.coverImageId ?: JSONObject.NULL)
    fun readGroup(g: JSONObject) = QueueGroup(g.getString("id"),g.getString("name"),strings(g.getJSONArray("videoIds")),QueueGroupSource.valueOf(g.getString("source")),
        g.optBoolean("favorite"),nullable(g,"coverVideoId"),nullable(g,"coverImageId"))
    private fun structure(s: ProgrammeStructure) = JSONObject().put("items",JSONArray().apply { s.items.forEach { put(JSONObject().put("queueItemId",it.queueItemId).put("videoId",it.videoId).put("local",it.local).put("title",it.title)) } })
        .put("groups",JSONArray().apply { s.groups.forEach { put(group(it)) } }).put("favorites",JSONArray(s.favorites.sorted()))
    private fun readStructure(s: JSONObject) = ProgrammeStructure(s.getJSONArray("items").let { a -> List(a.length()) { n -> a.getJSONObject(n).let { QueueItem(it.getString("queueItemId"),it.getString("videoId"),it.getBoolean("local"),it.getString("title")) } } },
        s.getJSONArray("groups").let { a -> List(a.length()) { readGroup(a.getJSONObject(it)) } },strings(s.getJSONArray("favorites")).toSet())
    fun encode(s: QueueState) = JSONObject().put("favorites",JSONArray(s.favorites.sorted())).put("stopAfterCurrent",s.stopAfterCurrent)
        .put("childScreen",JSONObject().put("enabled",s.childScreen.enabled).put("groupIds",JSONArray(s.childScreen.groupIds)))
        .put("progress",JSONObject().apply { s.progress.forEach { (id,p) -> put(id,JSONObject().put("positionMs",p.positionMs).put("durationMs",p.durationMs).put("status",p.status.name).put("lastPlayedAtMs",p.lastPlayedAtMs)
            .put("bookmarks",JSONArray().apply { p.bookmarks.forEach { put(JSONObject().put("id",it.id).put("name",it.name).put("positionMs",it.positionMs)) } })) } })
        .put("playNext",s.playNext?.let { JSONObject().put("videoId",it.videoId).put("inFlight",it.inFlight).put("returnVideoId",it.returnVideoId ?: JSONObject.NULL).put("returnGroupId",it.returnGroupId ?: JSONObject.NULL) } ?: JSONObject.NULL)
        .put("undo",s.undo?.let { JSONObject().put("id",it.id).put("label",it.label).put("before",structure(it.before)).put("afterFingerprint",it.afterFingerprint) } ?: JSONObject.NULL)
    fun decodeInto(s: QueueState, p: JSONObject?): QueueState {
        if(p==null)return s
        val progress=p.optJSONObject("progress")?.let { obj -> obj.keys().asSequence().associateWith { id -> val v=obj.getJSONObject(id)
            VideoProgress(v.getLong("positionMs"),v.getLong("durationMs"),VideoWatchStatus.valueOf(v.getString("status")),v.getLong("lastPlayedAtMs"),v.getJSONArray("bookmarks").let { a -> List(a.length()) { a.getJSONObject(it).let { b -> VideoBookmark(b.getString("id"),b.getString("name"),b.getLong("positionMs")) } } }) } }.orEmpty()
        return s.copy(progress=progress, favorites=p.optJSONArray("favorites")?.let(::strings)?.toSet().orEmpty(),stopAfterCurrent=p.optBoolean("stopAfterCurrent"),
            childScreen=p.optJSONObject("childScreen")?.let { ChildScreen(it.getBoolean("enabled"),strings(it.getJSONArray("groupIds"))) } ?: ChildScreen(),
            playNext=p.optJSONObject("playNext")?.let { NextVideo(it.getString("videoId"),it.getBoolean("inFlight"),nullable(it,"returnVideoId"),nullable(it,"returnGroupId")) },
            undo=p.optJSONObject("undo")?.let { ProgrammeUndo(it.getString("id"),it.getString("label"),readStructure(it.getJSONObject("before")),it.getString("afterFingerprint")) })
    }
    private fun nullable(o: JSONObject,k: String): String? = if(!o.has(k)||o.isNull(k))null else o.getString(k)
    private fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }
}
