package com.outline.notes.data.sync

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.outline.notes.data.local.CollectionEntity
import com.outline.notes.data.local.DocumentEntity
import com.outline.notes.data.local.OutlineDao
import com.outline.notes.data.local.PendingAttachmentEntity
import com.outline.notes.data.local.SyncState
import com.outline.notes.data.media.ImageProcessor
import com.outline.notes.data.prefs.AppPreferences
import com.outline.notes.data.remote.DocumentDto
import com.outline.notes.data.remote.NavigationNodeDto
import com.outline.notes.data.remote.OutlineApiClient
import com.outline.notes.data.remote.OutlineApiException
import com.outline.notes.widget.QuickNoteWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

sealed class SyncResult {
    data class Success(val pushed: Int, val pulled: Int, val conflicts: Int) : SyncResult()
    data object SkippedOffline : SyncResult()
    data class Error(val message: String) : SyncResult()
}

data class SyncStatus(
    val isSyncing: Boolean = false,
    val lastError: String? = null,
    val lastSyncedAt: Long = 0L,
    val message: String? = null,
)

class SyncEngine(
    private val context: Context,
    private val dao: OutlineDao,
    private val api: OutlineApiClient,
    private val preferences: AppPreferences,
    private val networkMonitor: NetworkMonitor,
) {
    private val syncMutex = Mutex()

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    val offlineMediaDir: File
        get() = File(context.filesDir, "offline_media").apply { mkdirs() }

    val mediaCacheDir: File
        get() = File(context.cacheDir, "media_cache").apply { mkdirs() }

    suspend fun syncNow(silent: Boolean = false): SyncResult {
        val settings = preferences.getSettings()
        if (!settings.isConfigured) {
            return SyncResult.Error("尚未配置 Outline 服务器")
        }
        if (!networkMonitor.isOnline.value) {
            if (!silent) {
                _syncStatus.value = _syncStatus.value.copy(
                    isSyncing = false,
                    message = "当前离线，本地修改将在联网后自动同步",
                )
            }
            SyncWorker.scheduleWhenConnected(context)
            return SyncResult.SkippedOffline
        }

        return syncMutex.withLock {
            SyncDiagnostics.log("SYNC", "开始同步 (silent=$silent, server=${settings.serverUrl})")
            _syncStatus.value = _syncStatus.value.copy(isSyncing = true, lastError = null, message = "正在同步...")
            try {
                val outcome = executeSyncCycle(
                    serverUrl = settings.serverUrl,
                    apiKey = settings.apiKey,
                    defaultCollectionId = settings.defaultCollectionId,
                    dao = dao,
                    api = api,
                    onUpdateBranding = { name, logoUrl, accentHex ->
                        preferences.updateWorkspaceBranding(name, logoUrl, accentHex)
                    },
                    onSetDefaultCollection = { colId ->
                        preferences.setDefaultCollectionId(colId)
                    },
                )
                val now = System.currentTimeMillis()
                preferences.setLastSyncAt(now)
                QuickNoteWidgetProvider.requestUpdate(context)
                val statusMsg = when {
                    outcome.conflicts > 0 -> "同步完成，发现 ${outcome.conflicts} 篇冲突笔记需要处理"
                    outcome.pushed > 0 -> "已上传 ${outcome.pushed} 项更改，共 ${outcome.pulled} 篇笔记"
                    else -> "已是最新（共 ${outcome.pulled} 篇笔记）"
                }
                _syncStatus.value = SyncStatus(
                    isSyncing = false,
                    lastError = null,
                    lastSyncedAt = now,
                    message = statusMsg,
                )
                SyncResult.Success(pushed = outcome.pushed, pulled = outcome.pulled, conflicts = outcome.conflicts)
            } catch (e: Exception) {
                val msg = when (e) {
                    is OutlineApiException -> "服务器返回错误 (${e.statusCode}): ${e.message}"
                    else -> "同步失败: ${e.message ?: "网络连接异常"}"
                }
                SyncDiagnostics.log("ERROR", msg)
                _syncStatus.value = _syncStatus.value.copy(
                    isSyncing = false,
                    lastError = msg,
                    message = msg,
                )
                SyncWorker.scheduleWhenConnected(context)
                SyncResult.Error(msg)
            }
        }
    }

    /**
     * Create a new document locally (works 100% offline), then schedule/trigger sync.
     */
    suspend fun createLocalDocument(
        title: String = "",
        text: String = "",
        collectionId: String?,
        parentDocumentId: String? = null,
        isDraft: Boolean = false,
    ): DocumentEntity {
        val localId = "local_${UUID.randomUUID()}"
        val nowIso = Instant.now().toString()
        val effectiveCollection = collectionId?.takeIf { it.isNotBlank() }
            ?: preferences.getSettings().defaultCollectionId.takeIf { it.isNotBlank() }
            ?: dao.getCollections().firstOrNull()?.id

        val entity = DocumentEntity(
            id = localId,
            collectionId = effectiveCollection,
            parentDocumentId = parentDocumentId,
            title = title,
            text = text,
            isDraft = isDraft || effectiveCollection == null,
            updatedAt = nowIso,
            localUpdatedAt = System.currentTimeMillis(),
            syncState = SyncState.LOCAL_NEW,
        )
        dao.upsertDocument(entity)
        QuickNoteWidgetProvider.requestUpdate(context)
        SyncWorker.scheduleWhenConnected(context)
        return entity
    }

    /**
     * Save edits locally immediately (offline-safe). Never loses keystrokes.
     */
    suspend fun saveLocalEdit(
        documentId: String,
        title: String,
        text: String,
    ): DocumentEntity? {
        val existing = dao.getDocumentById(documentId) ?: return null
        if (existing.title == title && existing.text == text) return existing

        val nextState = when (existing.syncState) {
            SyncState.LOCAL_NEW -> SyncState.LOCAL_NEW
            SyncState.CONFLICT -> SyncState.CONFLICT
            else -> SyncState.LOCAL_MODIFIED
        }
        val updated = existing.copy(
            title = title,
            text = text,
            localUpdatedAt = System.currentTimeMillis(),
            updatedAt = Instant.now().toString(),
            syncState = nextState,
        )
        dao.upsertDocument(updated)
        SyncWorker.scheduleWhenConnected(context)
        return updated
    }

    suspend fun deleteDocumentLocally(documentId: String) {
        val existing = dao.getDocumentById(documentId) ?: return
        if (existing.syncState == SyncState.LOCAL_NEW || existing.id.startsWith("local_")) {
            dao.deleteDocumentById(documentId)
        } else {
            dao.upsertDocument(
                existing.copy(
                    syncState = SyncState.LOCAL_DELETED,
                    localUpdatedAt = System.currentTimeMillis(),
                ),
            )
            SyncWorker.scheduleWhenConnected(context)
        }
    }

    suspend fun permanentlyDeleteDocument(documentId: String): Result<Unit> {
        val existing = dao.getDocumentById(documentId) ?: return Result.failure(IllegalStateException("文档不存在"))
        val settings = preferences.getSettings()
        if (existing.id.startsWith("local_")) {
            dao.deleteDocumentById(documentId)
            return Result.success(Unit)
        }
        if (!settings.isConfigured || !networkMonitor.isOnline.value) {
            return Result.failure(IllegalStateException("彻底删除需要连接云端服务器"))
        }
        return runCatching {
            api.deleteDocument(settings.serverUrl, settings.apiKey, existing.id, permanent = true)
            dao.deleteDocumentById(existing.id)
            SyncDiagnostics.log("TRASH", "永久删除文档 ${existing.id}")
        }
    }

    suspend fun moveDocument(
        documentId: String,
        targetCollectionId: String,
        targetParentDocumentId: String? = null,
    ): Result<Unit> {
        val existing = dao.getDocumentById(documentId) ?: return Result.failure(IllegalStateException("文档不存在"))
        if (existing.id.startsWith("local_")) {
            dao.upsertDocument(
                existing.copy(
                    collectionId = targetCollectionId,
                    parentDocumentId = targetParentDocumentId,
                    isDraft = false,
                    localUpdatedAt = System.currentTimeMillis(),
                ),
            )
            return Result.success(Unit)
        }
        val settings = preferences.getSettings()
        if (!settings.isConfigured || !networkMonitor.isOnline.value) {
            return Result.failure(IllegalStateException("移动已同步文档需要连接云端服务器"))
        }
        return runCatching {
            api.moveDocument(
                serverUrl = settings.serverUrl,
                apiKey = settings.apiKey,
                documentId = existing.id,
                collectionId = targetCollectionId,
                parentDocumentId = targetParentDocumentId,
            )
            dao.upsertDocument(
                existing.copy(
                    collectionId = targetCollectionId,
                    parentDocumentId = targetParentDocumentId,
                    isDraft = false,
                    localUpdatedAt = System.currentTimeMillis(),
                ),
            )
            SyncDiagnostics.log("MOVE", "移动文档 ${existing.id} -> collection=$targetCollectionId, parent=$targetParentDocumentId")
        }
    }

    /**
     * Resolves an Outline internal document link (e.g. /doc/my-title-AbCdEf1234 or https://wiki/doc/...)
     * to a local Document ID. If not yet cached locally and online, fetches it via documents.info!
     */
    suspend fun resolveInternalDocumentLink(rawUrl: String): String? {
        val settings = preferences.getSettings()
        val slugOrId = extractOutlineDocIdentifier(rawUrl, settings.serverUrl) ?: return null

        val local = dao.findDocumentByUrlIdOrId(slugOrId)
        if (local != null) return local.id

        // If the slug is "some-title-urlId", Outline's urlId is the final segment after the last '-'
        val trailingUrlId = slugOrId.substringAfterLast('-', slugOrId)
        if (trailingUrlId != slugOrId) {
            dao.findDocumentByUrlIdOrId(trailingUrlId)?.let { return it.id }
        }

        if (!settings.isConfigured || !networkMonitor.isOnline.value) return null
        return runCatching {
            val remote = api.getDocumentInfo(settings.serverUrl, settings.apiKey, trailingUrlId)
            dao.upsertDocument(remote.toEntity())
            remote.id
        }.getOrNull()
    }

    suspend fun archiveDocument(documentId: String): Result<Unit> {
        val existing = dao.getDocumentById(documentId) ?: return Result.failure(IllegalStateException("文档不存在"))
        val settings = preferences.getSettings()
        if (!settings.isConfigured || !networkMonitor.isOnline.value || existing.id.startsWith("local_")) {
            return Result.failure(IllegalStateException("归档操作需要联网且文档已同步至云端"))
        }
        return runCatching {
            val archived = api.archiveDocument(settings.serverUrl, settings.apiKey, existing.id)
            dao.upsertDocument(
                archived.toEntity(
                    isStarred = existing.isStarred,
                    starId = existing.starId,
                    treeOrder = existing.treeOrder,
                ),
            )
        }
    }

    suspend fun restoreDocument(documentId: String): Result<Unit> {
        val existing = dao.getDocumentById(documentId) ?: return Result.failure(IllegalStateException("文档不存在"))
        if (existing.syncState == SyncState.LOCAL_DELETED) {
            dao.upsertDocument(
                existing.copy(
                    syncState = SyncState.SYNCED,
                    isDeleted = false,
                    deletedAt = null,
                    localUpdatedAt = System.currentTimeMillis(),
                ),
            )
            return Result.success(Unit)
        }
        val settings = preferences.getSettings()
        if (!settings.isConfigured || !networkMonitor.isOnline.value || existing.id.startsWith("local_")) {
            return Result.failure(IllegalStateException("恢复文档需要连接云端服务器"))
        }
        return runCatching {
            val restored = api.restoreDocument(settings.serverUrl, settings.apiKey, existing.id)
            dao.upsertDocument(
                restored.toEntity(
                    isStarred = existing.isStarred,
                    starId = existing.starId,
                    treeOrder = existing.treeOrder,
                ),
            )
        }
    }

    suspend fun toggleStar(documentId: String): Result<Boolean> {
        val existing = dao.getDocumentById(documentId)
            ?: return Result.failure(IllegalStateException("文档不存在"))
        val settings = preferences.getSettings()
        if (!settings.isConfigured || !networkMonitor.isOnline.value || existing.id.startsWith("local_")) {
            return Result.failure(IllegalStateException("收藏操作需要联网且文档已同步至云端"))
        }

        val newStarred = !existing.isStarred
        // Optimistic update for snappy UI, with rollback on failure
        dao.upsertDocument(existing.copy(isStarred = newStarred))

        return try {
            if (newStarred) {
                val created = api.createStar(settings.serverUrl, settings.apiKey, existing.id)
                dao.upsertDocument(existing.copy(isStarred = true, starId = created.id))
            } else if (!existing.starId.isNullOrBlank()) {
                api.deleteStar(settings.serverUrl, settings.apiKey, existing.starId)
                dao.upsertDocument(existing.copy(isStarred = false, starId = null))
            } else {
                // Star ID unknown locally: refresh stars from server to stay accurate
                val stars = api.listStars(settings.serverUrl, settings.apiKey)
                val match = stars.stars.firstOrNull { it.documentId == existing.id }
                if (match != null) {
                    api.deleteStar(settings.serverUrl, settings.apiKey, match.id)
                }
                dao.upsertDocument(existing.copy(isStarred = false, starId = null))
            }
            Result.success(newStarred)
        } catch (e: Exception) {
            // Rollback local optimistic state
            dao.upsertDocument(existing)
            SyncDiagnostics.log("STAR", "收藏状态同步失败，已回滚 (${existing.id}): ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun createCollection(name: String, color: String = "#0366D6"): Result<CollectionEntity> {
        val settings = preferences.getSettings()
        if (!settings.isConfigured || !networkMonitor.isOnline.value) {
            return Result.failure(IllegalStateException("创建集合需要连接云端服务器"))
        }
        return runCatching {
            val dto = api.createCollection(settings.serverUrl, settings.apiKey, name, color = color)
            val entity = CollectionEntity(
                id = dto.id,
                name = dto.name,
                description = dto.description.orEmpty(),
                color = dto.color ?: color,
                icon = dto.icon,
                sortIndex = dto.index.orEmpty(),
                updatedAt = dto.updatedAt.orEmpty(),
            )
            dao.upsertCollections(listOf(entity))
            entity
        }
    }

    /**
     * Saves a picked image to app storage, uploads to Outline/MinIO immediately if online,
     * or returns a local offline marker URL that auto-uploads on next sync!
     */
    suspend fun attachImage(documentId: String, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val ext = when {
                mimeType.contains("png") -> "png"
                mimeType.contains("webp") -> "webp"
                mimeType.contains("gif") -> "gif"
                else -> "jpg"
            }
            val displayName = queryFileName(uri) ?: "note_img_${System.currentTimeMillis()}.$ext"
            val attachId = UUID.randomUUID().toString()
            val localFile = File(offlineMediaDir, "${attachId}.$ext")

            context.contentResolver.openInputStream(uri)?.use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("无法读取所选图片")

            val settings = preferences.getSettings()
            val processed = ImageProcessor.processForUpload(
                sourceFile = localFile,
                originalName = displayName,
                originalMime = mimeType,
                enableCompression = settings.compressImagesOnUpload,
            )
            val uploadFile = processed.file
            val finalName = processed.fileName
            val finalMime = processed.contentType

            if (settings.isConfigured && networkMonitor.isOnline.value) {
                val uploaded = runCatching {
                    api.uploadAttachment(
                        serverUrl = settings.serverUrl,
                        apiKey = settings.apiKey,
                        file = uploadFile,
                        fileName = finalName,
                        contentType = finalMime,
                        documentId = documentId,
                    )
                }.onFailure { err ->
                    SyncDiagnostics.log("UPLOAD", "MinIO 上传失败，已转入离线队列: ${err.message}")
                }.getOrNull()

                if (uploaded != null && uploaded.url.isNotBlank()) {
                    api.seedMediaCache(settings.serverUrl, uploaded.url, uploadFile, finalMime)
                    SyncDiagnostics.log("UPLOAD", "图片上传成功: $finalName (${uploadFile.length()} B)")
                    return@runCatching uploaded.url
                }
            }

            // Offline fallback: store pending attachment and insert local URL marker
            val marker = "$OFFLINE_MEDIA_PREFIX${uploadFile.name}"
            dao.upsertPendingAttachment(
                PendingAttachmentEntity(
                    id = attachId,
                    documentId = documentId,
                    localUriMarker = marker,
                    localFilePath = uploadFile.absolutePath,
                    fileName = finalName,
                    contentType = finalMime,
                ),
            )
            SyncWorker.scheduleWhenConnected(context)
            marker
        }
    }

    /**
     * Resolve a conflict in one of three safe ways:
     * KEEP_LOCAL: Force push local version to server
     * ACCEPT_SERVER: Replace local with server version
     * DUPLICATE_LOCAL: Restore server version on original doc, and save local version as a brand-new note
     */
    suspend fun resolveConflict(documentId: String, strategy: ConflictResolution): Result<Unit> {
        val doc = dao.getDocumentById(documentId) ?: return Result.failure(IllegalStateException("文档不存在"))
        val settings = preferences.getSettings()
        return runCatching {
            when (strategy) {
                ConflictResolution.KEEP_LOCAL -> {
                    val updated = api.updateDocument(
                        serverUrl = settings.serverUrl,
                        apiKey = settings.apiKey,
                        documentId = doc.id,
                        title = doc.title,
                        text = doc.text,
                    )
                    dao.upsertDocument(
                        updated.toEntity(
                            isStarred = doc.isStarred,
                            starId = doc.starId,
                            treeOrder = doc.treeOrder,
                        ),
                    )
                }

                ConflictResolution.ACCEPT_SERVER -> {
                    val remote = api.getDocumentInfo(settings.serverUrl, settings.apiKey, doc.id)
                    dao.upsertDocument(
                        remote.toEntity(
                            isStarred = doc.isStarred,
                            starId = doc.starId,
                            treeOrder = doc.treeOrder,
                        ),
                    )
                }

                ConflictResolution.DUPLICATE_LOCAL -> {
                    // Save local copy as a new note
                    createLocalDocument(
                        title = "${doc.title} (本地冲突副本)",
                        text = doc.text,
                        collectionId = doc.collectionId,
                        parentDocumentId = doc.parentDocumentId,
                        isDraft = doc.isDraft,
                    )
                    // Restore original to server version
                    val remote = api.getDocumentInfo(settings.serverUrl, settings.apiKey, doc.id)
                    dao.upsertDocument(
                        remote.toEntity(
                            isStarred = doc.isStarred,
                            starId = doc.starId,
                            treeOrder = doc.treeOrder,
                        ),
                    )
                    syncNow(silent = true)
                }
            }
        }
    }

    suspend fun searchNotes(query: String): List<DocumentEntity> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        // Combine SQLite FTS4 (blazing fast token search) + LIKE (exact CJK substring match)
        val merged = linkedMapOf<String, DocumentEntity>()
        val ftsToken = trimmed.replace(Regex("[\"*:()^-]"), " ").trim()
        if (ftsToken.isNotBlank()) {
            val ftsQuery = ftsToken.split(Regex("\\s+")).joinToString(" ") { "$it*" }
            runCatching { dao.searchLocalDocumentsFts(ftsQuery) }
                .getOrDefault(emptyList())
                .forEach { merged[it.id] = it }
        }
        dao.searchLocalDocumentsLike(trimmed).forEach { merged.putIfAbsent(it.id, it) }

        val settings = preferences.getSettings()
        if (!settings.isConfigured || !networkMonitor.isOnline.value) {
            return merged.values.toList()
        }
        val remoteResults = runCatching {
            api.searchDocuments(settings.serverUrl, settings.apiKey, trimmed)
                .map { it.document.toEntity() }
        }.getOrDefault(emptyList())

        remoteResults.forEach { merged.putIfAbsent(it.id, it) }
        return merged.values.toList()
    }

    fun getMediaCacheSizeBytes(): Long {
        return mediaCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun clearMediaCache() {
        mediaCacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    suspend fun clearAllLocalData() {
        dao.clearPendingAttachments()
        dao.clearDocuments()
        dao.clearCollections()
        clearMediaCache()
        offlineMediaDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun queryFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    companion object {
        const val OFFLINE_MEDIA_HOST = "local.outline.app"
        const val OFFLINE_MEDIA_PREFIX = "https://local.outline.app/offline-media/"
        const val MISSING_DELETE_THRESHOLD = 2

        /**
         * Pure orchestration core for one complete push+pull sync cycle.
         * Separated cleanly so JVM unit tests can exercise conflict/failure/transaction paths against a fake DAO & API.
         */
        internal suspend fun executeSyncCycle(
            serverUrl: String,
            apiKey: String,
            defaultCollectionId: String,
            dao: OutlineDao,
            api: OutlineApiClient,
            onUpdateBranding: suspend (workspaceName: String, logoUrl: String, accentHex: String) -> Unit = { _, _, _ -> },
            onSetDefaultCollection: suspend (collectionId: String) -> Unit = {},
        ): SyncCycleOutcome {
            var pushedCount = 0
            var conflictCount = 0

            // Step 1: Upload any pending offline attachments to Outline/MinIO first
            uploadPendingAttachmentsInternal(serverUrl, apiKey, dao, api)

            // Step 2: Push dirty local documents (NEW, MODIFIED, DELETED)
            val dirtyDocs = dao.getDirtyDocuments()
            val orderedDirty = dirtyDocs.sortedBy { if (it.parentDocumentId == null) 0 else 1 }

            for (localDoc in orderedDirty) {
                val current = dao.getDocumentById(localDoc.id) ?: continue
                when (current.syncState) {
                    SyncState.LOCAL_NEW -> {
                        val created = api.createDocument(
                            serverUrl = serverUrl,
                            apiKey = apiKey,
                            title = current.title,
                            text = current.text,
                            collectionId = current.collectionId,
                            parentDocumentId = current.parentDocumentId?.takeUnless { it.startsWith("local_") },
                            publish = !current.isDraft,
                        )
                        val syncedEntity = created.toEntity(
                            isStarred = current.isStarred,
                            starId = current.starId,
                            treeOrder = current.treeOrder,
                        )
                        dao.applyCreatedDocument(current.id, syncedEntity)
                        pushedCount++
                    }

                    SyncState.LOCAL_MODIFIED -> {
                        // Pre-flight conflict check. Outline's documents.update does not accept an optimistic-lock
                        // revision parameter, so we pre-check revision/updatedAt and log any post-update revision jump.
                        val remoteCheck = runCatching {
                            api.getDocumentInfo(serverUrl, apiKey, current.id)
                        }.onFailure { err ->
                            SyncDiagnostics.log("SYNC", "预检远端版本失败 (${current.id}): ${err.message}")
                        }.getOrNull()

                        val hasRemoteDiverged = remoteCheck != null && hasConflict(current, remoteCheck)
                        if (hasRemoteDiverged && remoteCheck != null) {
                            dao.upsertDocument(
                                current.copy(
                                    syncState = SyncState.CONFLICT,
                                    conflictServerTitle = remoteCheck.title,
                                    conflictServerText = remoteCheck.text,
                                    conflictServerUpdatedAt = remoteCheck.updatedAt,
                                ),
                            )
                            conflictCount++
                        } else {
                            val updated = api.updateDocument(
                                serverUrl = serverUrl,
                                apiKey = apiKey,
                                documentId = current.id,
                                title = current.title,
                                text = current.text,
                                publish = if (!current.isDraft) true else null,
                            )
                            if (current.serverBaseRevision > 0 && updated.revision > current.serverBaseRevision + 1) {
                                SyncDiagnostics.log(
                                    "CONFLICT_WARN",
                                    "文档 ${current.id} 提交时检测到版本号跳跃 (${current.serverBaseRevision} -> ${updated.revision})，请按需在网页端历史记录核对",
                                )
                            }
                            dao.upsertDocument(
                                updated.toEntity(
                                    isStarred = current.isStarred,
                                    starId = current.starId,
                                    treeOrder = current.treeOrder,
                                ),
                            )
                            pushedCount++
                        }
                    }

                    SyncState.LOCAL_DELETED -> {
                        if (current.id.startsWith("local_")) {
                            dao.deleteDocumentById(current.id)
                            pushedCount++
                        } else {
                            try {
                                api.deleteDocument(serverUrl, apiKey, current.id)
                                dao.deleteDocumentById(current.id)
                                pushedCount++
                            } catch (err: Exception) {
                                if (err is OutlineApiException && err.statusCode == 404) {
                                    dao.deleteDocumentById(current.id)
                                    pushedCount++
                                } else {
                                    SyncDiagnostics.log("SYNC", "远端删除失败，保留本地待重试 (${current.id}): ${err.message}")
                                }
                            }
                        }
                    }
                }
            }

            // Step 2.5: Sync workspace branding (non-critical)
            runCatching {
                val auth = api.authInfo(serverUrl, apiKey)
                val team = auth.team
                if (team != null) {
                    val logoUrl = team.avatarUrl.orEmpty()
                    val accentHex = team.preferences?.customTheme?.accent.orEmpty()
                    onUpdateBranding(team.name, logoUrl, accentHex)
                    if (logoUrl.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            api.fetchAndCacheMediaBlocking(serverUrl, apiKey, logoUrl)
                        }
                    }
                }
            }.onFailure { err ->
                SyncDiagnostics.log("BRAND", "同步工作区品牌信息跳过: ${err.message}")
            }

            // Step 3: Pull Collections atomically
            val remoteCollections = api.listCollections(serverUrl, apiKey)
            val collectionEntities = remoteCollections.mapIndexed { idx, col ->
                CollectionEntity(
                    id = col.id,
                    name = col.name,
                    description = col.description.orEmpty(),
                    color = col.color ?: "#0366D6",
                    icon = col.icon,
                    sortIndex = col.index ?: idx.toString().padStart(4, '0'),
                    updatedAt = col.updatedAt.orEmpty(),
                )
            }
            dao.applyCollectionsSnapshot(collectionEntities)
            if (collectionEntities.isNotEmpty() && defaultCollectionId.isBlank()) {
                onSetDefaultCollection(collectionEntities.first().id)
            }

            // Step 4: Pull document hierarchy order for each collection (ordering only, non-destructive)
            val treeOrderMap = mutableMapOf<String, Int>()
            val parentFromTreeMap = mutableMapOf<String, String?>()
            for (col in remoteCollections) {
                val nodes = runCatching {
                    api.collectionDocumentsTree(serverUrl, apiKey, col.id)
                }.onFailure { err ->
                    SyncDiagnostics.log("TREE", "拉取集合目录树失败 (${col.id}): ${err.message}")
                }.getOrDefault(emptyList())
                flattenTreeOrder(nodes, null, treeOrderMap, parentFromTreeMap)
            }

            // Step 5: Pull Stars (non-destructive if stars fails: preserve existing star flags)
            val starsResult = runCatching { api.listStars(serverUrl, apiKey) }
                .onFailure { err -> SyncDiagnostics.log("STARS", "拉取收藏列表失败: ${err.message}") }
            val starsData = starsResult.getOrNull()
            val starByDocId = starsData?.stars
                ?.filter { !it.documentId.isNullOrBlank() }
                ?.associateBy { it.documentId!! }

            // Step 6: Pull ALL document buckets strictly — any failure aborts before local pruning (P0-1)!
            val remoteDocs = api.listAllDocuments(serverUrl, apiKey)
            val remoteDrafts = api.listDrafts(serverUrl, apiKey)
            val remoteArchived = api.listArchivedDocuments(serverUrl, apiKey)
            val remoteDeleted = api.listDeletedDocuments(serverUrl, apiKey)

            val mergedRemoteMap = linkedMapOf<String, DocumentDto>()
            remoteDocs.forEach { mergedRemoteMap[it.id] = it }
            remoteDrafts.forEach { mergedRemoteMap.putIfAbsent(it.id, it) }
            starsData?.documents?.forEach { mergedRemoteMap.putIfAbsent(it.id, it) }
            remoteArchived.forEach { archivedDoc ->
                val normalized = if (archivedDoc.archivedAt.isNullOrBlank()) {
                    archivedDoc.copy(archivedAt = archivedDoc.updatedAt ?: Instant.now().toString())
                } else {
                    archivedDoc
                }
                mergedRemoteMap[normalized.id] = normalized
            }
            remoteDeleted.forEach { deletedDoc ->
                val normalized = if (deletedDoc.deletedAt.isNullOrBlank()) {
                    deletedDoc.copy(deletedAt = deletedDoc.updatedAt ?: Instant.now().toString())
                } else {
                    deletedDoc
                }
                mergedRemoteMap[normalized.id] = normalized
            }

            val existingLocalMap = dao.getAllDocuments().associateBy { it.id }
            val toUpsert = mutableListOf<DocumentEntity>()

            for ((docId, remote) in mergedRemoteMap) {
                val local = existingLocalMap[docId]
                val isStarred = if (starByDocId != null) starByDocId.containsKey(docId) else (local?.isStarred ?: false)
                val starId = if (starByDocId != null) starByDocId[docId]?.id else local?.starId
                val order = treeOrderMap[docId] ?: local?.treeOrder ?: 9999
                val treeParent = parentFromTreeMap[docId] ?: remote.parentDocumentId

                if (local != null && local.syncState != SyncState.SYNCED) {
                    toUpsert.add(
                        local.copy(
                            isStarred = isStarred,
                            starId = starId,
                            isArchived = !remote.archivedAt.isNullOrBlank(),
                            archivedAt = remote.archivedAt,
                            isDeleted = !remote.deletedAt.isNullOrBlank(),
                            deletedAt = remote.deletedAt,
                            treeOrder = order,
                            remoteMissingCount = 0,
                        ),
                    )
                } else {
                    toUpsert.add(
                        remote.toEntity(
                            isStarred = isStarred,
                            starId = starId,
                            treeOrder = order,
                            overrideParentId = treeParent,
                        ),
                    )
                }
            }

            // Two-phase pruning: only delete a SYNCED local doc after it is missing on 2 consecutive full syncs
            val remoteIds = mergedRemoteMap.keys
            val toMarkMissing = mutableListOf<String>()
            val toDelete = mutableListOf<String>()
            for ((localId, localDoc) in existingLocalMap) {
                if (localDoc.syncState == SyncState.SYNCED && localId !in remoteIds) {
                    if (localDoc.remoteMissingCount + 1 >= MISSING_DELETE_THRESHOLD) {
                        SyncDiagnostics.log("SYNC", "远端连续 $MISSING_DELETE_THRESHOLD 次缺失，清理本地缓存文档 $localId")
                        toDelete.add(localId)
                    } else {
                        toMarkMissing.add(localId)
                    }
                }
            }

            dao.applyPullBatch(
                toUpsert = toUpsert,
                toMarkMissing = toMarkMissing,
                toDelete = toDelete,
            )

            SyncDiagnostics.log(
                "SYNC",
                "同步成功: pushed=$pushedCount, pulled=${toUpsert.size}, stars=${starsData?.stars?.size ?: -1}, archived=${remoteArchived.size}, trash=${remoteDeleted.size}, markedMissing=${toMarkMissing.size}, pruned=${toDelete.size}, conflicts=$conflictCount",
            )
            return SyncCycleOutcome(pushed = pushedCount, pulled = toUpsert.size, conflicts = conflictCount)
        }

        private suspend fun uploadPendingAttachmentsInternal(
            serverUrl: String,
            apiKey: String,
            dao: OutlineDao,
            api: OutlineApiClient,
        ) {
            val pendingList = dao.getAllPendingAttachments()
            for (pending in pendingList) {
                val file = File(pending.localFilePath)
                if (!file.exists()) {
                    dao.deletePendingAttachment(pending.id)
                    continue
                }
                val uploaded = api.uploadAttachment(
                    serverUrl = serverUrl,
                    apiKey = apiKey,
                    file = file,
                    fileName = pending.fileName,
                    contentType = pending.contentType,
                    documentId = pending.documentId,
                )
                api.seedMediaCache(serverUrl, uploaded.url, file, pending.contentType)

                val doc = dao.getDocumentById(pending.documentId)
                if (doc != null && doc.text.contains(pending.localUriMarker)) {
                    val updatedText = doc.text.replace(pending.localUriMarker, uploaded.url)
                    val nextState = if (doc.syncState == SyncState.SYNCED) {
                        SyncState.LOCAL_MODIFIED
                    } else {
                        doc.syncState
                    }
                    dao.upsertDocument(
                        doc.copy(
                            text = updatedText,
                            localUpdatedAt = System.currentTimeMillis(),
                            syncState = nextState,
                        ),
                    )
                }
                dao.deletePendingAttachment(pending.id)
            }
        }

        private fun flattenTreeOrder(
            nodes: List<NavigationNodeDto>,
            parentId: String?,
            orderOut: MutableMap<String, Int>,
            parentOut: MutableMap<String, String?>,
        ) {
            for (node in nodes) {
                orderOut[node.id] = orderOut.size
                parentOut[node.id] = parentId
                if (node.children.isNotEmpty()) {
                    flattenTreeOrder(node.children, node.id, orderOut, parentOut)
                }
            }
        }

        internal fun extractOutlineDocIdentifier(rawUrl: String, serverUrl: String): String? {
            val trimmed = rawUrl.trim()
            val path = when {
                trimmed.startsWith("/doc/") -> trimmed
                trimmed.startsWith("https://appassets.androidplatform.net/doc/") ->
                    trimmed.removePrefix("https://appassets.androidplatform.net")
                serverUrl.isNotBlank() && trimmed.startsWith(serverUrl.trimEnd('/') + "/doc/") ->
                    trimmed.removePrefix(serverUrl.trimEnd('/'))
                else -> return null
            }
            val afterDoc = path.removePrefix("/doc/").substringBefore('?').substringBefore('#').trim('/')
            return afterDoc.takeIf { it.isNotBlank() }
        }

        internal fun hasConflict(local: DocumentEntity, remote: DocumentDto): Boolean {
            if (local.serverBaseRevision > 0 && remote.revision > local.serverBaseRevision) {
                return remote.text != local.text || remote.title != local.title
            }
            if (local.serverBaseUpdatedAt.isNotBlank() &&
                !remote.updatedAt.isNullOrBlank() &&
                remote.updatedAt != local.serverBaseUpdatedAt
            ) {
                return remote.text != local.text || remote.title != local.title
            }
            return false
        }
    }
}

enum class ConflictResolution {
    KEEP_LOCAL,
    ACCEPT_SERVER,
    DUPLICATE_LOCAL,
}

internal data class SyncCycleOutcome(
    val pushed: Int,
    val pulled: Int,
    val conflicts: Int,
)

private fun DocumentDto.toEntity(
    isStarred: Boolean = false,
    starId: String? = null,
    treeOrder: Int = 9999,
    overrideParentId: String? = parentDocumentId,
): DocumentEntity {
    val parsedMillis = runCatching {
        if (!updatedAt.isNullOrBlank()) Instant.parse(updatedAt).toEpochMilli() else System.currentTimeMillis()
    }.getOrDefault(System.currentTimeMillis())

    return DocumentEntity(
        id = id,
        urlId = urlId,
        collectionId = collectionId,
        parentDocumentId = overrideParentId,
        title = title,
        text = text,
        emoji = emoji ?: icon,
        isDraft = publishedAt.isNullOrBlank(),
        isArchived = !archivedAt.isNullOrBlank(),
        archivedAt = archivedAt,
        isDeleted = !deletedAt.isNullOrBlank(),
        deletedAt = deletedAt,
        isStarred = isStarred,
        starId = starId,
        treeOrder = treeOrder,
        revision = revision,
        updatedAt = updatedAt.orEmpty(),
        localUpdatedAt = parsedMillis,
        syncState = SyncState.SYNCED,
        serverBaseRevision = revision,
        serverBaseUpdatedAt = updatedAt.orEmpty(),
    )
}
