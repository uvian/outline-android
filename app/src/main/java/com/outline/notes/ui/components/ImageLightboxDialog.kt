package com.outline.notes.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.outline.notes.data.media.ImageProcessor
import com.outline.notes.data.remote.OutlineApiClient
import com.outline.notes.data.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ImageLightboxDialog(
    imageSrc: String,
    serverUrl: String,
    apiKey: String,
    apiClient: OutlineApiClient,
    offlineMediaDir: File,
    onDismiss: () -> Unit,
    onSavedMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember(imageSrc) { mutableStateOf<ImageBitmap?>(null) }
    var resolvedFile by remember(imageSrc) { mutableStateOf<File?>(null) }
    var resolvedMime by remember(imageSrc) { mutableStateOf("image/jpeg") }
    var isLoading by remember(imageSrc) { mutableStateOf(true) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(imageSrc) {
        isLoading = true
        val loaded = withContext(Dispatchers.IO) {
            when {
                imageSrc.startsWith(SyncEngine.OFFLINE_MEDIA_PREFIX) -> {
                    val fileName = imageSrc.removePrefix(SyncEngine.OFFLINE_MEDIA_PREFIX).substringBefore('/')
                    val f = File(offlineMediaDir, fileName)
                    if (f.exists()) Triple(f, "image/jpeg", BitmapFactory.decodeFile(f.absolutePath)) else null
                }

                else -> {
                    val normalized = if (imageSrc.startsWith("https://appassets.androidplatform.net/")) {
                        imageSrc.removePrefix("https://appassets.androidplatform.net")
                    } else {
                        imageSrc
                    }
                    val cached = apiClient.fetchAndCacheMediaBlocking(serverUrl, apiKey, normalized)
                    val file = cached?.file
                    if (file != null && file.exists()) {
                        Triple(file, cached.mimeType, BitmapFactory.decodeFile(file.absolutePath))
                    } else {
                        null
                    }
                }
            }
        }
        resolvedFile = loaded?.first
        resolvedMime = loaded?.second ?: "image/jpeg"
        bitmap = loaded?.third?.asImageBitmap()
        isLoading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f)),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (bitmap == null) {
                Text(
                    text = "无法加载该图片（可能尚未离线缓存）",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = "预览图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.2f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                                onTap = {
                                    if (scale <= 1.05f) onDismiss()
                                },
                            )
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = nextScale
                                offset = if (nextScale <= 1.01f) {
                                    Offset.Zero
                                } else {
                                    offset + pan
                                }
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }

            // Top overlay action bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                }
                if (resolvedFile != null) {
                    IconButton(
                        onClick = {
                            val fileToSave = resolvedFile ?: return@IconButton
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    ImageProcessor.saveImageToGallery(context, fileToSave, resolvedMime)
                                }
                                onSavedMessage(
                                    if (res.isSuccess) "已保存到相册 (Pictures/OutlineNotes)"
                                    else "保存失败：${res.exceptionOrNull()?.message}",
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = "保存到相册", tint = Color.White)
                    }
                }
            }
        }
    }
}
