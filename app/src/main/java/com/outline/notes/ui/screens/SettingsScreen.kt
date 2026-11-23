package com.outline.notes.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.outline.notes.BuildConfig
import com.outline.notes.data.backup.BackupExporter
import com.outline.notes.data.local.OutlineDao
import com.outline.notes.data.local.WorkspaceStore
import com.outline.notes.data.prefs.AppPreferences
import com.outline.notes.data.prefs.EditorMode
import com.outline.notes.data.prefs.ThemeMode
import com.outline.notes.data.prefs.UserSettings
import com.outline.notes.data.remote.OutlineApiClient
import com.outline.notes.data.sync.SyncDiagnostics
import com.outline.notes.data.sync.SyncEngine
import com.outline.notes.data.sync.SyncResult
import com.outline.notes.data.sync.SyncWorker
import com.outline.notes.ui.components.UiFormatters
import com.outline.notes.ui.components.WorkspaceLogo
import com.outline.notes.ui.theme.OutlineColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    preferences: AppPreferences,
    dao: OutlineDao,
    workspaceStore: WorkspaceStore,
    apiClient: OutlineApiClient,
    syncEngine: SyncEngine,
    onBack: () -> Unit,
    onReconfigureServer: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val allDocs by workspaceStore.allActiveDocs.collectAsState()
    val pendingCount by workspaceStore.pendingCount.collectAsState()
    var mediaCacheBytes by remember { mutableLongStateOf(syncEngine.getMediaCacheSizeBytes()) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var customHexInput by remember(settings.customAccentColor) {
        mutableStateOf(settings.customAccentColor.ifBlank { settings.effectiveAccentColor })
    }

    val canUseBiometric = remember {
        val bm = BiometricManager.from(context)
        val status = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        status == BiometricManager.BIOMETRIC_SUCCESS
    }

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cols = dao.getCollections()
                    val docs = dao.getAllDocuments()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        BackupExporter.writeMarkdownZip(cols, docs, out)
                    } ?: throw IllegalStateException("无法写入导出文件")
                }
            }
            val msg = if (result.isSuccess) {
                "已导出 ${result.getOrDefault(0)} 篇笔记为 Markdown ZIP 备份"
            } else {
                "导出失败：${result.exceptionOrNull()?.message}"
            }
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("设置与同步管理", style = MaterialTheme.typography.titleLarge) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Connection Card with synced Workspace Logo
            SettingsSectionCard(title = "云端服务器") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    WorkspaceLogo(
                        serverUrl = settings.serverUrl,
                        apiKey = settings.apiKey,
                        logoUrl = settings.workspaceLogoUrl,
                        apiClient = apiClient,
                        size = 42.dp,
                        cornerRadius = 11.dp,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = settings.workspaceName.ifBlank { "Outline 工作区" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = settings.userName.ifBlank { settings.userEmail },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SettingsRow("地址", settings.serverUrl)
                SettingsRow("上次同步", UiFormatters.formatFullTime(settings.lastSyncAt))
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        scope.launch {
                            val res = syncEngine.syncNow(silent = false)
                            val msg = when (res) {
                                is SyncResult.Success -> "同步完成（共 ${res.pulled} 篇笔记）"
                                is SyncResult.SkippedOffline -> "当前离线，稍后自动同步"
                                is SyncResult.Error -> res.message
                            }
                            snackbarHostState.showSnackbar(msg)
                        }
                    }) {
                        Text("立即全量同步")
                    }
                    TextButton(onClick = { showDisconnectConfirm = true }) {
                        Text("切换/断开服务器", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Theme Mode
            SettingsSectionCard(title = "界面外观") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.themeMode == ThemeMode.SYSTEM,
                        onClick = { scope.launch { preferences.setThemeMode(ThemeMode.SYSTEM) } },
                        label = { Text("跟随系统") },
                    )
                    FilterChip(
                        selected = settings.themeMode == ThemeMode.LIGHT,
                        onClick = { scope.launch { preferences.setThemeMode(ThemeMode.LIGHT) } },
                        label = { Text("浅色") },
                    )
                    FilterChip(
                        selected = settings.themeMode == ThemeMode.DARK,
                        onClick = { scope.launch { preferences.setThemeMode(ThemeMode.DARK) } },
                        label = { Text("深色") },
                    )
                }
            }

            // Accent Color
            SettingsSectionCard(title = "主题强调色") {
                val isFollowingServer = settings.customAccentColor.isBlank()
                val serverHex = settings.serverAccentColor.ifBlank { UserSettings.DEFAULT_ACCENT_HEX }
                val serverColor = OutlineColors.parseAccent(serverHex)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = isFollowingServer,
                        onClick = {
                            scope.launch { preferences.setCustomAccentColor("") }
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(serverColor),
                            )
                        },
                        label = {
                            val suffix = if (settings.serverAccentColor.isNotBlank()) {
                                " (${settings.serverAccentColor})"
                            } else {
                                " (默认)"
                            }
                            Text("跟随服务端$suffix")
                        },
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlineColors.PresetAccents.forEach { (hex, _) ->
                        val color = OutlineColors.parseAccent(hex)
                        val selected = !isFollowingServer &&
                            settings.customAccentColor.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.onBackground else Color.Black.copy(alpha = 0.12f),
                                    shape = CircleShape,
                                )
                                .clickable {
                                    customHexInput = hex
                                    scope.launch { preferences.setCustomAccentColor(hex) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = if (color.luminance() > 0.55f) Color(0xFF111319) else Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val previewHex = AppPreferences.normalizeHex(customHexInput)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (previewHex.isNotBlank()) OutlineColors.parseAccent(previewHex)
                                else MaterialTheme.colorScheme.surfaceContainer,
                            )
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                    OutlinedTextField(
                        value = customHexInput,
                        onValueChange = { customHexInput = it.take(7) },
                        label = { Text("自定义 Hex 色值") },
                        placeholder = { Text("#0366D6") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val normalized = AppPreferences.normalizeHex(customHexInput)
                            if (normalized.isNotBlank()) {
                                scope.launch {
                                    preferences.setCustomAccentColor(normalized)
                                    snackbarHostState.showSnackbar("已应用强调色 $normalized")
                                }
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar("请输入 6 位十六进制色值，例如 #9E5CF7")
                                }
                            }
                        },
                    ) {
                        Text("应用")
                    }
                }
            }

            // Reading & Editing preferences
            SettingsSectionCard(title = "阅读与编辑体验") {
                Text(
                    text = "默认编辑模式（编辑页右上角可随时切换）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.defaultEditorMode == EditorMode.RICH_TEXT,
                        onClick = { scope.launch { preferences.setDefaultEditorMode(EditorMode.RICH_TEXT) } },
                        label = { Text("富文本（推荐）") },
                    )
                    FilterChip(
                        selected = settings.defaultEditorMode == EditorMode.MARKDOWN,
                        onClick = { scope.launch { preferences.setDefaultEditorMode(EditorMode.MARKDOWN) } },
                        label = { Text("Markdown 源码") },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                SettingsToggleRow(
                    title = "打开已有笔记默认防误触阅读",
                    subtitle = "防止浏览时误弹键盘；双击正文或点右下角按钮即刻编辑，待办可直接勾选",
                    checked = settings.openInReadMode,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setOpenInReadMode(enabled) }
                    },
                )
                SettingsToggleRow(
                    title = "图片上传前智能压缩优化",
                    subtitle = "自动校正照片方向并压缩超大原图（上限 2048px），传 MinIO 更快省流",
                    checked = settings.compressImagesOnUpload,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setCompressImagesOnUpload(enabled) }
                    },
                )
            }

            // Security & Privacy
            SettingsSectionCard(title = "安全与隐私") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "API 密钥已通过 Android Keystore AES-256 加密存储",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingsToggleRow(
                    title = "生物识别 / 锁屏密码应用锁",
                    subtitle = if (canUseBiometric) "打开应用时需通过指纹、面容或系统密码验证" else "当前设备未设置锁屏密码或生物识别",
                    checked = settings.biometricLockEnabled,
                    enabled = canUseBiometric,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setBiometricLockEnabled(enabled) }
                    },
                )
            }

            // Sync, Backup & Diagnostics
            SettingsSectionCard(title = "离线缓存、备份与诊断") {
                SettingsRow("已缓存笔记", "${allDocs.size} 篇")
                SettingsRow("待上传修改", "$pendingCount 项")
                SettingsRow("图片本地缓存", UiFormatters.formatBytes(mediaCacheBytes))
                Spacer(modifier = Modifier.height(6.dp))
                SettingsToggleRow(
                    title = "后台定期自动同步",
                    subtitle = "每 30 分钟在联网且电量充足时静默拉取最新笔记",
                    checked = settings.periodicSyncEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            preferences.setPeriodicSyncEnabled(enabled)
                            SyncWorker.configurePeriodicSync(context, enabled)
                        }
                    },
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val fileName = "OutlineNotes_Backup_${LocalDate.now()}.zip"
                        backupLauncher.launch(fileName)
                    }) {
                        Text("导出全部笔记 (.zip)")
                    }
                    TextButton(onClick = { showLogsDialog = true }) {
                        Text("同步诊断日志")
                    }
                }
                Text(
                    text = "清理图片缓存（不影响离线笔记内容）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            syncEngine.clearMediaCache()
                            mediaCacheBytes = syncEngine.getMediaCacheSizeBytes()
                            scope.launch { snackbarHostState.showSnackbar("图片缓存已清理") }
                        }
                        .padding(vertical = 6.dp),
                )
            }

            // Version footer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val buildTag = if (BuildConfig.DEBUG) "Debug" else "Release"
                Text(
                    text = "Outline Notes v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · $buildTag",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }

    if (showLogsDialog) {
        val logContent = remember { SyncDiagnostics.dumpText() }
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = { Text("同步诊断日志") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        text = logContent,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Outline Notes 同步诊断日志")
                        putExtra(Intent.EXTRA_TEXT, logContent)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "分享诊断日志"))
                }) {
                    Text("导出分享")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogsDialog = false }) { Text("关闭") }
            },
        )
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("断开当前服务器？") },
            text = {
                val warn = if (pendingCount > 0) {
                    "注意：当前还有 $pendingCount 项离线修改尚未上传，断开后本地缓存将被清空！"
                } else {
                    "断开后将清除本机缓存的笔记数据并返回服务器配置页（云端数据不受影响）。"
                }
                Text(warn)
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    scope.launch {
                        SyncWorker.configurePeriodicSync(context, false)
                        workspaceStore.resetOnDisconnect()
                        syncEngine.clearAllLocalData()
                        preferences.clearConnection()
                        onReconfigureServer()
                    }
                }) {
                    Text("确认断开", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.6.dp)
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.65f),
        )
    }
}
