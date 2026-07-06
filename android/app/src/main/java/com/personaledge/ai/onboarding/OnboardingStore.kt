package com.personaledge.ai.onboarding

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.onboardingDataStore by preferencesDataStore("onboarding_prefs")

class OnboardingStore(private val context: Context) {
    private val welcomeCompleteKey = booleanPreferencesKey("welcome_complete")

    val welcomeComplete: Flow<Boolean> = context.onboardingDataStore.data.map {
        it[welcomeCompleteKey] == true
    }

    suspend fun isWelcomeComplete(): Boolean = welcomeComplete.first()

    suspend fun markWelcomeComplete() {
        context.onboardingDataStore.edit { prefs ->
            prefs[welcomeCompleteKey] = true
        }
    }
}
