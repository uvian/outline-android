package com.outline.notes.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.outline.notes.data.remote.OutlineApiClient
import com.outline.notes.data.sync.SyncEngine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Holds a single pre-warmed offline Tiptap (ProseMirror) WebView instance so opening notes
 * never blocks the UI thread with Chromium/JS initialization during navigation animations.
 */
class EditorWebViewPool(
    private val appContext: Context,
    private val apiClient: OutlineApiClient,
    private val offlineMediaDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var contextWrapper: MutableContextWrapper? = null
    private var webView: WebView? = null
    private var isReady: Boolean = false

    @Volatile
    private var currentServerUrl: String = ""

    @Volatile
    private var currentApiKey: String = ""

    private var activeDarkTheme: Boolean = false
    private var activeAccentHex: String = "#0366D6"
    private var activeReadOnly: Boolean = false
    private var pendingLoadMarkdown: String? = null

    private var onMarkdownChanged: ((String) -> Unit)? = null
    private var onTypingActivity: (() -> Unit)? = null
    private var onSelectionState: ((EditorSelectionState) -> Unit)? = null
    private var onHeadingsChanged: ((List<HeadingItem>) -> Unit)? = null
    private var onLinkClicked: ((String) -> Unit)? = null
    private var onImageClicked: ((String) -> Unit)? = null
    private var onRequestEditMode: (() -> Unit)? = null

    fun prewarm(isDark: Boolean, accentHex: String) {
        mainHandler.post {
            ensureCreated(appContext, isDark, accentHex)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureCreated(hostContext: Context, isDark: Boolean, accentHex: String): WebView {
        val existing = webView
        if (existing != null) {
            contextWrapper?.baseContext = hostContext
            return existing
        }

        val wrapper = MutableContextWrapper(hostContext)
        contextWrapper = wrapper
        activeDarkTheme = isDark
        activeAccentHex = accentHex

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
            .build()

        val wv = WebView(wrapper).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(if (isDark) 0xFF111319.toInt() else 0xFFFFFFFF.toInt())
            overScrollMode = WebView.OVER_SCROLL_NEVER

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                builtInZoomControls = false
                displayZoomControls = false
            }

            val bridge = object {
                @JavascriptInterface
                fun onReady() {
                    isReady = true
                    mainHandler.post {
                        applyThemeInternal(activeDarkTheme)
                        applyAccentInternal(activeAccentHex)
                        applyReadOnlyInternal(activeReadOnly)
                        pendingLoadMarkdown?.let { md ->
                            loadDocumentInternal(md)
                            pendingLoadMarkdown = null
                        }
                    }
                }

                @JavascriptInterface
                fun onContentChanged(markdown: String) {
                    mainHandler.post {
                        onMarkdownChanged?.invoke(markdown)
                    }
                }

                @JavascriptInterface
                fun onTypingActivity() {
                    mainHandler.post {
                        onTypingActivity?.invoke()
                    }
                }

                @JavascriptInterface
                fun onSelectionState(stateJson: String) {
                    runCatching {
                        json.decodeFromString(EditorSelectionState.serializer(), stateJson)
                    }.onSuccess { parsed ->
                        mainHandler.post {
                            onSelectionState?.invoke(parsed)
                        }
                    }
                }

                @JavascriptInterface
                fun onHeadingsChanged(headingsJson: String) {
                    runCatching {
                        json.decodeFromString(ListSerializer(HeadingItem.serializer()), headingsJson)
                    }.onSuccess { parsed ->
                        mainHandler.post {
                            onHeadingsChanged?.invoke(parsed)
                        }
                    }
                }

                @JavascriptInterface
                fun onLinkClicked(url: String) {
                    mainHandler.post {
                        onLinkClicked?.invoke(url)
                    }
                }

                @JavascriptInterface
                fun onImageClicked(src: String) {
                    mainHandler.post {
                        onImageClicked?.invoke(src)
                    }
                }

                @JavascriptInterface
                fun onRequestEditMode() {
                    mainHandler.post {
                        onRequestEditMode?.invoke()
                    }
                }
            }

            addJavascriptInterface(bridge, "OutlineNative")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val uri = request.url
                    val host = uri.host.orEmpty()
                    val path = uri.path.orEmpty()

                    if (host == SyncEngine.OFFLINE_MEDIA_HOST && path.startsWith("/offline-media/")) {
                        val fileName = path.removePrefix("/offline-media/").substringBefore('/')
                        val file = File(offlineMediaDir, fileName)
                        if (file.exists()) {
                            return WebResourceResponse(guessMime(fileName), null, FileInputStream(file))
                        }
                    }

                    if (host == "appassets.androidplatform.net" && path.startsWith("/assets/")) {
                        return assetLoader.shouldInterceptRequest(uri)
                    }

                    val srv = currentServerUrl
                    val key = currentApiKey
                    val isOutlineAttachment = path.startsWith("/api/attachments.redirect") ||
                        path.startsWith("/api/files.get") ||
                        (srv.isNotBlank() && uri.toString().startsWith(srv) && path.contains("/api/attachments"))

                    if (isOutlineAttachment) {
                        val targetPathOrUrl = if (host == "appassets.androidplatform.net") {
                            val queryPart = uri.encodedQuery?.let { "?$it" }.orEmpty()
                            "$path$queryPart"
                        } else {
                            uri.toString()
                        }
                        val cached = apiClient.fetchAndCacheMediaBlocking(
                            serverUrl = srv,
                            apiKey = key,
                            rawUrl = targetPathOrUrl,
                        )
                        if (cached != null) {
                            val stream = cached.file?.let { FileInputStream(it) }
                                ?: cached.bytes?.let { ByteArrayInputStream(it) }
                            if (stream != null) {
                                return WebResourceResponse(cached.mimeType, null, stream)
                            }
                        }
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url.toString()
                    if (!url.startsWith("https://appassets.androidplatform.net/assets/")) {
                        mainHandler.post { onLinkClicked?.invoke(url) }
                        return true
                    }
                    return false
                }
            }

            val themeParam = if (isDark) "dark" else "light"
            val accentEncoded = Uri.encode(accentHex)
            loadUrl("https://appassets.androidplatform.net/assets/editor/index.html?theme=$themeParam&accent=$accentEncoded")
        }

        webView = wv
        return wv
    }

    fun acquire(
        activityContext: Context,
        serverUrl: String,
        apiKey: String,
        isDark: Boolean,
        accentHex: String,
        readOnly: Boolean,
        onMarkdownChanged: (String) -> Unit,
        onTypingActivity: () -> Unit,
        onSelectionState: (EditorSelectionState) -> Unit,
        onHeadingsChanged: (List<HeadingItem>) -> Unit,
        onLinkClicked: (String) -> Unit,
        onImageClicked: (String) -> Unit,
        onRequestEditMode: () -> Unit,
    ): WebView {
        currentServerUrl = serverUrl
        currentApiKey = apiKey
        this.onMarkdownChanged = onMarkdownChanged
        this.onTypingActivity = onTypingActivity
        this.onSelectionState = onSelectionState
        this.onHeadingsChanged = onHeadingsChanged
        this.onLinkClicked = onLinkClicked
        this.onImageClicked = onImageClicked
        this.onRequestEditMode = onRequestEditMode

        val wv = ensureCreated(activityContext, isDark, accentHex)
        (wv.parent as? ViewGroup)?.removeView(wv)
        setTheme(isDark)
        setAccentColor(accentHex)
        setReadOnly(readOnly)
        wv.onResume()
        return wv
    }

    fun release(container: ViewGroup, ownerCallback: (String) -> Unit) {
        val wv = webView ?: return
        if (wv.parent === container) {
            container.removeView(wv)
        }
        if (onMarkdownChanged === ownerCallback) {
            onMarkdownChanged = null
            onTypingActivity = null
            onSelectionState = null
            onHeadingsChanged = null
            onLinkClicked = null
            onImageClicked = null
            onRequestEditMode = null
            contextWrapper?.baseContext = appContext
        }
    }

    fun loadDocument(markdown: String) {
        if (isReady) {
            pendingLoadMarkdown = null
            loadDocumentInternal(markdown)
        } else {
            pendingLoadMarkdown = markdown
        }
    }

    private fun loadDocumentInternal(markdown: String) {
        val escaped = JSONObject.quote(markdown)
        webView?.post {
            webView?.evaluateJavascript(
                "window.OutlineEditor && (window.OutlineEditor.loadDocument ? window.OutlineEditor.loadDocument($escaped) : window.OutlineEditor.setMarkdown($escaped));",
                null,
            )
        }
    }

    fun setTheme(isDark: Boolean) {
        activeDarkTheme = isDark
        applyThemeInternal(isDark)
    }

    private fun applyThemeInternal(isDark: Boolean) {
        webView?.post {
            webView?.setBackgroundColor(if (isDark) 0xFF111319.toInt() else 0xFFFFFFFF.toInt())
            if (isReady) {
                webView?.evaluateJavascript("window.OutlineEditor && window.OutlineEditor.setTheme($isDark);", null)
            }
        }
    }

    fun setAccentColor(hex: String) {
        activeAccentHex = hex
        applyAccentInternal(hex)
    }

    private fun applyAccentInternal(hex: String) {
        if (!isReady) return
        val escaped = JSONObject.quote(hex)
        webView?.post {
            webView?.evaluateJavascript("window.OutlineEditor && window.OutlineEditor.setAccentColor($escaped);", null)
        }
    }

    fun setReadOnly(readOnly: Boolean) {
        activeReadOnly = readOnly
        applyReadOnlyInternal(readOnly)
    }

    private fun applyReadOnlyInternal(readOnly: Boolean) {
        if (!isReady) return
        webView?.post {
            webView?.evaluateJavascript("window.OutlineEditor && window.OutlineEditor.setReadOnly($readOnly);", null)
        }
    }

    fun focusEditor() {
        if (!isReady) return
        webView?.post {
            webView?.requestFocus()
            webView?.evaluateJavascript("window.OutlineEditor && window.OutlineEditor.focusEditor();", null)
        }
    }

    fun scrollToHeading(index: Int) {
        if (!isReady) return
        webView?.post {
            webView?.evaluateJavascript("window.OutlineEditor && window.OutlineEditor.scrollToHeading($index);", null)
        }
    }

    fun execCommand(command: String, arg: String? = null) {
        if (!isReady) return
        val cmdQuoted = JSONObject.quote(command)
        val argPart = if (arg != null) ", ${JSONObject.quote(arg)}" else ""
        webView?.post {
            webView?.evaluateJavascript(
                "window.OutlineEditor && window.OutlineEditor.execCommand($cmdQuoted$argPart);",
                null,
            )
        }
    }

    fun insertImage(url: String, alt: String = "") {
        if (!isReady) return
        val urlQuoted = JSONObject.quote(url)
        val altQuoted = JSONObject.quote(alt)
        webView?.post {
            webView?.evaluateJavascript(
                "window.OutlineEditor && window.OutlineEditor.insertImage($urlQuoted, $altQuoted);",
                null,
            )
        }
    }

    private fun guessMime(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".svg") -> "image/svg+xml"
            else -> "image/jpeg"
        }
    }
}
