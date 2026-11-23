package com.outline.notes

import android.app.Application
import com.outline.notes.data.local.OutlineDatabase
import com.outline.notes.data.local.WorkspaceStore
import com.outline.notes.data.prefs.AppPreferences
import com.outline.notes.data.remote.OutlineApiClient
import com.outline.notes.data.sync.NetworkMonitor
import com.outline.notes.data.sync.SyncEngine
import com.outline.notes.data.sync.SyncWorker
import com.outline.notes.ui.components.EditorWebViewPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class OutlineApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var preferences: AppPreferences
        private set
    lateinit var database: OutlineDatabase
        private set
    lateinit var apiClient: OutlineApiClient
        private set
    lateinit var networkMonitor: NetworkMonitor
        private set
    lateinit var syncEngine: SyncEngine
        private set
    lateinit var workspaceStore: WorkspaceStore
        private set
    lateinit var editorWebViewPool: EditorWebViewPool
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        database = OutlineDatabase.getInstance(this)
        workspaceStore = WorkspaceStore(database.outlineDao(), appScope)
        val mediaCacheDir = File(cacheDir, "media_cache").apply { mkdirs() }
        apiClient = OutlineApiClient(mediaCacheDir = mediaCacheDir)
        networkMonitor = NetworkMonitor(this)
        syncEngine = SyncEngine(
            context = this,
            dao = database.outlineDao(),
            api = apiClient,
            preferences = preferences,
            networkMonitor = networkMonitor,
        )
        editorWebViewPool = EditorWebViewPool(
            appContext = this,
            apiClient = apiClient,
            offlineMediaDir = syncEngine.offlineMediaDir,
        )

        networkMonitor.setOnReconnectListener {
            SyncWorker.scheduleWhenConnected(this)
        }

        appScope.launch {
            val settings = preferences.getSettings()
            SyncWorker.configurePeriodicSync(this@OutlineApp, settings.isConfigured && settings.periodicSyncEnabled)
        }
    }
}
