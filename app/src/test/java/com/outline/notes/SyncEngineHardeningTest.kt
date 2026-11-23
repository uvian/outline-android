package com.outline.notes

import com.outline.notes.data.local.CollectionEntity
import com.outline.notes.data.local.DocumentEntity
import com.outline.notes.data.local.OutlineDao
import com.outline.notes.data.local.PendingAttachmentEntity
import com.outline.notes.data.local.SyncState
import com.outline.notes.data.remote.AuthInfoData
import com.outline.notes.data.remote.CollectionDto
import com.outline.notes.data.remote.DocumentDto
import com.outline.notes.data.remote.NavigationNodeDto
import com.outline.notes.data.remote.OutlineApiClient
import com.outline.notes.data.remote.StarsListData
import com.outline.notes.data.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SyncEngineHardeningTest {

    @Test
    fun p0_1_draftsPullFailure_abortsSafelyAndPreservesLocalDrafts() = runTest {
        val dao = FakeOutlineDao().apply {
            docs["draft-1"] = DocumentEntity(
                id = "draft-1",
                title = "Important Draft",
                text = "Do not lose me",
                isDraft = true,
                syncState = SyncState.SYNCED,
            )
            docs["doc-1"] = DocumentEntity(
                id = "doc-1",
                title = "Published Note",
                text = "Body",
                isDraft = false,
                syncState = SyncState.SYNCED,
            )
        }

        val api = object : FakeOutlineApiClient() {
            override suspend fun listAllDocuments(serverUrl: String, apiKey: String, collectionId: String?) = listOf(
                DocumentDto(id = "doc-1", title = "Published Note", text = "Body", publishedAt = "2026-11-20T00:00:00Z"),
            )

            override suspend fun listDrafts(serverUrl: String, apiKey: String): List<DocumentDto> {
                throw IOException("Simulated 502/timeout on documents.drafts")
            }
        }

        var caught: Exception? = null
        try {
            SyncEngine.executeSyncCycle(
                serverUrl = "https://wiki.example.com",
                apiKey = "test-key",
                defaultCollectionId = "col-1",
                dao = dao,
                api = api,
            )
        } catch (e: IOException) {
            caught = e
        }

        assertNotNull("Failure on listDrafts must abort sync cycle", caught)
        assertNotNull("Local cached draft must remain untouched on partial pull failure", dao.docs["draft-1"])
        assertEquals("Important Draft", dao.docs["draft-1"]?.title)
    }

    @Test
    fun p0_1_twoPhaseDeletion_requiresTwoConsecutiveMissingSyncs() = runTest {
        val dao = FakeOutlineDao().apply {
            docs["gone-doc"] = DocumentEntity(
                id = "gone-doc",
                title = "Removed on PC",
                text = "Body",
                syncState = SyncState.SYNCED,
            )
            docs["transient-doc"] = DocumentEntity(
                id = "transient-doc",
                title = "Temporarily missing",
                text = "Body",
                syncState = SyncState.SYNCED,
            )
        }

        var includeTransient = false
        val api = object : FakeOutlineApiClient() {
            override suspend fun listAllDocuments(serverUrl: String, apiKey: String, collectionId: String?): List<DocumentDto> {
                return if (includeTransient) {
                    listOf(
                        DocumentDto(
                            id = "transient-doc",
                            title = "Temporarily missing",
                            text = "Body",
                            publishedAt = "2026-11-20T00:00:00Z",
                        ),
                    )
                } else {
                    emptyList()
                }
            }
        }

        // Sync #1: both docs missing once -> marked missing (count = 1), NOT deleted yet
        SyncEngine.executeSyncCycle("https://wiki.example.com", "key", "", dao, api)
        assertNotNull(dao.docs["gone-doc"])
        assertEquals(1, dao.docs["gone-doc"]?.remoteMissingCount)
        assertNotNull(dao.docs["transient-doc"])
        assertEquals(1, dao.docs["transient-doc"]?.remoteMissingCount)

        // Sync #2: transient-doc reappears (resets to 0); gone-doc still missing -> now pruned
        includeTransient = true
        SyncEngine.executeSyncCycle("https://wiki.example.com", "key", "", dao, api)
        assertNull("Doc missing on 2 consecutive syncs should be pruned", dao.docs["gone-doc"])
        assertNotNull("Reappeared doc must be kept", dao.docs["transient-doc"])
        assertEquals(0, dao.docs["transient-doc"]?.remoteMissingCount)
    }

    @Test
    fun p0_2_createDocument_atomicallyRemapsChildrenAndPendingAttachments() = runTest {
        val dao = FakeOutlineDao().apply {
            docs["local_parent"] = DocumentEntity(
                id = "local_parent",
                collectionId = "col-1",
                title = "Offline Parent",
                text = "Parent text",
                syncState = SyncState.LOCAL_NEW,
            )
            docs["local_child"] = DocumentEntity(
                id = "local_child",
                collectionId = "col-1",
                parentDocumentId = "local_parent",
                title = "Offline Child",
                text = "Child text",
                syncState = SyncState.LOCAL_NEW,
            )
        }

        val createdMap = mutableMapOf<String, String>()
        val api = object : FakeOutlineApiClient() {
            override suspend fun createDocument(
                serverUrl: String,
                apiKey: String,
                title: String,
                text: String,
                collectionId: String?,
                parentDocumentId: String?,
                publish: Boolean,
            ): DocumentDto {
                val newId = if (title == "Offline Parent") "srv-parent-1" else "srv-child-1"
                createdMap[title] = parentDocumentId.orEmpty()
                return DocumentDto(
                    id = newId,
                    collectionId = collectionId,
                    parentDocumentId = parentDocumentId,
                    title = title,
                    text = text,
                    publishedAt = "2026-11-23T00:00:00Z",
                    updatedAt = "2026-11-23T00:00:00Z",
                )
            }

            override suspend fun listAllDocuments(serverUrl: String, apiKey: String, collectionId: String?) = listOf(
                DocumentDto(id = "srv-parent-1", collectionId = "col-1", title = "Offline Parent", text = "Parent text", publishedAt = "2026-11-23T00:00:00Z"),
                DocumentDto(id = "srv-child-1", collectionId = "col-1", parentDocumentId = "srv-parent-1", title = "Offline Child", text = "Child text", publishedAt = "2026-11-23T00:00:00Z"),
            )
        }

        SyncEngine.executeSyncCycle("https://wiki.example.com", "key", "col-1", dao, api)

        assertNull(dao.docs["local_parent"])
        assertNull(dao.docs["local_child"])
        assertNotNull(dao.docs["srv-parent-1"])
        assertNotNull(dao.docs["srv-child-1"])
        assertEquals("srv-parent-1", createdMap["Offline Child"])
        assertTrue(dao.applyCreatedTxCount >= 2)
    }

    @Test
    fun p1_3_localDeleted_isRetainedWhenRemoteDeleteFails() = runTest {
        val dao = FakeOutlineDao().apply {
            docs["doc-del"] = DocumentEntity(
                id = "doc-del",
                title = "To Delete",
                text = "Bye",
                syncState = SyncState.LOCAL_DELETED,
            )
        }

        val api = object : FakeOutlineApiClient() {
            override suspend fun deleteDocument(serverUrl: String, apiKey: String, documentId: String, permanent: Boolean) {
                throw IOException("Network dropped during delete")
            }

            override suspend fun listAllDocuments(serverUrl: String, apiKey: String, collectionId: String?) = listOf(
                DocumentDto(id = "doc-del", title = "To Delete", text = "Bye", publishedAt = "2026-11-20T00:00:00Z"),
            )
        }

        SyncEngine.executeSyncCycle("https://wiki.example.com", "key", "", dao, api)

        // Must stay LOCAL_DELETED so next sync retries, instead of disappearing and resurrecting as SYNCED
        assertEquals(SyncState.LOCAL_DELETED, dao.docs["doc-del"]?.syncState)
    }

    @Test
    fun p1_5_streamingMediaCache_atomicWriteOversizeRejectAndLruEviction() {
        val cacheDir = Files.createTempDirectory("outline_media_test_").toFile()
        val server = MockWebServer()
        server.start()
        try {
            val client = OutlineApiClient(mediaCacheDir = cacheDir)
            val baseUrl = server.url("/").toString().trimEnd('/')

            // 1. Small image streams cleanly and leaves zero .tmp files
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(ByteArray(1024) { 7 })),
            )
            val first = client.fetchAndCacheMediaBlocking(baseUrl, "k", "/api/attachments.redirect?id=1", maxFileBytes = 2048)
            assertNotNull(first?.file)
            assertEquals(1024L, first?.file?.length())
            assertTrue(cacheDir.listFiles { f -> f.name.endsWith(".tmp") }?.isEmpty() == true)

            // 2. Oversize payload (> maxFileBytes) is rejected without leaving corrupt file
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(ByteArray(4096) { 9 })),
            )
            val oversize = client.fetchAndCacheMediaBlocking(baseUrl, "k", "/api/attachments.redirect?id=big", maxFileBytes = 2048)
            assertNull(oversize)
            assertTrue(cacheDir.listFiles { f -> f.name.endsWith(".tmp") }?.isEmpty() == true)

            // 3. Second cached image + LRU pruning evicts oldest entry
            first?.file?.setLastModified(System.currentTimeMillis() - 60_000L)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/jpeg")
                    .setBody(Buffer().write(ByteArray(1500) { 3 })),
            )
            val second = client.fetchAndCacheMediaBlocking(baseUrl, "k", "/api/attachments.redirect?id=2", maxFileBytes = 2048)
            assertNotNull(second?.file)

            client.pruneMediaCache(maxTotalBytes = 1600L)
            assertEquals(false, first?.file?.exists())
            assertEquals(true, second?.file?.exists())
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }
}

private open class FakeOutlineApiClient : OutlineApiClient() {
    override suspend fun authInfo(serverUrl: String, apiKey: String) = AuthInfoData()
    override suspend fun listCollections(serverUrl: String, apiKey: String) = emptyList<CollectionDto>()
    override suspend fun collectionDocumentsTree(serverUrl: String, apiKey: String, collectionId: String) = emptyList<NavigationNodeDto>()
    override suspend fun listStars(serverUrl: String, apiKey: String) = StarsListData()
    override suspend fun listAllDocuments(serverUrl: String, apiKey: String, collectionId: String?) = emptyList<DocumentDto>()
    override suspend fun listDrafts(serverUrl: String, apiKey: String) = emptyList<DocumentDto>()
    override suspend fun listArchivedDocuments(serverUrl: String, apiKey: String) = emptyList<DocumentDto>()
    override suspend fun listDeletedDocuments(serverUrl: String, apiKey: String) = emptyList<DocumentDto>()
}

private class FakeOutlineDao : OutlineDao {
    val collections = linkedMapOf<String, CollectionEntity>()
    val docs = linkedMapOf<String, DocumentEntity>()
    val pendingAttachments = linkedMapOf<String, PendingAttachmentEntity>()
    var applyCreatedTxCount = 0

    override fun observeCollections(): Flow<List<CollectionEntity>> = flowOf(collections.values.toList())
    override suspend fun getCollections(): List<CollectionEntity> = collections.values.toList()
    override suspend fun upsertCollections(items: List<CollectionEntity>) { items.forEach { collections[it.id] = it } }
    override suspend fun deleteCollectionsNotIn(activeIds: List<String>) { collections.keys.retainAll(activeIds.toSet()) }
    override suspend fun clearCollections() { collections.clear() }

    override fun observeAllActiveDocuments(): Flow<List<DocumentEntity>> = flowOf(docs.values.toList())
    override fun observeRecentDocuments(): Flow<List<DocumentEntity>> = flowOf(docs.values.toList())
    override fun observeStarredDocuments(): Flow<List<DocumentEntity>> = flowOf(docs.values.filter { it.isStarred })
    override fun observeDraftDocuments(): Flow<List<DocumentEntity>> = flowOf(docs.values.filter { it.isDraft })
    override fun observeArchivedDocuments(): Flow<List<DocumentEntity>> = flowOf(docs.values.filter { it.isArchived })
    override fun observeDeletedDocuments(): Flow<List<DocumentEntity>> = flowOf(docs.values.filter { it.isDeleted })
    override fun observeDocumentsInCollection(collectionId: String): Flow<List<DocumentEntity>> = flowOf(docs.values.filter { it.collectionId == collectionId })
    override fun observeDocumentById(id: String): Flow<DocumentEntity?> = flowOf(docs[id])
    override fun observePendingSyncCount(): Flow<Int> = flowOf(docs.values.count { it.syncState in setOf(SyncState.LOCAL_NEW, SyncState.LOCAL_MODIFIED, SyncState.LOCAL_DELETED) })
    override fun observeConflictCount(): Flow<Int> = flowOf(docs.values.count { it.syncState == SyncState.CONFLICT })
    override fun observeUnsyncedDocuments(): Flow<List<DocumentEntity>> = flowOf(docs.values.filter { it.syncState != SyncState.SYNCED })

    override suspend fun getDocumentById(id: String): DocumentEntity? = docs[id]
    override suspend fun findDocumentByUrlIdOrId(urlId: String): DocumentEntity? = docs.values.firstOrNull { it.urlId == urlId || it.id == urlId }
    override suspend fun getRecentDocumentsSnapshot(limit: Int): List<DocumentEntity> = docs.values.take(limit)
    override suspend fun getAllDocuments(): List<DocumentEntity> = docs.values.toList()
    override suspend fun getDirtyDocuments(): List<DocumentEntity> =
        docs.values.filter { it.syncState in setOf(SyncState.LOCAL_NEW, SyncState.LOCAL_MODIFIED, SyncState.LOCAL_DELETED) }

    override suspend fun searchLocalDocumentsFts(ftsQuery: String): List<DocumentEntity> = emptyList()
    override suspend fun searchLocalDocumentsLike(query: String): List<DocumentEntity> = emptyList()
    override suspend fun upsertDocument(doc: DocumentEntity) { docs[doc.id] = doc }
    override suspend fun upsertDocuments(docs: List<DocumentEntity>) { docs.forEach { this.docs[it.id] = it } }
    override suspend fun updateDocument(doc: DocumentEntity) { docs[doc.id] = doc }
    override suspend fun deleteDocumentById(id: String) { docs.remove(id) }
    override suspend fun incrementRemoteMissingCount(id: String) {
        val cur = docs[id] ?: return
        docs[id] = cur.copy(remoteMissingCount = cur.remoteMissingCount + 1)
    }

    override suspend fun remapParentDocumentId(oldParentId: String, newParentId: String) {
        docs.values.filter { it.parentDocumentId == oldParentId }.forEach {
            docs[it.id] = it.copy(parentDocumentId = newParentId)
        }
    }

    override suspend fun clearDocuments() { docs.clear() }

    override suspend fun applyCreatedDocument(localId: String, synced: DocumentEntity) {
        applyCreatedTxCount++
        super.applyCreatedDocument(localId, synced)
    }

    override suspend fun getAllPendingAttachments(): List<PendingAttachmentEntity> = pendingAttachments.values.toList()
    override suspend fun getPendingAttachmentsForDocument(documentId: String): List<PendingAttachmentEntity> =
        pendingAttachments.values.filter { it.documentId == documentId }

    override suspend fun remapPendingAttachmentDocId(oldDocId: String, newDocId: String) {
        pendingAttachments.values.filter { it.documentId == oldDocId }.forEach {
            pendingAttachments[it.id] = it.copy(documentId = newDocId)
        }
    }

    override suspend fun upsertPendingAttachment(item: PendingAttachmentEntity) { pendingAttachments[item.id] = item }
    override suspend fun deletePendingAttachment(id: String) { pendingAttachments.remove(id) }
    override suspend fun clearPendingAttachments() { pendingAttachments.clear() }
}
