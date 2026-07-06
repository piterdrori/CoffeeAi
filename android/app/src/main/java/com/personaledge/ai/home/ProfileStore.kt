package com.personaledge.ai.home

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.personaledge.ai.homeDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CoffeePreferences(
    val favoriteDrink: String = "",
    val roastLevel: String = "",
    val brewMethod: String = "",
    val milkPreference: String = "",
    val strength: String = "",
) {
    val summary: String
        get() = favoriteDrink.ifBlank {
            listOf(roastLevel, brewMethod).filter { it.isNotBlank() }.joinToString(" · ")
        }
}

data class UserProfile(
    val displayName: String = "",
    val email: String = "",
    val avatarPath: String = "",
    val coffeePreferences: CoffeePreferences = CoffeePreferences(),
    val memberSinceMillis: Long = 0L,
) {
    val isRegistered: Boolean get() = displayName.isNotBlank()

    val memberSinceLabel: String
        get() = if (memberSinceMillis <= 0L) {
            ""
        } else {
            SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(memberSinceMillis))
        }

    val initials: String
        get() {
            val parts = displayName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            return when {
                parts.isEmpty() -> "☕"
                parts.size == 1 -> parts[0].take(2).uppercase()
                else -> "${parts.first().first()}${parts.last().first()}".uppercase()
            }
        }
}

class ProfileStore(private val context: Context) {
    private val nameKey = stringPreferencesKey("profile_display_name")
    private val emailKey = stringPreferencesKey("profile_email")
    private val avatarKey = stringPreferencesKey("profile_avatar_path")
    private val drinkKey = stringPreferencesKey("profile_favorite_drink")
    private val roastKey = stringPreferencesKey("profile_roast_level")
    private val brewKey = stringPreferencesKey("profile_brew_method")
    private val milkKey = stringPreferencesKey("profile_milk_preference")
    private val strengthKey = stringPreferencesKey("profile_strength")
    private val memberSinceKey = longPreferencesKey("profile_member_since")

    val profile: Flow<UserProfile> = context.homeDataStore.data.map { prefs ->
        UserProfile(
            displayName = prefs[nameKey].orEmpty(),
            email = prefs[emailKey].orEmpty(),
            avatarPath = prefs[avatarKey].orEmpty(),
            coffeePreferences = CoffeePreferences(
                favoriteDrink = prefs[drinkKey].orEmpty(),
                roastLevel = prefs[roastKey].orEmpty(),
                brewMethod = prefs[brewKey].orEmpty(),
                milkPreference = prefs[milkKey].orEmpty(),
                strength = prefs[strengthKey].orEmpty(),
            ),
            memberSinceMillis = prefs[memberSinceKey] ?: 0L,
        )
    }

    suspend fun savePersonalDetails(displayName: String, email: String, avatarPath: String) {
        context.homeDataStore.edit { prefs ->
            val existingSince = prefs[memberSinceKey] ?: 0L
            prefs[nameKey] = displayName.trim()
            prefs[emailKey] = email.trim()
            if (avatarPath.isNotBlank()) {
                prefs[avatarKey] = avatarPath
            }
            if (displayName.isNotBlank() && existingSince <= 0L) {
                prefs[memberSinceKey] = System.currentTimeMillis()
            }
        }
    }

    suspend fun saveCoffeePreferences(preferences: CoffeePreferences) {
        context.homeDataStore.edit { prefs ->
            prefs[drinkKey] = preferences.favoriteDrink
            prefs[roastKey] = preferences.roastLevel
            prefs[brewKey] = preferences.brewMethod
            prefs[milkKey] = preferences.milkPreference
            prefs[strengthKey] = preferences.strength
        }
    }
}
