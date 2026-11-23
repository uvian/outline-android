package com.outline.notes.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.outline.notes.R
import com.outline.notes.data.prefs.AppPreferences
import com.outline.notes.data.remote.OutlineApiClient
import com.outline.notes.data.sync.SyncEngine
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    initialServerUrl: String = "",
    initialApiKey: String = "",
    apiClient: OutlineApiClient,
    preferences: AppPreferences,
    syncEngine: SyncEngine,
    onConnected: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Official Outline logo
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_outline_official),
                    contentDescription = "Outline",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "连接到 Outline",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "输入你的云端 Outline 地址与 API 密钥，即可同步所有笔记并支持离线记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Server URL
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    errorMessage = null
                },
                label = { Text("服务器地址") },
                placeholder = { Text("https://wiki.yourdomain.com") },
                leadingIcon = {
                    Icon(Icons.Outlined.Public, contentDescription = null)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (serverUrl.trim().startsWith("http://", ignoreCase = true)) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "提示：当前使用 HTTP 非加密连接，建议仅在受信任的局域网或 VPN 中使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // API Key
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    errorMessage = null
                },
                label = { Text("个人 API 密钥 (API Key)") },
                placeholder = { Text("ol_api_...") },
                leadingIcon = {
                    Icon(Icons.Outlined.Key, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = "显示/隐藏密钥",
                        )
                    }
                },
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Expandable guide
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showHelp = !showHelp }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "在哪里获取 API Key？",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            AnimatedVisibility(visible = showHelp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "获取步骤（仅需 10 秒）：",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "1. 在电脑浏览器打开你的 Outline 网页端\n" +
                            "2. 点击左侧栏「设置 (Settings)」→「API 密钥 (API Keys)」\n" +
                            "3. 点击「新建 API 密钥」，备注例如 Android 手机\n" +
                            "4. 复制以 ol_api_ 开头的密钥粘贴到上方即可",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                        .padding(12.dp),
                ) {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (statusText != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = statusText!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val cleanUrl = AppPreferences.normalizeServerUrl(serverUrl)
                    val cleanKey = apiKey.trim()
                    if (cleanUrl.isBlank() || cleanKey.isBlank()) {
                        errorMessage = "请完整填写服务器地址与 API 密钥"
                        return@Button
                    }
                    scope.launch {
                        isConnecting = true
                        errorMessage = null
                        statusText = "正在验证服务器连接..."
                        try {
                            val auth = apiClient.authInfo(cleanUrl, cleanKey)
                            val teamName = auth.team?.name ?: "Outline 工作区"
                            val userName = auth.user?.name ?: "用户"
                            val userEmail = auth.user?.email.orEmpty()
                            val logoUrl = auth.team?.avatarUrl.orEmpty()
                            val serverAccent = auth.team?.preferences?.customTheme?.accent.orEmpty()

                            preferences.saveConnection(
                                serverUrl = cleanUrl,
                                apiKey = cleanKey,
                                userName = userName,
                                userEmail = userEmail,
                                workspaceName = teamName,
                                workspaceLogoUrl = logoUrl,
                                serverAccentColor = serverAccent,
                            )
                            statusText = "已连接「$teamName」，正在拉取初始笔记..."
                            syncEngine.syncNow(silent = false)
                            onConnected()
                        } catch (e: Exception) {
                            errorMessage = "连接失败：${e.message ?: "请检查域名、HTTPS 证书或 API 密钥是否正确"}"
                            statusText = null
                        } finally {
                            isConnecting = false
                        }
                    }
                },
                enabled = !isConnecting,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("正在连接...", fontWeight = FontWeight.Medium)
                } else {
                    Text("连接并同步笔记", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
