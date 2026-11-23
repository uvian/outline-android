package com.outline.notes.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class HeadingItem(
    val index: Int = 0,
    val level: Int = 1,
    val text: String = "",
)

@Immutable
@Serializable
data class EditorSelectionState(
    val h1: Boolean = false,
    val h2: Boolean = false,
    val h3: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val highlight: Boolean = false,
    val bulletList: Boolean = false,
    val orderedList: Boolean = false,
    val taskList: Boolean = false,
    val blockquote: Boolean = false,
    val callout: Boolean = false,
    val codeBlock: Boolean = false,
    val table: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

@Stable
class RichEditorController(
    private val pool: EditorWebViewPool,
) {
    private val _selectionState = MutableStateFlow(EditorSelectionState())
    val selectionState: StateFlow<EditorSelectionState> = _selectionState.asStateFlow()

    private val _headings = MutableStateFlow<List<HeadingItem>>(emptyList())
    val headings: StateFlow<List<HeadingItem>> = _headings.asStateFlow()

    internal fun updateSelection(state: EditorSelectionState) {
        _selectionState.value = state
    }

    internal fun updateHeadings(items: List<HeadingItem>) {
        _headings.value = items
    }

    fun loadDocument(markdown: String) = pool.loadDocument(markdown)
    fun setTheme(isDark: Boolean) = pool.setTheme(isDark)
    fun setAccentColor(hex: String) = pool.setAccentColor(hex)
    fun setReadOnly(readOnly: Boolean) = pool.setReadOnly(readOnly)
    fun focusEditor() = pool.focusEditor()
    fun scrollToHeading(index: Int) = pool.scrollToHeading(index)
    fun execCommand(command: String, arg: String? = null) = pool.execCommand(command, arg)
    fun insertImage(url: String, alt: String = "") = pool.insertImage(url, alt)
}

@Composable
fun OutlineRichEditorView(
    pool: EditorWebViewPool,
    controller: RichEditorController,
    currentMarkdown: String,
    isDarkTheme: Boolean,
    accentColorHex: String,
    isReadOnly: Boolean,
    serverUrl: String,
    apiKey: String,
    onMarkdownChanged: (String) -> Unit,
    onLinkClicked: (String) -> Unit,
    onImageClicked: (String) -> Unit,
    onRequestEditMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestMarkdown by rememberUpdatedState(currentMarkdown)
    val latestIsDark by rememberUpdatedState(isDarkTheme)
    val latestAccent by rememberUpdatedState(accentColorHex)
    val latestReadOnly by rememberUpdatedState(isReadOnly)
    val latestServerUrl by rememberUpdatedState(serverUrl)
    val latestApiKey by rememberUpdatedState(apiKey)
    val latestOnMarkdownChanged by rememberUpdatedState(onMarkdownChanged)
    val latestOnLinkClicked by rememberUpdatedState(onLinkClicked)
    val latestOnImageClicked by rememberUpdatedState(onImageClicked)
    val latestOnRequestEditMode by rememberUpdatedState(onRequestEditMode)

    val stableMarkdownCallback = remember { { md: String -> latestOnMarkdownChanged(md) } }
    val stableSelectionCallback = remember(controller) { { state: EditorSelectionState -> controller.updateSelection(state) } }
    val stableHeadingsCallback = remember(controller) { { items: List<HeadingItem> -> controller.updateHeadings(items) } }
    val stableLinkCallback = remember { { url: String -> latestOnLinkClicked(url) } }
    val stableImageCallback = remember { { src: String -> latestOnImageClicked(src) } }
    val stableEditModeCallback = remember { { latestOnRequestEditMode() } }

    val containerRef = remember { arrayOfNulls<FrameLayout>(1) }

    fun attachToHost(host: FrameLayout, reloadContent: Boolean) {
        val wv = pool.acquire(
            activityContext = host.context,
            serverUrl = latestServerUrl,
            apiKey = latestApiKey,
            isDark = latestIsDark,
            accentHex = latestAccent,
            readOnly = latestReadOnly,
            onMarkdownChanged = stableMarkdownCallback,
            onSelectionState = stableSelectionCallback,
            onHeadingsChanged = stableHeadingsCallback,
            onLinkClicked = stableLinkCallback,
            onImageClicked = stableImageCallback,
            onRequestEditMode = stableEditModeCallback,
        )
        if (wv.parent !== host) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            host.removeAllViews()
            host.addView(
                wv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            if (reloadContent) {
                pool.loadDocument(latestMarkdown)
            }
        }
    }

    LaunchedEffect(isDarkTheme) {
        controller.setTheme(isDarkTheme)
    }

    LaunchedEffect(accentColorHex) {
        controller.setAccentColor(accentColorHex)
    }

    LaunchedEffect(isReadOnly) {
        controller.setReadOnly(isReadOnly)
    }

    // Re-attach pooled WebView cleanly when returning from a child note / internal link back-stack entry
    DisposableEffect(lifecycleOwner, pool, stableMarkdownCallback) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                containerRef[0]?.let { host ->
                    if (host.childCount == 0) {
                        attachToHost(host, reloadContent = true)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            containerRef[0]?.let { host ->
                pool.release(host, stableMarkdownCallback)
            }
            containerRef[0] = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(if (isDarkTheme) 0xFF111319.toInt() else 0xFFFFFFFF.toInt())
                containerRef[0] = this
                attachToHost(this, reloadContent = false)
            }
        },
        update = { host ->
            host.setBackgroundColor(if (isDarkTheme) 0xFF111319.toInt() else 0xFFFFFFFF.toInt())
        },
    )
}
