package com.outline.notes.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.outline.notes.OutlineApp
import com.outline.notes.data.local.DocumentEntity
import com.outline.notes.data.local.OutlineDao
import com.outline.notes.data.local.SyncState
import com.outline.notes.data.prefs.EditorMode
import com.outline.notes.data.prefs.ThemeMode
import com.outline.notes.data.prefs.UserSettings
import com.outline.notes.data.sync.ConflictResolution
import com.outline.notes.data.sync.NetworkMonitor
import com.outline.notes.data.sync.SyncEngine
import com.outline.notes.ui.components.EditorSelectionState
import com.outline.notes.ui.components.ImageLightboxDialog
import com.outline.notes.ui.components.MoveDocumentDialog
import com.outline.notes.ui.components.OutlineRichEditorView
import com.outline.notes.ui.components.RichEditorController
import com.outline.notes.ui.components.TocBottomSheet
import com.outline.notes.ui.components.UiFormatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    documentId: String,
    app: OutlineApp,
    settings: UserSettings,
    dao: OutlineDao,
    syncEngine: SyncEngine,
    networkMonitor: NetworkMonitor,
    onBack: () -> Unit,
    onOpenDocument: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var activeDocId by remember(documentId) { mutableStateOf(syncEngine.resolveDocumentId(documentId)) }
    val docFlow = remember(activeDocId) { dao.observeDocumentById(activeDocId) }
    val doc by docFlow.collectAsState(initial = null)
    val collections by app.workspaceStore.collections.collectAsState()
    val allActiveDocs by app.workspaceStore.allActiveDocs.collectAsState()
    val isOnline by networkMonitor.isOnline.collectAsState()

    val isDark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    var editorMode by remember { mutableStateOf(settings.defaultEditorMode) }
    var initializedDocId by remember { mutableStateOf<String?>(null) }
    var titleText by remember { mutableStateOf("") }
    var bodyMarkdown by remember { mutableStateOf("") }
    var hasUnsavedInMemory by remember { mutableStateOf(false) }
    var didModifyInSession by remember { mutableStateOf(false) }
    var typingHeartbeat by remember { mutableIntStateOf(0) }
    var isPushingDoc by remember { mutableStateOf(false) }
    var isReadOnly by remember { mutableStateOf(settings.openInReadMode) }

    var showMoreMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showPermDeleteConfirm by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var showTocSheet by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showImageSourceDialog by remember { mutableStateOf(false) }
    var showCalloutPicker by remember { mutableStateOf(false) }
    var lightboxImageSrc by remember { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var isUploadingImage by remember { mutableStateOf(false) }

    val richController = remember(documentId) { RichEditorController(app.editorWebViewPool) }
    val selectionState by richController.selectionState.collectAsState()
    val headings by richController.headings.collectAsState()

    // Shield the actively edited document from full background sync races while typing or holding unsynced changes
    val shouldShieldFromBackgroundSync = !isReadOnly || didModifyInSession || hasUnsavedInMemory
    DisposableEffect(activeDocId, shouldShieldFromBackgroundSync) {
        syncEngine.setActiveEditingDocument(if (shouldShieldFromBackgroundSync) activeDocId else null)
        onDispose {
            syncEngine.setActiveEditingDocument(null)
        }
    }

    // Hydrate local state once when the document loads (skipped when local_xxx is promoted to server UUID)
    LaunchedEffect(doc?.id) {
        val loaded = doc ?: return@LaunchedEffect
        if (initializedDocId != loaded.id) {
            initializedDocId = loaded.id
            titleText = loaded.title
            bodyMarkdown = loaded.text
            // Brand-new empty notes open directly in Edit mode; deleted/archived or configured existing notes open in Read mode
            val isNewEmpty = loaded.title.isBlank() && loaded.text.isBlank()
            if (loaded.syncState == SyncState.LOCAL_NEW && !isNewEmpty) {
                didModifyInSession = true
            }
            isReadOnly = when {
                loaded.isDeleted -> true
                isNewEmpty -> false
                else -> settings.openInReadMode
            }
            richController.setReadOnly(isReadOnly)
            richController.loadDocument(loaded.text)
        }
    }

    // Debounced local auto-save to Room DB only (zero WorkManager spam, zero network contention while typing)
    LaunchedEffect(titleText, bodyMarkdown, activeDocId) {
        val currentId = initializedDocId ?: return@LaunchedEffect
        if (!hasUnsavedInMemory) return@LaunchedEffect
        didModifyInSession = true
        delay(400)
        syncEngine.saveLocalEdit(currentId, titleText, bodyMarkdown, fromActiveTyping = true)
        hasUnsavedInMemory = false
    }

    // True-resettable idle cloud push: resets to 0 on EVERY keystroke & WebView typing heartbeat!
    // Once 5 full seconds of silence elapse, pushes ONLY this document on appScope so resuming typing mid-HTTP
    // never cancels an in-flight create/update request.
    LaunchedEffect(titleText, bodyMarkdown, typingHeartbeat, activeDocId, isOnline, didModifyInSession) {
        val currentId = initializedDocId ?: return@LaunchedEffect
        if (!didModifyInSession || !isOnline) return@LaunchedEffect
        delay(5_000)

        val pendingSave = hasUnsavedInMemory
        val snapTitle = titleText
        val snapBody = bodyMarkdown
        if (pendingSave) hasUnsavedInMemory = false
        isPushingDoc = true

        app.appScope.launch {
            if (pendingSave) {
                syncEngine.saveLocalEdit(currentId, snapTitle, snapBody, fromActiveTyping = true)
            }
            val result = syncEngine.pushSingleDocument(currentId)
            withContext(Dispatchers.Main) {
                isPushingDoc = false
                if (result.docId != activeDocId) {
                    initializedDocId = result.docId
                    activeDocId = result.docId
                }
                if (result.synced && !hasUnsavedInMemory) {
                    didModifyInSession = false
                } else if (!result.synced && !result.conflict && result.errorMessage == null) {
                    // User typed more characters while the HTTP push was in flight: re-arm calm 5s idle timer
                    typingHeartbeat++
                }
            }
        }
    }

    fun flushOnAppScope(triggerSync: Boolean, exiting: Boolean = false) {
        val currentId = initializedDocId ?: return
        val pendingSave = hasUnsavedInMemory
        val finalTitle = titleText
        val finalBody = bodyMarkdown
        val currentSyncState = doc?.syncState
        val hasLocalUnsyncedContent =
            (currentSyncState == SyncState.LOCAL_NEW && (finalTitle.isNotBlank() || finalBody.isNotBlank())) ||
                currentSyncState == SyncState.LOCAL_MODIFIED
        val needsCloudPush = triggerSync && (didModifyInSession || pendingSave || hasLocalUnsyncedContent)

        hasUnsavedInMemory = false
        if (triggerSync && !isOnline) {
            // Offline: WorkManager will upload once back online
            didModifyInSession = false
        }

        if (!pendingSave && !(needsCloudPush && isOnline)) {
            if (exiting) syncEngine.setActiveEditingDocument(null)
            return
        }

        if (!exiting && needsCloudPush && isOnline) {
            isPushingDoc = true
        }

        app.appScope.launch {
            if (pendingSave) {
                // If we are about to push right now online, skip redundant WorkManager enqueue
                syncEngine.saveLocalEdit(
                    documentId = currentId,
                    title = finalTitle,
                    text = finalBody,
                    fromActiveTyping = (needsCloudPush && isOnline),
                )
            }
            if (needsCloudPush && isOnline) {
                com.outline.notes.data.sync.SyncWorker.cancelIdleEditSync(context.applicationContext)
                val result = syncEngine.pushSingleDocument(currentId)
                if (!exiting) {
                    withContext(Dispatchers.Main) {
                        isPushingDoc = false
                        if (result.docId != activeDocId) {
                            initializedDocId = result.docId
                            activeDocId = result.docId
                        }
                        if (result.synced && !hasUnsavedInMemory) {
                            didModifyInSession = false
                        }
                    }
                }
            }
            if (exiting) {
                syncEngine.setActiveEditingDocument(null)
            }
        }
    }

    fun flushAndExit() {
        keyboardController?.hide()
        flushOnAppScope(triggerSync = true, exiting = true)
        onBack()
    }

    BackHandler { flushAndExit() }

    DisposableEffect(Unit) {
        onDispose {
            flushOnAppScope(triggerSync = false, exiting = true)
        }
    }

    fun handlePickedImageUri(uri: Uri) {
        scope.launch {
            isUploadingImage = true
            val res = syncEngine.attachImage(activeDocId, uri)
            isUploadingImage = false
            res.onSuccess { imageUrl ->
                if (editorMode == EditorMode.RICH_TEXT) {
                    richController.insertImage(imageUrl, "image")
                } else {
                    val insertion = "\n![image]($imageUrl)\n"
                    bodyMarkdown += insertion
                    hasUnsavedInMemory = true
                    didModifyInSession = true
                    typingHeartbeat++
                }
                val note = if (imageUrl.startsWith(SyncEngine.OFFLINE_MEDIA_PREFIX)) {
                    "已离线缓存图片，联网后将自动上传至 MinIO"
                } else {
                    "图片已优化上传并插入"
                }
                snackbarHostState.showSnackbar(note)
            }.onFailure { err ->
                snackbarHostState.showSnackbar("插入图片失败：${err.message}")
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) handlePickedImageUri(uri)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
            handlePickedImageUri(uri)
        }
    }

    fun launchCameraCapture() {
        runCatching {
            val camDir = File(context.cacheDir, "camera").apply { mkdirs() }
            val photoFile = File(camDir, "capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar("无法启动相机：${it.message}") }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (isReadOnly && doc?.isDeleted != true) {
                ExtendedFloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isReadOnly = false
                        richController.setReadOnly(false)
                        if (editorMode == EditorMode.RICH_TEXT) {
                            richController.focusEditor()
                        }
                    },
                    icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    text = { Text("编辑") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                )
            }
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = { flushAndExit() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    val statusLabel = when {
                        isUploadingImage -> "正在压缩上传图片..."
                        isPushingDoc -> "正在同步..."
                        hasUnsavedInMemory -> "正在保存..."
                        doc?.isDeleted == true -> "回收站"
                        doc?.syncState == SyncState.CONFLICT -> "存在冲突"
                        doc?.syncState == SyncState.LOCAL_NEW || doc?.syncState == SyncState.LOCAL_MODIFIED ->
                            if (isOnline) "已本地保存 · 待同步" else "已离线缓存"
                        isReadOnly -> "阅读模式"
                        else -> "已同步"
                    }
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (doc?.syncState == SyncState.CONFLICT) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                actions = {
                    if (!isReadOnly && doc?.isDeleted != true) {
                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                keyboardController?.hide()
                                isReadOnly = true
                                richController.setReadOnly(true)
                                flushOnAppScope(triggerSync = true)
                            },
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("完成", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (headings.isNotEmpty() && editorMode == EditorMode.RICH_TEXT) {
                        IconButton(onClick = { showTocSheet = true }) {
                            Icon(Icons.AutoMirrored.Outlined.Toc, contentDescription = "文档目录")
                        }
                    }

                    EditorModeTogglePill(
                        mode = editorMode,
                        onToggle = { next ->
                            if (next != editorMode) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                editorMode = next
                                if (next == EditorMode.RICH_TEXT) {
                                    richController.loadDocument(bodyMarkdown)
                                }
                            }
                        },
                    )

                    if (doc?.isDeleted != true) {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                val res = syncEngine.toggleStar(activeDocId)
                                res.onFailure { err ->
                                    snackbarHostState.showSnackbar(err.message ?: "收藏操作失败")
                                }
                            }
                        }) {
                            val starred = doc?.isStarred == true
                            Icon(
                                imageVector = if (starred) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                                contentDescription = "收藏",
                                tint = if (starred) Color(0xFFF5A623) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            if (doc?.isDeleted == true) {
                                DropdownMenuItem(
                                    text = { Text("恢复笔记") },
                                    leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        scope.launch {
                                            val res = syncEngine.restoreDocument(activeDocId)
                                            snackbarHostState.showSnackbar(
                                                if (res.isSuccess) "已从回收站恢复" else (res.exceptionOrNull()?.message ?: "恢复失败"),
                                            )
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("永久彻底删除", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.DeleteForever,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        showPermDeleteConfirm = true
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("立即同步此笔记") },
                                    leadingIcon = { Icon(Icons.Outlined.Sync, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        scope.launch {
                                            isPushingDoc = true
                                            if (hasUnsavedInMemory) {
                                                syncEngine.saveLocalEdit(activeDocId, titleText, bodyMarkdown, fromActiveTyping = true)
                                                hasUnsavedInMemory = false
                                            }
                                            val result = syncEngine.pushSingleDocument(activeDocId)
                                            isPushingDoc = false
                                            if (result.docId != activeDocId) {
                                                initializedDocId = result.docId
                                                activeDocId = result.docId
                                            }
                                            if (result.synced && !hasUnsavedInMemory) {
                                                didModifyInSession = false
                                            }
                                            val msg = when {
                                                result.conflict -> "检测到云端版本冲突，请点击顶部横幅处理"
                                                result.errorMessage != null -> "同步失败：${result.errorMessage}"
                                                !isOnline -> "当前离线，联网后将自动同步"
                                                else -> "此笔记已同步到云端"
                                            }
                                            snackbarHostState.showSnackbar(msg)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("文档目录 (${headings.size})") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Toc, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        showTocSheet = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("移动到其他集合 / 目录") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        showMoveDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("新建子文档") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        scope.launch {
                                            val child = syncEngine.createLocalDocument(
                                                collectionId = doc?.collectionId,
                                                parentDocumentId = activeDocId,
                                            )
                                            onOpenDocument(child.id)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("分享 Markdown 文本") },
                                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        val shareText = "# ${titleText.ifBlank { "无标题笔记" }}\n\n$bodyMarkdown"
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, titleText)
                                            putExtra(Intent.EXTRA_TEXT, shareText)
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "分享笔记"))
                                    },
                                )
                                val archived = doc?.isArchived == true
                                DropdownMenuItem(
                                    text = { Text(if (archived) "取消归档（恢复）" else "归档笔记") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        scope.launch {
                                            val res = if (archived) {
                                                syncEngine.restoreDocument(activeDocId)
                                            } else {
                                                syncEngine.archiveDocument(activeDocId)
                                            }
                                            val msg = if (res.isSuccess) {
                                                if (archived) "已取消归档并恢复" else "已归档此笔记"
                                            } else {
                                                res.exceptionOrNull()?.message ?: "操作失败"
                                            }
                                            snackbarHostState.showSnackbar(msg)
                                        }
                                    },
                                )
                                if (doc?.syncState == SyncState.CONFLICT) {
                                    DropdownMenuItem(
                                        text = { Text("处理版本冲突", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.WarningAmber,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            showConflictDialog = true
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("移至回收站", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        showDeleteConfirm = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !isReadOnly && doc?.isDeleted != true,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                EditorFormattingToolbar(
                    mode = editorMode,
                    selection = selectionState,
                    onRichCommand = { cmd ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        richController.execCommand(cmd)
                    },
                    onOpenCalloutPicker = { showCalloutPicker = true },
                    onMarkdownInsert = { snippet ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        bodyMarkdown = if (bodyMarkdown.isBlank()) snippet else "$bodyMarkdown\n$snippet"
                        hasUnsavedInMemory = true
                        didModifyInSession = true
                        typingHeartbeat++
                    },
                    onPickImage = { showImageSourceDialog = true },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (doc?.isDeleted == true) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "此笔记位于回收站中（只读）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                val res = syncEngine.restoreDocument(activeDocId)
                                snackbarHostState.showSnackbar(
                                    if (res.isSuccess) "已恢复文档" else (res.exceptionOrNull()?.message ?: "恢复失败"),
                                )
                            }
                        },
                    ) {
                        Text("恢复", style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else if (doc?.isArchived == true) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Archive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "此笔记当前处于归档状态",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                val res = syncEngine.restoreDocument(activeDocId)
                                snackbarHostState.showSnackbar(
                                    if (res.isSuccess) "已恢复文档" else (res.exceptionOrNull()?.message ?: "恢复失败"),
                                )
                            }
                        },
                    ) {
                        Text("恢复", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (doc?.syncState == SyncState.CONFLICT) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .clickable { showConflictDialog = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "云端与本地均修改了此文档，点击选择保留方案",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Borderless Outline-style Title Input (read-only protected when in Read Mode)
            BasicTextField(
                value = titleText,
                onValueChange = {
                    titleText = it
                    hasUnsavedInMemory = true
                    didModifyInSession = true
                    typingHeartbeat++
                },
                readOnly = isReadOnly || doc?.isDeleted == true,
                textStyle = TextStyle(
                    fontSize = 24.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                decorationBox = { innerTextField ->
                    if (titleText.isEmpty()) {
                        Text(
                            text = "输入笔记标题…",
                            style = TextStyle(
                                fontSize = 24.sp,
                                lineHeight = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            ),
                        )
                    }
                    innerTextField()
                },
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.7.dp,
            )

            // Editor Body: Rich Text (Pre-warmed Tiptap ProseMirror) or Markdown Source
            if (editorMode == EditorMode.RICH_TEXT) {
                OutlineRichEditorView(
                    pool = app.editorWebViewPool,
                    controller = richController,
                    currentMarkdown = bodyMarkdown,
                    isDarkTheme = isDark,
                    accentColorHex = settings.effectiveAccentColor,
                    isReadOnly = isReadOnly || doc?.isDeleted == true || doc?.syncState == SyncState.LOCAL_DELETED,
                    serverUrl = settings.serverUrl,
                    apiKey = settings.apiKey,
                    onMarkdownChanged = { updatedMd ->
                        if (updatedMd != bodyMarkdown) {
                            bodyMarkdown = updatedMd
                            hasUnsavedInMemory = true
                            didModifyInSession = true
                            typingHeartbeat++
                        }
                    },
                    onTypingActivity = {
                        didModifyInSession = true
                        typingHeartbeat++
                    },
                    onLinkClicked = { rawUrl ->
                        scope.launch {
                            val internalDocId = syncEngine.resolveInternalDocumentLink(rawUrl)
                            if (internalDocId != null) {
                                flushOnAppScope(triggerSync = false)
                                onOpenDocument(internalDocId)
                            } else {
                                runCatching {
                                    val resolved = app.apiClient.resolveUrl(settings.serverUrl, rawUrl)
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resolved)))
                                }
                            }
                        }
                    },
                    onImageClicked = { src ->
                        lightboxImageSrc = src
                    },
                    onRequestEditMode = {
                        if (doc?.isDeleted != true) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isReadOnly = false
                            richController.setReadOnly(false)
                            richController.focusEditor()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                BasicTextField(
                    value = bodyMarkdown,
                    onValueChange = {
                        bodyMarkdown = it
                        hasUnsavedInMemory = true
                        didModifyInSession = true
                        typingHeartbeat++
                    },
                    readOnly = isReadOnly || doc?.isDeleted == true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    decorationBox = { innerTextField ->
                        if (bodyMarkdown.isEmpty()) {
                            Text(
                                text = "在此输入 Markdown 源码…\n例如：# 一级标题、- [ ] 待办事项、:::info 提示块",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    lineHeight = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                ),
                            )
                        }
                        innerTextField()
                    },
                )
            }
        }
    }

    // Full-screen pinch-to-zoom Image Lightbox
    val activeImage = lightboxImageSrc
    if (activeImage != null) {
        ImageLightboxDialog(
            imageSrc = activeImage,
            serverUrl = settings.serverUrl,
            apiKey = settings.apiKey,
            apiClient = app.apiClient,
            offlineMediaDir = syncEngine.offlineMediaDir,
            onDismiss = { lightboxImageSrc = null },
            onSavedMessage = { msg ->
                scope.launch { snackbarHostState.showSnackbar(msg) }
            },
        )
    }

    // Document TOC Bottom Sheet
    if (showTocSheet) {
        TocBottomSheet(
            headings = headings,
            onSelectHeading = { idx -> richController.scrollToHeading(idx) },
            onDismiss = { showTocSheet = false },
        )
    }

    // Move Document Dialog
    if (showMoveDialog) {
        MoveDocumentDialog(
            currentDocumentId = activeDocId,
            currentCollectionId = doc?.collectionId,
            currentParentId = doc?.parentDocumentId,
            collections = collections,
            allDocs = allActiveDocs,
            onDismiss = { showMoveDialog = false },
            onConfirmMove = { colId, parentId ->
                showMoveDialog = false
                scope.launch {
                    val res = syncEngine.moveDocument(activeDocId, colId, parentId)
                    snackbarHostState.showSnackbar(
                        if (res.isSuccess) "笔记已移动" else (res.exceptionOrNull()?.message ?: "移动失败"),
                    )
                }
            },
        )
    }

    // Image Source Picker (Gallery or Camera)
    if (showImageSourceDialog) {
        AlertDialog(
            onDismissRequest = { showImageSourceDialog = false },
            title = { Text("插入图片") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                showImageSourceDialog = false
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("从相册选择图片", style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                showImageSourceDialog = false
                                launchCameraCapture()
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("使用相机拍照", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImageSourceDialog = false }) { Text("取消") }
            },
        )
    }

    // Outline Callout Style Picker
    if (showCalloutPicker) {
        AlertDialog(
            onDismissRequest = { showCalloutPicker = false },
            title = { Text("插入 Outline 提示块") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Triple("info", "信息提示 (Info)", MaterialTheme.colorScheme.primary),
                        Triple("tip", "小贴士 (Tip)", Color(0xFF10B981)),
                        Triple("warning", "注意警告 (Warning)", Color(0xFFF59E0B)),
                    ).forEach { (type, label, tint) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(tint.copy(alpha = 0.12f))
                                .clickable {
                                    showCalloutPicker = false
                                    if (editorMode == EditorMode.RICH_TEXT) {
                                        richController.execCommand("callout", type)
                                    } else {
                                        val block = "\n:::$type\n提示内容\n:::\n"
                                        bodyMarkdown += block
                                        hasUnsavedInMemory = true
                                        didModifyInSession = true
                                        typingHeartbeat++
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Info, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCalloutPicker = false }) { Text("取消") }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("移至回收站？") },
            text = { Text("笔记将被移入 Outline 回收站（可随时恢复；离线时将在联网后同步）。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        syncEngine.deleteDocumentLocally(activeDocId)
                        onBack()
                    }
                }) {
                    Text("移至回收站", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showPermDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showPermDeleteConfirm = false },
            title = { Text("永久彻底删除？") },
            text = { Text("此操作将从云端服务器彻底删除该笔记且无法恢复，请确认。") },
            confirmButton = {
                TextButton(onClick = {
                    showPermDeleteConfirm = false
                    scope.launch {
                        val res = syncEngine.permanentlyDeleteDocument(activeDocId)
                        if (res.isSuccess) {
                            onBack()
                        } else {
                            snackbarHostState.showSnackbar(res.exceptionOrNull()?.message ?: "删除失败")
                        }
                    }
                }) {
                    Text("彻底删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermDeleteConfirm = false }) { Text("取消") }
            },
        )
    }

    val currentDoc = doc
    if (showConflictDialog && currentDoc != null) {
        ConflictResolutionDialog(
            doc = currentDoc,
            onDismiss = { showConflictDialog = false },
            onResolve = { strategy ->
                showConflictDialog = false
                scope.launch {
                    val res = syncEngine.resolveConflict(currentDoc.id, strategy)
                    if (res.isSuccess) {
                        val reloaded = dao.getDocumentById(currentDoc.id)
                        if (reloaded != null) {
                            titleText = reloaded.title
                            bodyMarkdown = reloaded.text
                            richController.loadDocument(reloaded.text)
                        }
                        snackbarHostState.showSnackbar("冲突已解决并同步")
                    } else {
                        snackbarHostState.showSnackbar("处理冲突失败：${res.exceptionOrNull()?.message}")
                    }
                }
            },
        )
    }
}

@Composable
private fun EditorModeTogglePill(
    mode: EditorMode,
    onToggle: (EditorMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSegment(
            label = "富文本",
            selected = mode == EditorMode.RICH_TEXT,
            onClick = { onToggle(EditorMode.RICH_TEXT) },
        )
        ModeSegment(
            label = "MD",
            selected = mode == EditorMode.MARKDOWN,
            onClick = { onToggle(EditorMode.MARKDOWN) },
        )
    }
}

@Composable
private fun ModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MaterialTheme.colorScheme.background else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditorFormattingToolbar(
    mode: EditorMode,
    selection: EditorSelectionState,
    onRichCommand: (String) -> Unit,
    onOpenCalloutPicker: () -> Unit,
    onMarkdownInsert: (String) -> Unit,
    onPickImage: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column {
            // Contextual Table Action Bar when cursor is inside a table
            AnimatedVisibility(visible = mode == EditorMode.RICH_TEXT && selection.table) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.7.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("表格:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        TableMiniChip("+ 加行") { onRichCommand("addRowAfter") }
                        TableMiniChip("- 删行") { onRichCommand("deleteRow") }
                        TableMiniChip("+ 加列") { onRichCommand("addColumnAfter") }
                        TableMiniChip("- 删列") { onRichCommand("deleteColumn") }
                        TableMiniChip("删表", isDanger = true) { onRichCommand("deleteTable") }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.8.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (mode == EditorMode.RICH_TEXT) {
                    ToolbarIconBtn(Icons.AutoMirrored.Outlined.Undo, "撤销", false, selection.canUndo) {
                        onRichCommand("undo")
                    }
                    ToolbarIconBtn(Icons.AutoMirrored.Outlined.Redo, "重做", false, selection.canRedo) {
                        onRichCommand("redo")
                    }
                }

                ToolbarTextBtn("H1", mode == EditorMode.RICH_TEXT && selection.h1) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("h1") else onMarkdownInsert("# ")
                }
                ToolbarTextBtn("H2", mode == EditorMode.RICH_TEXT && selection.h2) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("h2") else onMarkdownInsert("## ")
                }
                ToolbarTextBtn("H3", mode == EditorMode.RICH_TEXT && selection.h3) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("h3") else onMarkdownInsert("### ")
                }

                ToolbarIconBtn(Icons.Outlined.FormatBold, "加粗", mode == EditorMode.RICH_TEXT && selection.bold) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("bold") else onMarkdownInsert("**粗体文字**")
                }
                ToolbarIconBtn(Icons.Outlined.FormatItalic, "斜体", mode == EditorMode.RICH_TEXT && selection.italic) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("italic") else onMarkdownInsert("*斜体文字*")
                }
                ToolbarIconBtn(Icons.Outlined.FormatStrikethrough, "删除线", mode == EditorMode.RICH_TEXT && selection.strike) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("strike") else onMarkdownInsert("~~删除线~~")
                }
                ToolbarIconBtn(Icons.Outlined.Highlight, "高亮", mode == EditorMode.RICH_TEXT && selection.highlight) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("highlight") else onMarkdownInsert("==高亮文字==")
                }

                ToolbarIconBtn(Icons.Outlined.CheckBox, "待办清单", mode == EditorMode.RICH_TEXT && selection.taskList) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("taskList") else onMarkdownInsert("- [ ] ")
                }
                ToolbarIconBtn(Icons.AutoMirrored.Outlined.FormatListBulleted, "无序列表", mode == EditorMode.RICH_TEXT && selection.bulletList) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("bulletList") else onMarkdownInsert("- ")
                }
                ToolbarIconBtn(Icons.Outlined.FormatListNumbered, "有序列表", mode == EditorMode.RICH_TEXT && selection.orderedList) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("orderedList") else onMarkdownInsert("1. ")
                }

                ToolbarIconBtn(Icons.Outlined.Info, "提示块", mode == EditorMode.RICH_TEXT && selection.callout) {
                    onOpenCalloutPicker()
                }
                ToolbarIconBtn(Icons.Outlined.FormatQuote, "引用", mode == EditorMode.RICH_TEXT && selection.blockquote) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("blockquote") else onMarkdownInsert("> ")
                }
                ToolbarIconBtn(Icons.Outlined.Code, "行内代码", mode == EditorMode.RICH_TEXT && selection.code) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("code") else onMarkdownInsert("`code`")
                }
                ToolbarIconBtn(Icons.Outlined.Terminal, "代码块", mode == EditorMode.RICH_TEXT && selection.codeBlock) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("codeBlock") else onMarkdownInsert("```\n\n```")
                }
                ToolbarIconBtn(Icons.Outlined.GridOn, "插入表格", mode == EditorMode.RICH_TEXT && selection.table) {
                    if (mode == EditorMode.RICH_TEXT) {
                        onRichCommand("table")
                    } else {
                        onMarkdownInsert("| 列1 | 列2 |\n| --- | --- |\n| 内容 | 内容 |")
                    }
                }
                ToolbarIconBtn(Icons.Outlined.HorizontalRule, "分割线", false) {
                    if (mode == EditorMode.RICH_TEXT) onRichCommand("hr") else onMarkdownInsert("---")
                }
                ToolbarIconBtn(Icons.Outlined.Image, "插入图片/拍照", false) {
                    onPickImage()
                }
            }
        }
    }
}

@Composable
private fun TableMiniChip(
    label: String,
    isDanger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isDanger) MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isDanger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ToolbarIconBtn(
    icon: ImageVector,
    description: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ToolbarTextBtn(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = fg,
        )
    }
}

@Composable
private fun ConflictResolutionDialog(
    doc: DocumentEntity,
    onDismiss: () -> Unit,
    onResolve: (ConflictResolution) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现版本冲突") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "你在离线/同步期间修改了这篇笔记，同时云端版本也发生了变化。请选择处理方式（不会丢失任何内容）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    Column {
                        Text(
                            text = "云端最新版本（${UiFormatters.formatIsoTime(doc.conflictServerUpdatedAt)}）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = UiFormatters.stripMarkdownSnippet(doc.conflictServerText.orEmpty(), 120),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { onResolve(ConflictResolution.DUPLICATE_LOCAL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("保留两边（将本地版另存为新笔记，推荐）")
                    }
                    TextButton(
                        onClick = { onResolve(ConflictResolution.KEEP_LOCAL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("保留本地版本并覆盖云端")
                    }
                    TextButton(
                        onClick = { onResolve(ConflictResolution.ACCEPT_SERVER) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("放弃本地修改，采用云端版本", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后再说") }
        },
    )
}
