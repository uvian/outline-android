package com.outline.notes

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.outline.notes.data.prefs.ThemeMode
import com.outline.notes.data.prefs.UserSettings
import com.outline.notes.ui.components.BiometricLockOverlay
import com.outline.notes.ui.screens.EditorScreen
import com.outline.notes.ui.screens.HomeScreen
import com.outline.notes.ui.screens.SearchScreen
import com.outline.notes.ui.screens.SettingsScreen
import com.outline.notes.ui.screens.SetupScreen
import com.outline.notes.ui.theme.OutlineTheme
import kotlinx.coroutines.delay

private object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val EDITOR = "editor/{docId}"
    fun editor(docId: String) = "editor/$docId"
}

sealed class ExternalLaunchAction {
    data class Share(val title: String? = null, val text: String? = null, val imageUri: Uri? = null) : ExternalLaunchAction()
    data object QuickCreate : ExternalLaunchAction()
    data class OpenDocument(val docId: String) : ExternalLaunchAction()
}

class MainActivity : FragmentActivity() {

    private var pendingAction by mutableStateOf<ExternalLaunchAction?>(null)
    private var isBiometricUnlocked by mutableStateOf(false)
    private var hasPromptedBiometric by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingAction = extractExternalAction(intent)

        val app = application as OutlineApp
        setContent {
            val settingsState by app.preferences.settingsFlow.collectAsState(initial = null)
            val currentSettings = settingsState
            val themeMode = currentSettings?.themeMode ?: ThemeMode.SYSTEM
            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val accentHex = currentSettings?.effectiveAccentColor ?: UserSettings.DEFAULT_ACCENT_HEX

            OutlineTheme(
                themeMode = themeMode,
                accentHex = accentHex,
            ) {
                val bgColor = MaterialTheme.colorScheme.background

                // Sync native Window background & system bars so NO white edge flash ever appears
                LaunchedEffect(isDark, bgColor) {
                    window.setBackgroundDrawable(ColorDrawable(bgColor.toArgb()))
                    enableEdgeToEdge(
                        statusBarStyle = if (isDark) {
                            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                            )
                        },
                        navigationBarStyle = if (isDark) {
                            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                            )
                        },
                    )
                }

                // Only enforce biometric lock if it was already enabled on app launch
                // (so toggling it ON in Settings won't abruptly lock you mid-session)
                var initializedLockState by remember { mutableStateOf(false) }
                LaunchedEffect(currentSettings != null) {
                    if (currentSettings != null && !initializedLockState) {
                        initializedLockState = true
                        if (!currentSettings.biometricLockEnabled) {
                            isBiometricUnlocked = true
                        }
                    }
                }

                val requiresLock = initializedLockState &&
                    currentSettings?.isConfigured == true &&
                    currentSettings.biometricLockEnabled &&
                    !isBiometricUnlocked

                LaunchedEffect(requiresLock) {
                    if (requiresLock && !hasPromptedBiometric) {
                        hasPromptedBiometric = true
                        triggerBiometricPrompt()
                    }
                }

                // Pre-warm offline Tiptap editor quietly after initial frame settles
                LaunchedEffect(currentSettings?.isConfigured, requiresLock) {
                    if (currentSettings?.isConfigured == true && !requiresLock) {
                        delay(300)
                        app.editorWebViewPool.prewarm(isDark = isDark, accentHex = accentHex)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor),
                ) {
                    if (currentSettings != null) {
                        if (requiresLock) {
                            BiometricLockOverlay(
                                workspaceName = currentSettings.workspaceName,
                                onUnlockClick = { triggerBiometricPrompt() },
                            )
                        } else {
                            OutlineNotesNavHost(
                                app = app,
                                settings = currentSettings,
                                launchAction = pendingAction,
                                onConsumeAction = { pendingAction = null },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingAction = extractExternalAction(intent)
    }

    private fun triggerBiometricPrompt() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val bm = BiometricManager.from(this)
        if (bm.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            isBiometricUnlocked = true
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isBiometricUnlocked = true
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁 Outline Notes")
            .setSubtitle("验证身份以访问你的私人笔记")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    private fun extractExternalAction(intent: Intent?): ExternalLaunchAction? {
        if (intent == null) return null
        return when (intent.action) {
            ACTION_QUICK_CREATE -> ExternalLaunchAction.QuickCreate
            ACTION_OPEN_DOC -> {
                val id = intent.getStringExtra(EXTRA_DOC_ID)
                if (!id.isNullOrBlank()) ExternalLaunchAction.OpenDocument(id) else null
            }

            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                when {
                    type.startsWith("text/") -> {
                        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                        val body = intent.getStringExtra(Intent.EXTRA_TEXT)
                        if (!body.isNullOrBlank()) ExternalLaunchAction.Share(title = subject, text = body) else null
                    }

                    type.startsWith("image/") -> {
                        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_STREAM)
                        }
                        if (uri != null) ExternalLaunchAction.Share(title = "剪藏图片", imageUri = uri) else null
                    }

                    else -> null
                }
            }

            else -> null
        }
    }

    companion object {
        const val ACTION_QUICK_CREATE = "com.outline.notes.ACTION_QUICK_CREATE"
        const val ACTION_OPEN_DOC = "com.outline.notes.ACTION_OPEN_DOC"
        const val EXTRA_DOC_ID = "extra_doc_id"
    }
}

@Composable
private fun OutlineNotesNavHost(
    app: OutlineApp,
    settings: UserSettings,
    launchAction: ExternalLaunchAction?,
    onConsumeAction: () -> Unit,
) {
    val navController = rememberNavController()
    val startDestination = remember {
        if (settings.isConfigured) Routes.HOME else Routes.SETUP
    }

    // Handle Android Share Sheet & Home Screen Widget deep actions (works offline!)
    LaunchedEffect(launchAction, settings.isConfigured) {
        val action = launchAction ?: return@LaunchedEffect
        if (!settings.isConfigured) return@LaunchedEffect
        onConsumeAction()

        when (action) {
            ExternalLaunchAction.QuickCreate -> {
                val created = app.syncEngine.createLocalDocument(
                    collectionId = settings.defaultCollectionId.takeIf { it.isNotBlank() },
                )
                navController.navigate(Routes.editor(created.id))
            }

            is ExternalLaunchAction.OpenDocument -> {
                navController.navigate(Routes.editor(action.docId))
            }

            is ExternalLaunchAction.Share -> {
                val created = app.syncEngine.createLocalDocument(
                    title = action.title?.take(80).orEmpty().ifBlank { "快速剪藏" },
                    text = action.text.orEmpty(),
                    collectionId = settings.defaultCollectionId.takeIf { it.isNotBlank() },
                )
                if (action.imageUri != null) {
                    val imgRes = app.syncEngine.attachImage(created.id, action.imageUri)
                    imgRes.onSuccess { url ->
                        app.syncEngine.saveLocalEdit(created.id, created.title, "![剪藏图片]($url)\n")
                    }
                }
                navController.navigate(Routes.editor(created.id))
            }
        }
    }

    // Material 3 Z-axis scale + fade: silky 60/120fps, zero horizontal WebView tearing,
    // and direction-neutral whether swiping back from left edge OR right edge!
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = {
            fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
                scaleIn(
                    initialScale = 0.965f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(160, easing = FastOutSlowInEasing), targetAlpha = 0.88f) +
                scaleOut(
                    targetScale = 0.985f,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                )
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing), initialAlpha = 0.88f) +
                scaleIn(
                    initialScale = 0.985f,
                    animationSpec = tween(210, easing = FastOutSlowInEasing),
                )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(160, easing = FastOutSlowInEasing)) +
                scaleOut(
                    targetScale = 0.965f,
                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                )
        },
    ) {
        composable(Routes.SETUP) {
            SetupScreen(
                initialServerUrl = settings.serverUrl,
                initialApiKey = settings.apiKey,
                apiClient = app.apiClient,
                preferences = app.preferences,
                syncEngine = app.syncEngine,
                onConnected = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                settings = settings,
                workspaceStore = app.workspaceStore,
                apiClient = app.apiClient,
                syncEngine = app.syncEngine,
                networkMonitor = app.networkMonitor,
                onOpenDocument = { docId ->
                    navController.navigate(Routes.editor(docId))
                },
                onOpenSearch = {
                    navController.navigate(Routes.SEARCH)
                },
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                syncEngine = app.syncEngine,
                onBack = { navController.popBackStack() },
                onOpenDocument = { docId ->
                    navController.navigate(Routes.editor(docId))
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = settings,
                preferences = app.preferences,
                dao = app.database.outlineDao(),
                workspaceStore = app.workspaceStore,
                apiClient = app.apiClient,
                syncEngine = app.syncEngine,
                onBack = { navController.popBackStack() },
                onReconfigureServer = {
                    navController.navigate(Routes.SETUP) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("docId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getString("docId") ?: return@composable
            EditorScreen(
                documentId = docId,
                app = app,
                settings = settings,
                dao = app.database.outlineDao(),
                syncEngine = app.syncEngine,
                networkMonitor = app.networkMonitor,
                onBack = { navController.popBackStack() },
                onOpenDocument = { childId ->
                    navController.navigate(Routes.editor(childId))
                },
            )
        }
    }
}
