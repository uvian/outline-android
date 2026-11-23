package com.outline.notes.data.local

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

object SyncState {
    const val SYNCED = "SYNCED"
    const val LOCAL_NEW = "LOCAL_NEW"
    const val LOCAL_MODIFIED = "LOCAL_MODIFIED"
    const val LOCAL_DELETED = "LOCAL_DELETED"
    const val CONFLICT = "CONFLICT"
}

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val color: String = "#0366D6",
    val icon: String? = null,
    val sortIndex: String = "",
    val updatedAt: String = "",
)

@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["collectionId"]),
        Index(value = ["parentDocumentId"]),
        Index(value = ["syncState"]),
        Index(value = ["isStarred"]),
        Index(value = ["urlId"]),
    ],
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val urlId: String? = null,
    val collectionId: String? = null,
    val parentDocumentId: String? = null,
    val title: String,
    val text: String,
    val emoji: String? = null,
    val isDraft: Boolean = false,
    val isArchived: Boolean = false,
    val archivedAt: String? = null,
    val isDeleted: Boolean = false,
    val deletedAt: String? = null,
    val isStarred: Boolean = false,
    val starId: String? = null,
    val treeOrder: Int = 0,
    val revision: Int = 0,
    val updatedAt: String = "",
    val localUpdatedAt: Long = System.currentTimeMillis(),
    val syncState: String = SyncState.SYNCED,
    val remoteMissingCount: Int = 0,
    val serverBaseRevision: Int = 0,
    val serverBaseUpdatedAt: String = "",
    val conflictServerTitle: String? = null,
    val conflictServerText: String? = null,
    val conflictServerUpdatedAt: String? = null,
)

@Fts4(contentEntity = DocumentEntity::class)
@Entity(tableName = "documents_fts")
data class DocumentFtsEntity(
    val title: String,
    val text: String,
)

@Entity(tableName = "pending_attachments")
data class PendingAttachmentEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val localUriMarker: String,
    val localFilePath: String,
    val fileName: String,
    val contentType: String,
    val createdAt: Long = System.currentTimeMillis(),
)
