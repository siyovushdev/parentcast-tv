package dev.siyovush.parentcast.tv.queue

import dev.siyovush.parentcast.tv.media.OnlineMediaIds
import java.security.MessageDigest

fun validProgrammeVideoId(id: String) = OnlineMediaIds.isOnline(id) || id.matches(Regex("local_[0-9a-f]{32}"))

data class VideoBookmark(val id: String, val name: String, val positionMs: Long) {
    init { require(id.matches(Regex("b_[0-9a-f]{32}"))); require(name.isNotBlank() && name.length <= 100 && name.none(Character::isISOControl)); require(positionMs in 0..604_800_000L) }
}
enum class VideoWatchStatus { NOT_STARTED, IN_PROGRESS, WATCHED }
data class VideoProgress(val positionMs: Long = 0, val durationMs: Long = 0,
    val status: VideoWatchStatus = VideoWatchStatus.NOT_STARTED, val lastPlayedAtMs: Long = 0,
    val bookmarks: List<VideoBookmark> = emptyList()) {
    init { require(positionMs >= 0 && durationMs >= 0 && lastPlayedAtMs >= 0); require(bookmarks.size <= 100 && bookmarks.map { it.id }.distinct().size == bookmarks.size) }
}
data class NextVideo(val videoId: String, val inFlight: Boolean = false, val returnVideoId: String? = null, val returnGroupId: String? = null) {
    init { require(validProgrammeVideoId(videoId)); require(returnVideoId == null || validProgrammeVideoId(returnVideoId)) }
}
data class ChildScreen(val enabled: Boolean = false, val groupIds: List<String> = emptyList()) {
    init { require(groupIds.size <= 100 && groupIds.distinct().size == groupIds.size && groupIds.all { it.matches(Regex("g_[0-9a-f]{32}")) }) }
}
/** Only programme structure is undoable. Playback, history, bookmarks and child grants never roll back. */
data class ProgrammeStructure(val items: List<QueueItem>, val groups: List<QueueGroup>, val favorites: Set<String>) {
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256").digest((items.toString()+"|"+groups+"|"+favorites.sorted()).toByteArray()).joinToString("") { "%02x".format(it) }
}
data class ProgrammeUndo(val id: String, val label: String, val before: ProgrammeStructure, val afterFingerprint: String) {
    init { require(id.matches(Regex("u_[0-9a-f]{32}"))); require(label.length <= 100); require(afterFingerprint.matches(Regex("[0-9a-f]{64}"))) }
}
fun QueueState.structure() = ProgrammeStructure(items, groups, favorites)
fun QueueState.looseVideoIds(): List<String> {
    val assigned = groups.flatMap { it.videoIds }.toSet()
    return items.filter { it.videoId !in assigned }.map { it.videoId }
}
/** Removing membership may turn a video into a loose item, but never deletes its file. */
fun QueueGroup.withMembers(ids: List<String>): QueueGroup? = ids.takeIf { it.isNotEmpty() }?.let {
    copy(videoIds = it, coverVideoId = coverVideoId?.takeIf { id -> id in it })
}
