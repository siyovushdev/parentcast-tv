package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.playback.CoordinatorSnapshot
import dev.siyovush.parentcast.tv.queue.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Called only behind ApiController authentication, Host/Origin and mutation envelope. */
internal class ProgrammeApi(private val coordinator: PlaybackCoordinator, private val dispatcher: CallDispatcher,
    private val coverExists: (String) -> Boolean = { false }) {
    fun state(): JSONObject = state(dispatcher.call { coordinator.currentSnapshot() })
    fun state(snapshot: CoordinatorSnapshot): JSONObject {
        val s=snapshot.queueState
        val ids=(s.items.map { it.videoId }+s.progress.keys).distinct()
        // Use the same pure transition as playback, including one-shot return context.
        val next=if(s.finished || s.stopAfterCurrent)null else s.next().takeUnless { it.finished }
        return JSONObject().put("queueVersion",s.version).put("looseVideoIds",JSONArray(s.looseVideoIds()))
            .put("groups",JSONArray().apply { s.groups.forEach { put(ProgrammeCodec.group(it)) } })
            .put("videos",JSONArray().apply { ids.forEach { id ->
                var p=s.progress[id] ?: VideoProgress()
                if(s.currentItem?.videoId==id && !s.finished)p=p.copy(positionMs=s.positionMs,durationMs=snapshot.durationMs ?: p.durationMs,
                    status=if(s.positionMs>0)VideoWatchStatus.IN_PROGRESS else p.status)
                put(JSONObject().put("videoId",id).put("favorite",id in s.favorites).put("positionMs",p.positionMs).put("durationMs",p.durationMs)
                    .put("status",p.status.name).put("lastPlayedAtMs",p.lastPlayedAtMs).put("bookmarks",JSONArray().apply { p.bookmarks.forEach { put(JSONObject().put("id",it.id).put("name",it.name).put("positionMs",it.positionMs)) } }))
            } })
            .put("history",JSONArray(s.progress.filterValues { it.lastPlayedAtMs>0 }.entries.sortedByDescending { it.value.lastPlayedAtMs }.take(500).map { it.key }))
            .put("playNextVideoId",s.playNext?.takeUnless { it.inFlight }?.videoId ?: JSONObject.NULL)
            .put("nextVideoId",next?.currentItem?.videoId ?: JSONObject.NULL)
            .put("nextGroupId",next?.activeGroupId ?: JSONObject.NULL)
            .put("stopAfterCurrent",s.stopAfterCurrent)
            .put("childScreen",JSONObject().put("enabled",s.childScreen.enabled).put("groupIds",JSONArray(s.childScreen.groupIds)))
            .put("undo",JSONObject().put("available",s.undo!=null).put("id",s.undo?.id ?: JSONObject.NULL).put("label",s.undo?.label ?: ""))
    }
    fun read(): ApiResponse = ApiResponse(200,JSONObject().put("programme",state()).toString())
    fun mutate(json: JSONObject,key: String,fingerprint: String): ApiResponse {
        try {
            // Replay is checked before parsing transient resources such as a deleted custom cover.
            dispatcher.call { coordinator.batchReplay(key,fingerprint) }?.let { return result(it) }
            val version=long(json,"queueVersion")
            val action=text(json,"action",50)
            val allowed=when(action) {
                "REORDER_LOOSE","REMOVE_VIDEOS" -> setOf("videoIds")
                "CREATE_GROUP" -> setOf("name","videoIds")
                "UPDATE_GROUP" -> setOf("groupId","name","favorite","coverVideoId","coverImageId")
                "MEMBERSHIP" -> setOf("videoIds","fromGroupId","toGroupId","mode")
                "FAVORITE_VIDEO" -> setOf("videoId","value")
                "PLAY_NEXT" -> setOf("videoId")
                "STOP_AFTER_CURRENT" -> setOf("value")
                "BOOKMARK_ADD" -> setOf("videoId","name","positionMs")
                "BOOKMARK_DELETE" -> setOf("videoId","bookmarkId")
                "UNDO" -> setOf("undoId")
                "CHILD_SCREEN" -> setOf("enabled","groupIds")
                else -> throw IllegalArgumentException()
            }+setOf("action","queueVersion","idempotencyKey")
            require(json.keys().asSequence().all { it in allowed })
            val command: ProgrammeCommand=when(action) {
                "REORDER_LOOSE" -> ProgrammeCommand.ReorderLoose(strings(json,"videoIds",10_000))
                "CREATE_GROUP" -> ProgrammeCommand.CreateGroup(text(json,"name",200),strings(json,"videoIds",500))
                "UPDATE_GROUP" -> {
                    val image=optionalText(json,"coverImageId",64)
                    if(image!=null){require(image.matches(Regex("[0-9a-f]{64}")));if(!coverExists(image))return error(404,"COVER_NOT_FOUND")}
                    ProgrammeCommand.UpdateGroup(text(json,"groupId",40),if(json.has("name"))text(json,"name",200)else null,
                        if(json.has("favorite"))bool(json,"favorite")else null,json.has("coverVideoId")||json.has("coverImageId"),optionalText(json,"coverVideoId",100),image)
                }
                "MEMBERSHIP" -> ProgrammeCommand.Membership(strings(json,"videoIds",500),optionalText(json,"fromGroupId",40),optionalText(json,"toGroupId",40),text(json,"mode",10))
                "REMOVE_VIDEOS" -> ProgrammeCommand.RemoveVideos(strings(json,"videoIds",500))
                "FAVORITE_VIDEO" -> ProgrammeCommand.Favorite(text(json,"videoId",100),bool(json,"value"))
                "PLAY_NEXT" -> ProgrammeCommand.PlayNext(optionalText(json,"videoId",100))
                "STOP_AFTER_CURRENT" -> ProgrammeCommand.StopAfter(bool(json,"value"))
                "BOOKMARK_ADD" -> ProgrammeCommand.AddBookmark(text(json,"videoId",100),text(json,"name",100),long(json,"positionMs"))
                "BOOKMARK_DELETE" -> ProgrammeCommand.DeleteBookmark(text(json,"videoId",100),text(json,"bookmarkId",40))
                "UNDO" -> ProgrammeCommand.Undo(text(json,"undoId",40))
                "CHILD_SCREEN" -> ProgrammeCommand.Child(bool(json,"enabled"),strings(json,"groupIds",100))
                else -> error("Unreachable")
            }
            return result(dispatcher.call { coordinator.programmeCommand(command,version,key,fingerprint) })
        } catch(e: ProgrammeConflict){return error(409,e.code)}
          catch(_: IllegalArgumentException){return error(400,"BAD_REQUEST")}
          catch(_: JSONException){return error(400,"BAD_REQUEST")}
    }
    private fun result(result: BatchAddResult): ApiResponse { val programme=state(); return queueAdditionResponse(result,null,JSONObject().put("programme",programme).put("queueVersion",programme.getLong("queueVersion")).toString()) }
    private fun error(code: Int,message: String) = ApiResponse(code,JSONObject().put("error",message).toString())
    private fun long(j: JSONObject,k: String): Long { val v=j.get(k);require(v is Int || v is Long);return (v as Number).toLong().also { require(it>=0) } }
    private fun bool(j: JSONObject,k: String) = (j.get(k) as? Boolean) ?: throw IllegalArgumentException()
    private fun text(j: JSONObject,k: String,max: Int): String = (j.get(k) as? String)?.also { require(it.isNotBlank() && it.length<=max && it.none(Character::isISOControl)) } ?: throw IllegalArgumentException()
    private fun optionalText(j: JSONObject,k: String,max: Int): String? = if(!j.has(k)||j.isNull(k))null else text(j,k,max)
    private fun strings(j: JSONObject,k: String,max: Int): List<String> { val a=j.getJSONArray(k);require(a.length()<=max);return List(a.length()) { val v=a.get(it);require(v is String && v.length<=100);v }.also { require(it.distinct().size==it.size) } }
}
