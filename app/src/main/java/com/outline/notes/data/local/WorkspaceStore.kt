package com.outline.notes.data.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

sealed class WorkspaceFilter {
    data object Recent : WorkspaceFilter()
    data object Starred : WorkspaceFilter()
    data object Drafts : WorkspaceFilter()
    data object Archived : WorkspaceFilter()
    data object Trash : WorkspaceFilter()
    data object Unsynced : WorkspaceFilter()
    data class Collection(val id: String, val name: String) : WorkspaceFilter()
}

/**
 * Application-scoped hot StateFlow store backed by Room.
 * Guarantees instant frame-0 rendering on screen transitions (zero empty-list flashes
 * and zero query cancellations during recompositions or sync animations).
 */
class WorkspaceStore(
    dao: OutlineDao,
    scope: CoroutineScope,
) {
    val collections: StateFlow<List<CollectionEntity>> = dao.observeCollections()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val allActiveDocs: StateFlow<List<DocumentEntity>> = dao.observeAllActiveDocuments()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val recentDocs: StateFlow<List<DocumentEntity>> = dao.observeRecentDocuments()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val starredDocs: StateFlow<List<DocumentEntity>> = dao.observeStarredDocuments()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val draftDocs: StateFlow<List<DocumentEntity>> = dao.observeDraftDocuments()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val archivedDocs: StateFlow<List<DocumentEntity>> = dao.observeArchivedDocuments()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val deletedDocs: StateFlow<List<DocumentEntity>> = dao.observeDeletedDocuments()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val unsyncedDocs: StateFlow<List<DocumentEntity>> = dao.observeUnsyncedDocuments()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pendingCount: StateFlow<Int> = dao.observePendingSyncCount()
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val conflictCount: StateFlow<Int> = dao.observeConflictCount()
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private val _currentFilter = MutableStateFlow<WorkspaceFilter>(WorkspaceFilter.Recent)
    val currentFilter: StateFlow<WorkspaceFilter> = _currentFilter.asStateFlow()

    @Volatile
    var hasTriggeredInitialSync: Boolean = false

    fun setFilter(filter: WorkspaceFilter) {
        _currentFilter.value = filter
    }

    fun resetOnDisconnect() {
        _currentFilter.value = WorkspaceFilter.Recent
        hasTriggeredInitialSync = false
    }
}
