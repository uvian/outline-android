package com.outline.notes.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.outline.notes.data.local.CollectionEntity
import com.outline.notes.data.local.DocumentEntity
import com.outline.notes.data.local.SyncState
import com.outline.notes.data.local.WorkspaceFilter
import com.outline.notes.data.local.WorkspaceStore
import com.outline.notes.data.prefs.UserSettings
import com.outline.notes.data.remote.OutlineApiClient
import com.outline.notes.data.sync.NetworkMonitor
import com.outline.notes.data.sync.SyncEngine
import com.outline.notes.data.sync.SyncResult
import com.outline.notes.ui.components.UiFormatters
import com.outline.notes.ui.components.WorkspaceLogo
import com.outline.notes.ui.theme.OutlineColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: UserSettings,
    workspaceStore: WorkspaceStore,
    apiClient: OutlineApiClient,
    syncEngine: SyncEngine,
    networkMonitor: NetworkMonitor,
    onOpenDocument: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    val collections by workspaceStore.collections.collectAsState()
    val allActiveDocs by workspaceStore.allActiveDocs.collectAsState()
    val recentDocs by workspaceStore.recentDocs.collectAsState()
    val starredDocs by workspaceStore.starredDocs.collectAsState()
    val draftDocs by workspaceStore.draftDocs.collectAsState()
    val archivedDocs by workspaceStore.archivedDocs.collectAsState()
    val deletedDocs by workspaceStore.deletedDocs.collectAsState()
    val unsyncedDocs by workspaceStore.unsyncedDocs.collectAsState()
    val pendingCount by workspaceStore.pendingCount.collectAsState()
    val conflictCount by workspaceStore.conflictCount.collectAsState()

    val syncStatus by syncEngine.syncStatus.collectAsState()
    val isOnline by networkMonitor.isOnline.collectAsState()

    val currentFilter by workspaceStore.currentFilter.collectAsState()
    val expandedCollections = remember { mutableStateMapOf<String, Boolean>() }
    val expandedDocs = remember { mutableStateMapOf<String, Boolean>() }
    var showCreateCollectionDialog by remember { mutableStateOf(false) }

    val collectionsById = remember(collections) { collections.associateBy { it.id } }

    // Trigger silent sync once on cold launch (or if stale > 5 minutes), NOT on every back navigation
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        val stale = now - settings.lastSyncAt > 5 * 60 * 1000L
        if (isOnline && settings.isConfigured && (!workspaceStore.hasTriggeredInitialSync || stale)) {
            workspaceStore.hasTriggeredInitialSync = true
            syncEngine.syncNow(silent = true)
        }
    }

    val displayedDocs = remember(currentFilter, recentDocs, starredDocs, draftDocs, archivedDocs, deletedDocs, unsyncedDocs, allActiveDocs) {
        when (val f = currentFilter) {
            WorkspaceFilter.Recent -> recentDocs
            WorkspaceFilter.Starred -> starredDocs
            WorkspaceFilter.Drafts -> draftDocs
            WorkspaceFilter.Archived -> archivedDocs
            WorkspaceFilter.Trash -> deletedDocs
            WorkspaceFilter.Unsynced -> unsyncedDocs
            is WorkspaceFilter.Collection -> allActiveDocs
                .filter { it.collectionId == f.id && !it.isDraft && !it.isArchived && !it.isDeleted }
                .sortedWith(compareBy<DocumentEntity> { it.treeOrder }.thenBy { it.title })
        }
    }

    val topBarTitle = when (val f = currentFilter) {
        WorkspaceFilter.Recent -> "最近更新"
        WorkspaceFilter.Starred -> "收藏夹"
        WorkspaceFilter.Drafts -> "草稿箱"
        WorkspaceFilter.Archived -> "归档"
        WorkspaceFilter.Trash -> "回收站"
        WorkspaceFilter.Unsynced -> "待同步与冲突"
        is WorkspaceFilter.Collection -> f.name
    }

    val activeCollectionId = (currentFilter as? WorkspaceFilter.Collection)?.id

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.width(304.dp),
            ) {
                OutlineSidebarContent(
                    settings = settings,
                    apiClient = apiClient,
                    isOnline = isOnline,
                    isSyncing = syncStatus.isSyncing,
                    starredCount = starredDocs.size,
                    draftCount = draftDocs.size,
                    archivedCount = archivedDocs.size,
                    trashCount = deletedDocs.size,
                    pendingCount = pendingCount,
                    conflictCount = conflictCount,
                    collections = collections,
                    allDocs = allActiveDocs,
                    currentFilter = currentFilter,
                    expandedCollections = expandedCollections,
                    expandedDocs = expandedDocs,
                    onSelectFilter = { filter ->
                        workspaceStore.setFilter(filter)
                        scope.launch { drawerState.close() }
                    },
                    onOpenDocument = { docId ->
                        scope.launch { drawerState.close() }
                        onOpenDocument(docId)
                    },
                    onCreateDocInCollection = { colId ->
                        scope.launch {
                            drawerState.close()
                            val doc = syncEngine.createLocalDocument(collectionId = colId)
                            onOpenDocument(doc.id)
                        }
                    },
                    onCreateCollectionClick = { showCreateCollectionDialog = true },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                )
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, contentDescription = "打开侧边栏")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = topBarTitle,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtitle = when {
                                syncStatus.isSyncing -> "正在同步云端..."
                                !isOnline && pendingCount > 0 -> "离线模式 · $pendingCount 项待上传"
                                !isOnline -> "离线模式 · 已缓存本地笔记"
                                conflictCount > 0 -> "$conflictCount 篇笔记存在版本冲突"
                                pendingCount > 0 -> "$pendingCount 项修改等待上传"
                                else -> settings.workspaceName.ifBlank { "Outline" }
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (!isOnline || conflictCount > 0) {
                                    OutlineColors.WarningLight
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Outlined.Search, contentDescription = "搜索笔记")
                        }
                        SyncActionButton(
                            isSyncing = syncStatus.isSyncing,
                            onClick = {
                                scope.launch {
                                    val result = syncEngine.syncNow(silent = false)
                                    val msg = when (result) {
                                        is SyncResult.Success ->
                                            if (result.conflicts > 0) "同步完成，有 ${result.conflicts} 篇冲突笔记"
                                            else "同步完成（共 ${result.pulled} 篇笔记）"
                                        is SyncResult.SkippedOffline -> "当前无网络，联网后将自动同步"
                                        is SyncResult.Error -> result.message
                                    }
                                    snackbarHostState.showSnackbar(msg)
                                }
                            },
                        )
                    },
                )
            },
            floatingActionButton = {
                if (currentFilter != WorkspaceFilter.Trash && currentFilter != WorkspaceFilter.Archived) {
                    FloatingActionButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scope.launch {
                                val newDoc = syncEngine.createLocalDocument(
                                    collectionId = activeCollectionId,
                                    isDraft = currentFilter == WorkspaceFilter.Drafts,
                                )
                                onOpenDocument(newDoc.id)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建笔记")
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.8.dp)

                // Offline or conflict banner
                AnimatedVisibility(visible = !isOnline || conflictCount > 0) {
                    val isConflictBanner = conflictCount > 0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isConflictBanner) {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                },
                            )
                            .clickable {
                                if (isConflictBanner) workspaceStore.setFilter(WorkspaceFilter.Unsynced)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isConflictBanner) Icons.Outlined.WarningAmber else Icons.Outlined.CloudOff,
                            contentDescription = null,
                            tint = if (isConflictBanner) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isConflictBanner) {
                                "发现 $conflictCount 篇笔记在云端和本地同时被修改，点击查看处理"
                            } else {
                                "离线模式：所有已缓存笔记可随时浏览与编辑，联网后自动同步"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // Keep natural pull-down gesture to trigger sync, without the obtrusive top-center spinner
                PullToRefreshBox(
                    isRefreshing = syncStatus.isSyncing,
                    onRefresh = {
                        scope.launch { syncEngine.syncNow(silent = false) }
                    },
                    indicator = {},
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (displayedDocs.isEmpty()) {
                        EmptyNotesState(
                            title = when (currentFilter) {
                                WorkspaceFilter.Starred -> "还没有收藏的笔记"
                                WorkspaceFilter.Drafts -> "草稿箱为空"
                                WorkspaceFilter.Archived -> "没有已归档的文档"
                                WorkspaceFilter.Trash -> "回收站为空"
                                WorkspaceFilter.Unsynced -> "全部笔记已同步"
                                else -> "这里还没有笔记"
                            },
                            subtitle = when (currentFilter) {
                                WorkspaceFilter.Archived -> "在云端或手机端归档的笔记会显示在这里，离线也可查阅。"
                                WorkspaceFilter.Trash -> "删除的笔记会暂存在这里，打开后可随时恢复或彻底删除。"
                                else -> "点击右下角 + 按钮开始记录，离线状态下也会自动缓存到本地。"
                            },
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 12.dp,
                                bottom = 92.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(
                                items = displayedDocs,
                                key = { it.id },
                                contentType = { "note_card" },
                            ) { doc ->
                                NoteCardItem(
                                    doc = doc,
                                    collection = doc.collectionId?.let { collectionsById[it] },
                                    onClick = { onOpenDocument(doc.id) },
                                    onToggleStar = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        scope.launch { syncEngine.toggleStar(doc.id) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateCollectionDialog) {
        CreateCollectionDialog(
            onDismiss = { showCreateCollectionDialog = false },
            onConfirm = { name ->
                showCreateCollectionDialog = false
                scope.launch {
                    val res = syncEngine.createCollection(name)
                    if (res.isSuccess) {
                        val created = res.getOrThrow()
                        workspaceStore.setFilter(WorkspaceFilter.Collection(created.id, created.name))
                        snackbarHostState.showSnackbar("已创建集合「${created.name}」")
                    } else {
                        snackbarHostState.showSnackbar(res.exceptionOrNull()?.message ?: "创建失败")
                    }
                }
            },
        )
    }
}

@Composable
private fun SyncActionButton(
    isSyncing: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        if (isSyncing) {
            val infiniteTransition = rememberInfiniteTransition(label = "syncSpin")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "syncRotation",
            )
            Icon(
                imageVector = Icons.Outlined.Sync,
                contentDescription = "正在同步",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Sync,
                contentDescription = "立即同步",
            )
        }
    }
}

@Composable
private fun NoteCardItem(
    doc: DocumentEntity,
    collection: CollectionEntity?,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
) {
    val snippet = remember(doc.text) { UiFormatters.stripMarkdownSnippet(doc.text, 110) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = if (doc.syncState == SyncState.CONFLICT) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = doc.title.ifBlank { "无标题笔记" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(
                    onClick = onToggleStar,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (doc.isStarred) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                        contentDescription = "收藏",
                        tint = if (doc.isStarred) Color(0xFFF5A623) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (collection != null) {
                    val colColor = UiFormatters.parseHexColor(collection.color)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colColor.copy(alpha = 0.12f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colColor),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = collection.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = colColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (doc.isDraft) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "草稿",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                if (doc.isDeleted) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "回收站",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (doc.isArchived) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "已归档",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                // Sync status chip when not synced
                when (doc.syncState) {
                    SyncState.LOCAL_NEW, SyncState.LOCAL_MODIFIED -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "待同步",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    SyncState.CONFLICT -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "有冲突",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = UiFormatters.relativeTime(doc.localUpdatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun OutlineSidebarContent(
    settings: UserSettings,
    apiClient: OutlineApiClient,
    isOnline: Boolean,
    isSyncing: Boolean,
    starredCount: Int,
    draftCount: Int,
    archivedCount: Int,
    trashCount: Int,
    pendingCount: Int,
    conflictCount: Int,
    collections: List<CollectionEntity>,
    allDocs: List<DocumentEntity>,
    currentFilter: WorkspaceFilter,
    expandedCollections: MutableMap<String, Boolean>,
    expandedDocs: MutableMap<String, Boolean>,
    onSelectFilter: (WorkspaceFilter) -> Unit,
    onOpenDocument: (String) -> Unit,
    onCreateDocInCollection: (String) -> Unit,
    onCreateCollectionClick: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val docsByCollection = remember(allDocs) {
        allDocs.filter { !it.isDraft && !it.isArchived && it.collectionId != null }
            .groupBy { it.collectionId!! }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .padding(vertical = 12.dp),
    ) {
        // Workspace header with synced server Logo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceLogo(
                serverUrl = settings.serverUrl,
                apiKey = settings.apiKey,
                logoUrl = settings.workspaceLogoUrl,
                apiClient = apiClient,
                size = 36.dp,
                cornerRadius = 10.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = settings.workspaceName.ifBlank { "Outline Notes" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) OutlineColors.SuccessLight else OutlineColors.WarningLight),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = when {
                            isSyncing -> "正在同步..."
                            !isOnline -> "离线模式"
                            pendingCount > 0 -> "在线 · $pendingCount 项待上传"
                            else -> "已连接云端"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            item {
                SidebarNavItem(
                    icon = Icons.Outlined.Home,
                    label = "最近更新",
                    selected = currentFilter == WorkspaceFilter.Recent,
                    onClick = { onSelectFilter(WorkspaceFilter.Recent) },
                )
                SidebarNavItem(
                    icon = Icons.Outlined.StarOutline,
                    label = "收藏夹",
                    badgeCount = starredCount,
                    selected = currentFilter == WorkspaceFilter.Starred,
                    onClick = { onSelectFilter(WorkspaceFilter.Starred) },
                )
                SidebarNavItem(
                    icon = Icons.Outlined.EditNote,
                    label = "草稿箱",
                    badgeCount = draftCount,
                    selected = currentFilter == WorkspaceFilter.Drafts,
                    onClick = { onSelectFilter(WorkspaceFilter.Drafts) },
                )
                SidebarNavItem(
                    icon = Icons.Outlined.Archive,
                    label = "归档",
                    badgeCount = archivedCount,
                    selected = currentFilter == WorkspaceFilter.Archived,
                    onClick = { onSelectFilter(WorkspaceFilter.Archived) },
                )
                SidebarNavItem(
                    icon = Icons.Outlined.DeleteOutline,
                    label = "回收站",
                    badgeCount = trashCount,
                    selected = currentFilter == WorkspaceFilter.Trash,
                    onClick = { onSelectFilter(WorkspaceFilter.Trash) },
                )
                if (pendingCount > 0 || conflictCount > 0) {
                    SidebarNavItem(
                        icon = if (conflictCount > 0) Icons.Outlined.WarningAmber else Icons.Outlined.CloudQueue,
                        label = if (conflictCount > 0) "同步与冲突" else "待同步",
                        badgeCount = if (conflictCount > 0) conflictCount else pendingCount,
                        selected = currentFilter == WorkspaceFilter.Unsynced,
                        accentTint = if (conflictCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        onClick = { onSelectFilter(WorkspaceFilter.Unsynced) },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "集合 (COLLECTIONS)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onCreateCollectionClick,
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CreateNewFolder,
                            contentDescription = "新建集合",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            items(collections, key = { it.id }) { col ->
                val colDocs = docsByCollection[col.id].orEmpty()
                val isExpanded = expandedCollections[col.id] == true
                val isSelected = (currentFilter as? WorkspaceFilter.Collection)?.id == col.id
                val colColor = UiFormatters.parseHexColor(col.color)

                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                else Color.Transparent,
                            )
                            .clickable { onSelectFilter(WorkspaceFilter.Collection(col.id, col.name)) }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                            contentDescription = "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .clickable { expandedCollections[col.id] = !isExpanded }
                                .padding(2.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = colColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = col.name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onCreateDocInCollection(col.id) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "在此集合新建笔记",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    if (isExpanded) {
                        val childrenByParent = remember(colDocs) {
                            colDocs.groupBy { it.parentDocumentId }
                        }
                        val roots = childrenByParent[null].orEmpty()
                            .sortedWith(compareBy<DocumentEntity> { it.treeOrder }.thenBy { it.title })

                        DocumentTreeBranch(
                            nodes = roots,
                            childrenByParent = childrenByParent,
                            depth = 1,
                            expandedDocs = expandedDocs,
                            onOpenDocument = onOpenDocument,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "设置与同步管理",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (isOnline) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DocumentTreeBranch(
    nodes: List<DocumentEntity>,
    childrenByParent: Map<String?, List<DocumentEntity>>,
    depth: Int,
    expandedDocs: MutableMap<String, Boolean>,
    onOpenDocument: (String) -> Unit,
) {
    val cappedDepth = depth.coerceAtMost(4)
    for (node in nodes) {
        val children = childrenByParent[node.id].orEmpty()
            .sortedWith(compareBy<DocumentEntity> { it.treeOrder }.thenBy { it.title })
        val hasChildren = children.isNotEmpty()
        val isExpanded = expandedDocs[node.id] == true

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onOpenDocument(node.id) }
                .padding(start = (14 + cappedDepth * 14).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasChildren) {
                Icon(
                    imageVector = if (isExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                    contentDescription = "展开子文档",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { expandedDocs[node.id] = !isExpanded },
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = node.title.ifBlank { "无标题笔记" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (hasChildren && isExpanded) {
            DocumentTreeBranch(
                nodes = children,
                childrenByParent = childrenByParent,
                depth = depth + 1,
                expandedDocs = expandedDocs,
                onOpenDocument = onOpenDocument,
            )
        }
    }
}

@Composable
private fun SidebarNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badgeCount: Int = 0,
    accentTint: Color? = null,
    onClick: () -> Unit,
) {
    val fg = when {
        accentTint != null -> accentTint
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = fg,
            modifier = Modifier.weight(1f),
        )
        if (badgeCount > 0) {
            Text(
                text = badgeCount.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyNotesState(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(56.dp),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CreateCollectionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建集合") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("集合名称") },
                placeholder = { Text("例如：个人笔记 / 读书摘录") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
