package com.outline.notes.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.outline.notes.data.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "outline_prefs")

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

enum class EditorMode {
    RICH_TEXT, MARKDOWN
}

data class UserSettings(
    val serverUrl: String = "",
    val apiKey: String = "",
    val userName: String = "",
    val userEmail: String = "",
    val workspaceName: String = "",
    val workspaceLogoUrl: String = "",
    val serverAccentColor: String = "",
    val customAccentColor: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val defaultEditorMode: EditorMode = EditorMode.RICH_TEXT,
    val openInReadMode: Boolean = true,
    val compressImagesOnUpload: Boolean = true,
    val biometricLockEnabled: Boolean = false,
    val periodicSyncEnabled: Boolean = true,
    val lastSyncAt: Long = 0L,
    val defaultCollectionId: String = "",
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

    val effectiveAccentColor: String
        get() = customAccentColor.takeIf { isValidHexColor(it) }
            ?: serverAccentColor.takeIf { isValidHexColor(it) }
            ?: DEFAULT_ACCENT_HEX

    companion object {
        const val DEFAULT_ACCENT_HEX = "#0366D6"

        fun isValidHexColor(hex: String): Boolean {
            return Regex("^#[0-9a-fA-F]{6}$").matches(hex.trim())
        }
    }
}

class AppPreferences(private val context: Context) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_KEY = stringPreferencesKey("api_key")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val WORKSPACE_NAME = stringPreferencesKey("workspace_name")
        val WORKSPACE_LOGO_URL = stringPreferencesKey("workspace_logo_url")
        val SERVER_ACCENT_COLOR = stringPreferencesKey("server_accent_color")
        val CUSTOM_ACCENT_COLOR = stringPreferencesKey("custom_accent_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val EDITOR_MODE = stringPreferencesKey("editor_mode")
        val OPEN_IN_READ_MODE = booleanPreferencesKey("open_in_read_mode")
        val COMPRESS_IMAGES = booleanPreferencesKey("compress_images")
        val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
        val PERIODIC_SYNC = booleanPreferencesKey("periodic_sync")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val DEFAULT_COLLECTION_ID = stringPreferencesKey("default_collection_id")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            serverUrl = prefs[Keys.SERVER_URL].orEmpty(),
            apiKey = SecretCipher.decrypt(prefs[Keys.API_KEY].orEmpty()),
            userName = prefs[Keys.USER_NAME].orEmpty(),
            userEmail = prefs[Keys.USER_EMAIL].orEmpty(),
            workspaceName = prefs[Keys.WORKSPACE_NAME].orEmpty(),
            workspaceLogoUrl = prefs[Keys.WORKSPACE_LOGO_URL].orEmpty(),
            serverAccentColor = prefs[Keys.SERVER_ACCENT_COLOR].orEmpty(),
            customAccentColor = prefs[Keys.CUSTOM_ACCENT_COLOR].orEmpty(),
            themeMode = runCatching {
                ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name)
            }.getOrDefault(ThemeMode.SYSTEM),
            defaultEditorMode = runCatching {
                EditorMode.valueOf(prefs[Keys.EDITOR_MODE] ?: EditorMode.RICH_TEXT.name)
            }.getOrDefault(EditorMode.RICH_TEXT),
            openInReadMode = prefs[Keys.OPEN_IN_READ_MODE] ?: true,
            compressImagesOnUpload = prefs[Keys.COMPRESS_IMAGES] ?: true,
            biometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK] ?: false,
            periodicSyncEnabled = prefs[Keys.PERIODIC_SYNC] ?: true,
            lastSyncAt = prefs[Keys.LAST_SYNC_AT] ?: 0L,
            defaultCollectionId = prefs[Keys.DEFAULT_COLLECTION_ID].orEmpty(),
        )
    }

    suspend fun getSettings(): UserSettings = settingsFlow.first()

    suspend fun saveConnection(
        serverUrl: String,
        apiKey: String,
        userName: String,
        userEmail: String,
        workspaceName: String,
        workspaceLogoUrl: String = "",
        serverAccentColor: String = "",
    ) {
        val normalizedUrl = normalizeServerUrl(serverUrl)
        val encryptedKey = SecretCipher.encrypt(apiKey.trim())
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = normalizedUrl
            prefs[Keys.API_KEY] = encryptedKey
            prefs[Keys.USER_NAME] = userName
            prefs[Keys.USER_EMAIL] = userEmail
            prefs[Keys.WORKSPACE_NAME] = workspaceName
            prefs[Keys.WORKSPACE_LOGO_URL] = workspaceLogoUrl
            prefs[Keys.SERVER_ACCENT_COLOR] = normalizeHex(serverAccentColor)
        }
    }

    suspend fun updateWorkspaceBranding(
        workspaceName: String,
        workspaceLogoUrl: String,
        serverAccentColor: String,
    ) {
        context.dataStore.edit { prefs ->
            if (workspaceName.isNotBlank()) prefs[Keys.WORKSPACE_NAME] = workspaceName
            prefs[Keys.WORKSPACE_LOGO_URL] = workspaceLogoUrl
            prefs[Keys.SERVER_ACCENT_COLOR] = normalizeHex(serverAccentColor)
        }
    }

    suspend fun setCustomAccentColor(hex: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CUSTOM_ACCENT_COLOR] = if (hex.isBlank()) "" else normalizeHex(hex)
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDefaultEditorMode(mode: EditorMode) {
        context.dataStore.edit { it[Keys.EDITOR_MODE] = mode.name }
    }

    suspend fun setOpenInReadMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OPEN_IN_READ_MODE] = enabled }
    }

    suspend fun setCompressImagesOnUpload(enabled: Boolean) {
        context.dataStore.edit { it[Keys.COMPRESS_IMAGES] = enabled }
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BIOMETRIC_LOCK] = enabled }
    }

    suspend fun setPeriodicSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PERIODIC_SYNC] = enabled }
    }

    suspend fun setLastSyncAt(epochMillis: Long) {
        context.dataStore.edit { it[Keys.LAST_SYNC_AT] = epochMillis }
    }

    suspend fun setDefaultCollectionId(collectionId: String) {
        context.dataStore.edit { it[Keys.DEFAULT_COLLECTION_ID] = collectionId }
    }

    suspend fun clearConnection() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.SERVER_URL)
            prefs.remove(Keys.API_KEY)
            prefs.remove(Keys.USER_NAME)
            prefs.remove(Keys.USER_EMAIL)
            prefs.remove(Keys.WORKSPACE_NAME)
            prefs.remove(Keys.WORKSPACE_LOGO_URL)
            prefs.remove(Keys.SERVER_ACCENT_COLOR)
            prefs.remove(Keys.LAST_SYNC_AT)
            prefs.remove(Keys.DEFAULT_COLLECTION_ID)
        }
    }

    companion object {
        fun normalizeServerUrl(raw: String): String {
            var trimmed = raw.trim().removeSuffix("/")
            if (trimmed.isNotEmpty() && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                trimmed = "https://$trimmed"
            }
            if (trimmed.endsWith("/api")) {
                trimmed = trimmed.removeSuffix("/api")
            }
            return trimmed
        }

        fun normalizeHex(raw: String?): String {
            val cleaned = raw?.trim().orEmpty()
            if (cleaned.isEmpty()) return ""
            val withHash = if (cleaned.startsWith("#")) cleaned else "#$cleaned"
            return if (UserSettings.isValidHexColor(withHash)) withHash.uppercase() else ""
        }
    }
}
