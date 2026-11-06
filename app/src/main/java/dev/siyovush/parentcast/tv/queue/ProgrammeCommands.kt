package dev.siyovush.parentcast.tv.queue

import java.util.UUID

class ProgrammeConflict(val code: String) : IllegalStateException(code)
sealed interface ProgrammeCommand {
    data class ReorderLoose(val videoIds: List<String>): ProgrammeCommand
    data class CreateGroup(val name: String,val videoIds: List<String>): ProgrammeCommand
    data class UpdateGroup(val groupId: String,val name: String?=null,val favorite: Boolean?=null,val changeCover: Boolean=false,val coverVideoId: String?=null,val coverImageId: String?=null): ProgrammeCommand
    data class Membership(val videoIds: List<String>,val fromGroupId: String?,val toGroupId: String?,val mode: String): ProgrammeCommand
    data class RemoveVideos(val videoIds: List<String>): ProgrammeCommand
    data class Favorite(val videoId: String,val value: Boolean): ProgrammeCommand
    data class PlayNext(val videoId: String?): ProgrammeCommand
    data class StopAfter(val value: Boolean): ProgrammeCommand
    data class AddBookmark(val videoId: String,val name: String,val positionMs: Long): ProgrammeCommand
    data class DeleteBookmark(val videoId: String,val bookmarkId: String): ProgrammeCommand
    data class Undo(val id: String): ProgrammeCommand
    data class Child(val enabled: Boolean,val groupIds: List<String>): ProgrammeCommand
}

/** Pure structural transaction. It never reads media files or calls the player. */
fun QueueState.applyProgramme(command: ProgrammeCommand): QueueState {
    fun selected(ids: List<String>) { require(ids.size in 1..500 && ids.distinct().size==ids.size && ids.all { id -> items.any { it.videoId==id } }) }
    fun group(id: String) = groups.find { it.id==id } ?: throw IllegalArgumentException("Unknown group")
    fun change(newItems: List<QueueItem> = items, newGroups: List<QueueGroup> = groups, newFavorites: Set<String> = favorites): QueueState {
        val current = currentItem
        val keptIndex = newItems.indexOfFirst { it.queueItemId==current?.queueItemId }
        val index = if(newItems.isEmpty())-1 else if(keptIndex>=0)keptIndex else currentIndex.coerceIn(0,newItems.lastIndex)
        return copy(items=newItems,groups=newGroups,favorites=newFavorites,currentIndex=index,
            positionMs=if(keptIndex>=0)positionMs else 0,finished=finished&&keptIndex>=0,
            activeGroupId=activeGroupId?.takeIf { id -> newGroups.any { it.id==id && current?.videoId in it.videoIds } },
            playNext=playNext?.takeIf { n -> newItems.any { it.videoId==n.videoId } },
            childScreen=childScreen.copy(groupIds=childScreen.groupIds.filter { id -> newGroups.any { it.id==id } }))
    }
    return when(command) {
        is ProgrammeCommand.ReorderLoose -> {
            val loose=looseVideoIds(); require(command.videoIds.size==loose.size && command.videoIds.distinct().size==loose.size && command.videoIds.toSet()==loose.toSet())
            val byId=items.associateBy { it.videoId };val replacements=command.videoIds.iterator();val looseSet=loose.toSet()
            change(newItems=items.map { if(it.videoId in looseSet)byId.getValue(replacements.next()) else it })
        }
        is ProgrammeCommand.CreateGroup -> {
            selected(command.videoIds);require(groups.size<100)
            change(newGroups=groups+QueueGroup("g_"+UUID.randomUUID().toString().replace("-",""),command.name,command.videoIds,QueueGroupSource.CUSTOM))
        }
        is ProgrammeCommand.UpdateGroup -> {
            val old=group(command.groupId)
            val updated=old.copy(name=command.name ?: old.name,favorite=command.favorite ?: old.favorite,
                coverVideoId=if(command.changeCover)command.coverVideoId else old.coverVideoId,
                coverImageId=if(command.changeCover)command.coverImageId else old.coverImageId)
            change(newGroups=groups.map { if(it.id==old.id)updated else it })
        }
        is ProgrammeCommand.Membership -> {
            selected(command.videoIds);require(command.mode in setOf("COPY","MOVE","REMOVE"))
            require(command.fromGroupId!=command.toGroupId)
            val source=command.fromGroupId?.let(::group)
            require(command.videoIds.all { it in (source?.videoIds ?: looseVideoIds()) })
            if(command.mode!="COPY" && source!=null && command.fromGroupId==activeGroupId && currentItem?.videoId in command.videoIds && intent!=PlaybackIntent.STOP)
                throw ProgrammeConflict("CURRENT_VIDEO_IN_USE")
            if(command.toGroupId!=null)group(command.toGroupId)
            require(command.mode!="COPY" || command.toGroupId!=null)
            require(command.mode!="REMOVE" || command.toGroupId==null && source!=null)
            val ids=command.videoIds.toSet()
            change(newGroups=groups.mapNotNull { g ->
                when {
                    g.id==command.toGroupId -> g.copy(videoIds=(g.videoIds+command.videoIds).distinct())
                    g.id==command.fromGroupId && command.mode!="COPY" -> g.withMembers(g.videoIds.filterNot { it in ids })
                    else -> g
                }
            })
        }
        is ProgrammeCommand.RemoveVideos -> {
            selected(command.videoIds)
            if(currentItem?.videoId in command.videoIds && intent!=PlaybackIntent.STOP)throw ProgrammeConflict("CURRENT_VIDEO_IN_USE")
            val ids=command.videoIds.toSet()
            change(newItems=items.filterNot { it.videoId in ids },newGroups=groups.mapNotNull { g -> g.withMembers(g.videoIds.filterNot { it in ids }) })
        }
        is ProgrammeCommand.Favorite -> { selected(listOf(command.videoId));change(newFavorites=if(command.value)favorites+command.videoId else favorites-command.videoId) }
        is ProgrammeCommand.PlayNext -> {
            // Replacing an active one-shot would discard its saved return playlist.
            if(playNext?.inFlight==true)throw ProgrammeConflict("PLAY_NEXT_IN_PROGRESS")
            if(command.videoId!=null){selected(listOf(command.videoId));require(command.videoId!=currentItem?.videoId)}
            copy(playNext=command.videoId?.let(::NextVideo))
        }
        is ProgrammeCommand.StopAfter -> copy(stopAfterCurrent=command.value)
        is ProgrammeCommand.AddBookmark -> {
            selected(listOf(command.videoId));require(progress.values.sumOf { it.bookmarks.size }<1000);val old=progress[command.videoId] ?: VideoProgress()
            require(old.durationMs<=0 || command.positionMs<=old.durationMs)
            copy(progress=progress+(command.videoId to old.copy(bookmarks=old.bookmarks+VideoBookmark("b_"+UUID.randomUUID().toString().replace("-",""),command.name,command.positionMs))))
        }
        is ProgrammeCommand.DeleteBookmark -> {
            val old=progress[command.videoId] ?: throw IllegalArgumentException("Unknown video")
            require(old.bookmarks.any { it.id==command.bookmarkId })
            copy(progress=progress+(command.videoId to old.copy(bookmarks=old.bookmarks.filterNot { it.id==command.bookmarkId })))
        }
        is ProgrammeCommand.Undo -> {
            val last=undo ?: throw ProgrammeConflict("UNDO_UNAVAILABLE")
            if(last.id!=command.id || last.afterFingerprint!=structure().fingerprint())throw ProgrammeConflict("UNDO_UNAVAILABLE")
            if(intent!=PlaybackIntent.STOP && currentItem!=null && last.before.items.none { it.queueItemId==currentItem!!.queueItemId })throw ProgrammeConflict("UNDO_CURRENT_VIDEO_IN_USE")
            if(intent!=PlaybackIntent.STOP && activeGroupId!=null && last.before.groups.none { it.id==activeGroupId && currentItem?.videoId in it.videoIds })throw ProgrammeConflict("UNDO_CURRENT_VIDEO_IN_USE")
            change(last.before.items,last.before.groups,last.before.favorites).copy(undo=null)
        }
        is ProgrammeCommand.Child -> {
            require(command.groupIds.all { id -> groups.any { it.id==id } })
            copy(childScreen=ChildScreen(command.enabled,command.groupIds))
        }
    }
}
