package com.outline.notes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OutlineDao {

    // Collections
    @Query("SELECT * FROM collections ORDER BY sortIndex ASC, name ASC")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections ORDER BY sortIndex ASC, name ASC")
    suspend fun getCollections(): List<CollectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollections(items: List<CollectionEntity>)

    @Query("DELETE FROM collections WHERE id NOT IN (:activeIds)")
    suspend fun deleteCollectionsNotIn(activeIds: List<String>)

    @Query("DELETE FROM collections")
    suspend fun clearCollections()

    // Documents - Reactive queries
    @Query("SELECT * FROM documents WHERE syncState != 'LOCAL_DELETED' AND isDeleted = 0 ORDER BY localUpdatedAt DESC")
    fun observeAllActiveDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE syncState != 'LOCAL_DELETED' AND isDraft = 0 AND isArchived = 0 AND isDeleted = 0 ORDER BY updatedAt DESC, localUpdatedAt DESC")
    fun observeRecentDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE syncState != 'LOCAL_DELETED' AND isStarred = 1 AND isArchived = 0 AND isDeleted = 0 ORDER BY updatedAt DESC, localUpdatedAt DESC")
    fun observeStarredDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE syncState != 'LOCAL_DELETED' AND isDraft = 1 AND isArchived = 0 AND isDeleted = 0 ORDER BY localUpdatedAt DESC")
    fun observeDraftDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE syncState != 'LOCAL_DELETED' AND isArchived = 1 AND isDeleted = 0 ORDER BY archivedAt DESC, localUpdatedAt DESC")
    fun observeArchivedDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE isDeleted = 1 OR syncState = 'LOCAL_DELETED' ORDER BY deletedAt DESC, localUpdatedAt DESC")
    fun observeDeletedDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE syncState != 'LOCAL_DELETED' AND collectionId = :collectionId AND isDraft = 0 AND isArchived = 0 AND isDeleted = 0 ORDER BY treeOrder ASC, title ASC")
    fun observeDocumentsInCollection(collectionId: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    fun observeDocumentById(id: String): Flow<DocumentEntity?>

    @Query("SELECT COUNT(*) FROM documents WHERE syncState IN ('LOCAL_NEW', 'LOCAL_MODIFIED', 'LOCAL_DELETED')")
    fun observePendingSyncCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM documents WHERE syncState = 'CONFLICT'")
    fun observeConflictCount(): Flow<Int>

    @Query("SELECT * FROM documents WHERE syncState IN ('LOCAL_NEW', 'LOCAL_MODIFIED', 'LOCAL_DELETED', 'CONFLICT') ORDER BY localUpdatedAt DESC")
    fun observeUnsyncedDocuments(): Flow<List<DocumentEntity>>

    // Documents - One-shot queries
    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentById(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE urlId = :urlId OR id = :urlId LIMIT 1")
    suspend fun findDocumentByUrlIdOrId(urlId: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE syncState != 'LOCAL_DELETED' AND isDraft = 0 AND isArchived = 0 AND isDeleted = 0 ORDER BY localUpdatedAt DESC LIMIT :limit")
    suspend fun getRecentDocumentsSnapshot(limit: Int): List<DocumentEntity>

    @Query("SELECT * FROM documents")
    suspend fun getAllDocuments(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE syncState IN ('LOCAL_NEW', 'LOCAL_MODIFIED', 'LOCAL_DELETED')")
    suspend fun getDirtyDocuments(): List<DocumentEntity>

    @Query(
        """
        SELECT documents.* FROM documents
        JOIN documents_fts ON documents.rowid = documents_fts.rowid
        WHERE documents_fts MATCH :ftsQuery
          AND documents.syncState != 'LOCAL_DELETED'
          AND documents.isDeleted = 0
        ORDER BY documents.localUpdatedAt DESC
        LIMIT 50
        """,
    )
    suspend fun searchLocalDocumentsFts(ftsQuery: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE syncState != 'LOCAL_DELETED' AND isDeleted = 0 AND (title LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%') ORDER BY localUpdatedAt DESC LIMIT 50")
    suspend fun searchLocalDocumentsLike(query: String): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(doc: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocuments(docs: List<DocumentEntity>)

    @Update
    suspend fun updateDocument(doc: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocumentById(id: String)

    @Query("UPDATE documents SET remoteMissingCount = remoteMissingCount + 1 WHERE id = :id")
    suspend fun incrementRemoteMissingCount(id: String)

    @Query("UPDATE documents SET parentDocumentId = :newParentId WHERE parentDocumentId = :oldParentId")
    suspend fun remapParentDocumentId(oldParentId: String, newParentId: String)

    @Query("DELETE FROM documents")
    suspend fun clearDocuments()

    @Transaction
    suspend fun applyCollectionsSnapshot(items: List<CollectionEntity>) {
        upsertCollections(items)
        if (items.isNotEmpty()) {
            deleteCollectionsNotIn(items.map { it.id })
        }
    }

    @Transaction
    suspend fun applyCreatedDocument(localId: String, synced: DocumentEntity) {
        deleteDocumentById(localId)
        upsertDocument(synced)
        remapParentDocumentId(localId, synced.id)
        remapPendingAttachmentDocId(localId, synced.id)
    }

    @Transaction
    suspend fun applyPullBatch(
        toUpsert: List<DocumentEntity>,
        toMarkMissing: List<String>,
        toDelete: List<String>,
    ) {
        if (toUpsert.isNotEmpty()) {
            upsertDocuments(toUpsert)
        }
        for (id in toMarkMissing) {
            incrementRemoteMissingCount(id)
        }
        for (id in toDelete) {
            deleteDocumentById(id)
        }
    }

    // Pending Attachments
    @Query("SELECT * FROM pending_attachments ORDER BY createdAt ASC")
    suspend fun getAllPendingAttachments(): List<PendingAttachmentEntity>

    @Query("SELECT * FROM pending_attachments WHERE documentId = :documentId")
    suspend fun getPendingAttachmentsForDocument(documentId: String): List<PendingAttachmentEntity>

    @Query("UPDATE pending_attachments SET documentId = :newDocId WHERE documentId = :oldDocId")
    suspend fun remapPendingAttachmentDocId(oldDocId: String, newDocId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingAttachment(item: PendingAttachmentEntity)

    @Query("DELETE FROM pending_attachments WHERE id = :id")
    suspend fun deletePendingAttachment(id: String)

    @Query("DELETE FROM pending_attachments")
    suspend fun clearPendingAttachments()
}
