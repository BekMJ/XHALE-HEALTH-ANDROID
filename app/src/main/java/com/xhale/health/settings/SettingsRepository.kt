package com.xhale.health.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    companion object {
        val KEY_SAMPLE_DURATION = intPreferencesKey("sample_duration_sec")
    }

    val sampleDuration: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_SAMPLE_DURATION] ?: 15
    }

    suspend fun setSampleDuration(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SAMPLE_DURATION] = seconds.coerceIn(5, 60)
        }
    }
}


