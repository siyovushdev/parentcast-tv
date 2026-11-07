package dev.siyovush.parentcast.tv.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AtomicCollectionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun file() = File(temporaryFolder.root, "collections.json")

    private fun selection(id: String = "collection-1", ids: List<String> = listOf("video000002", "video000001", "video000002")) =
        SavedCollection(id, "Программа на вечер", ids, 1_234L)

    @Test
    fun restoresFixedOrderedSelectionIncludingIntentionalDuplicates() {
        val store = AtomicCollectionStore(file())
        val collection = selection()
        store.save(collection)
        val restored = AtomicCollectionStore(file())
        assertEquals(listOf(collection), restored.list())
        assertEquals(collection, restored.get(collection.id))
        assertFalse(file().readText().contains("https://"))
        assertFalse(file().readText().contains("playlist"))
        assertEquals(listOf("collections.json"), temporaryFolder.root.listFiles()!!.map { it.name })
    }

    @Test
    fun changingOriginalPlaylistOrInputCannotChangeSavedProgram() {
        val sourcePlaylist = mutableListOf("video000001", "video000002")
        val store = AtomicCollectionStore(file())
        store.save(selection(ids = sourcePlaylist))
        sourcePlaylist.add("video000003")
        sourcePlaylist.reverse()
        assertEquals(listOf("video000001", "video000002"), store.get("collection-1")!!.videoIds)
        val returned = store.get("collection-1")!!.videoIds as MutableList<String>
        returned.clear()
        assertEquals(listOf("video000001", "video000002"), store.get("collection-1")!!.videoIds)
    }

    @Test
    fun replacesExistingIdWithoutCreatingDuplicateAndDeletesOnlyRequestedSelection() {
        val store = AtomicCollectionStore(file())
        store.save(selection())
        store.save(selection("collection-2"))
        val renamed = selection().copy(name = "Утро", videoIds = listOf("video000003"))
        store.save(renamed)
        assertEquals(listOf(renamed, selection("collection-2")), store.list())
        assertTrue(store.delete("collection-1"))
        assertFalse(store.delete("collection-1"))
        assertNull(store.get("collection-1"))
        assertEquals(listOf(selection("collection-2")), store.list())
    }

    @Test fun mixedVkAndYoutubeSelectionIsFixedAndOrdered() {
        val ids=mutableListOf("vk:-227392256_456239129","video000001")
        val store=AtomicCollectionStore(file());store.save(selection(ids=ids))
        ids.reverse();ids.add("vk:-1_2")
        assertEquals(listOf("vk:-227392256_456239129","video000001"),AtomicCollectionStore(file()).get("collection-1")!!.videoIds)
    }

    @Test
    fun missingStorageStartsEmpty() {
        assertEquals(emptyList<SavedCollection>(), AtomicCollectionStore(file()).list())
    }

    @Test
    fun rejectsBadNamesIdsCountsAndTimes() {
        listOf("", " ", "\nНазвание", "a".repeat(101)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { selection().copy(name = name) }
        }
        listOf(emptyList(), listOf("bad"), listOf("https://youtu.be/video000001"), List(501) { "video000001" }).forEach { ids ->
            assertThrows(IllegalArgumentException::class.java) { selection(ids = ids) }
        }
        assertThrows(IllegalArgumentException::class.java) { selection().copy(id = "../file") }
        assertThrows(IllegalArgumentException::class.java) { selection().copy(createdAtMs = -1) }
    }

    @Test
    fun permitsExactly500AndRejectsMutationToInvalidInput() {
        val store = AtomicCollectionStore(file())
        store.save(selection(ids = List(500) { "video000001" }))
        assertEquals(500, store.get("collection-1")!!.videoIds.size)
        val input = mutableListOf("video000001")
        val selection = selection(ids = input)
        input[0] = "bad"
        assertThrows(IllegalArgumentException::class.java) { store.save(selection) }
        assertEquals(500, store.get("collection-1")!!.videoIds.size)
    }

    @Test
    fun corruptStorageIsNotSilentlyOverwrittenBySaveOrDelete() {
        val store = AtomicCollectionStore(file())
        listOf("", "not-json", "{\"version\":99,\"collections\":[]}", "{\"version\":1,\"collections\":[{}]}").forEach { corrupt ->
            file().writeText(corrupt)
            assertThrows(IOException::class.java) { store.list() }
            assertThrows(IOException::class.java) { store.save(selection()) }
            assertThrows(IOException::class.java) { store.delete("collection-1") }
            assertEquals(corrupt, file().readText())
        }
    }

    @Test
    fun enforcesCollectionLimitAndAllowsReplacementAtLimit() {
        val store = AtomicCollectionStore(file())
        repeat(50) { store.save(selection("collection-$it")) }
        assertThrows(CollectionLimitException::class.java) { store.save(selection("collection-51")) }
        store.save(selection("collection-0").copy(name = "Обновлённая"))
        assertEquals(50, store.list().size)
        assertEquals("Обновлённая", store.get("collection-0")!!.name)
    }

    @Test
    fun enforcesTotalVideoLimitWithoutChangingExistingData() {
        val store = AtomicCollectionStore(file())
        repeat(20) { store.save(selection("collection-$it", List(500) { "video000001" })) }
        val before = file().readText()
        assertThrows(CollectionLimitException::class.java) { store.save(selection("another", listOf("video000002"))) }
        assertEquals(before, file().readText())
    }
}
