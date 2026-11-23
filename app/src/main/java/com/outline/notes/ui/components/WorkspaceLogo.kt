package com.outline.notes.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.outline.notes.R
import com.outline.notes.data.remote.OutlineApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Displays the synced server workspace logo (with offline disk cache),
 * falling back cleanly to the official Outline mark.
 */
@Composable
fun WorkspaceLogo(
    serverUrl: String,
    apiKey: String,
    logoUrl: String,
    apiClient: OutlineApiClient,
    size: Dp = 36.dp,
    cornerRadius: Dp = 10.dp,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(serverUrl, logoUrl) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(serverUrl, apiKey, logoUrl) {
        if (logoUrl.isBlank() || serverUrl.isBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.IO) {
            val cached = apiClient.getCachedMediaFile(serverUrl, logoUrl)
            val file = cached ?: apiClient.fetchAndCacheMediaBlocking(serverUrl, apiKey, logoUrl)?.file
            if (file != null && file.exists()) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
            } else {
                null
            }
        }
        bitmap = decoded
    }

    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Color.White)
            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = "工作区 Logo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.ic_outline_official),
                contentDescription = "Outline",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
