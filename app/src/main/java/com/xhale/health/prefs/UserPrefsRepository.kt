package com.xhale.health.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.userPrefs by preferencesDataStore(name = "user_prefs")

class UserPrefsRepository(private val context: Context) {
    companion object {
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val KEY_DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
    }

    val onboardingDone: Flow<Boolean> = context.userPrefs.data.map { it[KEY_ONBOARDING_DONE] ?: false }
    val disclaimerAccepted: Flow<Boolean> = context.userPrefs.data.map { it[KEY_DISCLAIMER_ACCEPTED] ?: false }

    suspend fun setOnboardingDone(done: Boolean) {
        context.userPrefs.edit { prefs -> prefs[KEY_ONBOARDING_DONE] = done }
    }

    suspend fun setDisclaimerAccepted(accepted: Boolean) {
        context.userPrefs.edit { prefs -> prefs[KEY_DISCLAIMER_ACCEPTED] = accepted }
    }
}


