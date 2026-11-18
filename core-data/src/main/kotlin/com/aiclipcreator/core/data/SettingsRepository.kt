package com.aiclipcreator.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.models.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(name = "ai_clip_settings")

/** Single source of truth for user preferences, serialized as one JSON document. */
class SettingsRepository(private val context: Context) {

    private val key = stringPreferencesKey("settings_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[key] ?: return@map UserSettings()
        runCatching { json.decodeFromString<UserSettings>(raw) }.getOrElse {
            Logx.w("Settings corrupted, resetting: ${it.message}")
            UserSettings()
        }
    }

    suspend fun current(): UserSettings = settings.first()

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[key]?.let { raw ->
                runCatching { json.decodeFromString<UserSettings>(raw) }.getOrDefault(UserSettings())
            } ?: UserSettings()
            prefs[key] = json.encodeToString(UserSettings.serializer(), transform(current))
        }
    }

    suspend fun reset() {
        context.settingsDataStore.edit { it.remove(key) }
    }
}
