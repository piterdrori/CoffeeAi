package com.personaledge.ai.home

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.personaledge.ai.homeDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** User's coffee bean setup — CoffeeAI uses this to tune recipes and brew commands. */
data class CoffeePreferences(
    val beanName: String = "",
    val beanType: String = "",
    val origin: String = "",
    val roastLevel: String = "",
    val notes: String = "",
) {
    val isConfigured: Boolean get() = beanName.isNotBlank()

    val summary: String
        get() = listOf(beanName, roastLevel, beanType)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    /** Structured block appended to machine brew commands for the AI backend. */
    fun machineContextBlock(): String? {
        if (!isConfigured && roastLevel.isBlank() && origin.isBlank()) return null
        return buildString {
            appendLine("[USER_BEANS]")
            if (beanName.isNotBlank()) appendLine("name: $beanName")
            if (beanType.isNotBlank()) appendLine("type: $beanType")
            if (origin.isNotBlank()) appendLine("origin: $origin")
            if (roastLevel.isNotBlank()) appendLine("roast: $roastLevel")
            if (notes.isNotBlank()) appendLine("notes: $notes")
        }.trimEnd()
    }

    /** Short line injected into offline memory so chat/voice always knows the user's beans. */
    fun memoryChunk(): String? {
        if (!isConfigured) return null
        val parts = buildList {
            add(beanName)
            beanType.takeIf { it.isNotBlank() }?.let { add(it) }
            roastLevel.takeIf { it.isNotBlank() }?.let { add("$it roast") }
            origin.takeIf { it.isNotBlank() }?.let { add("from $it") }
        }
        return "User brews with: ${parts.joinToString(", ")}." +
            if (notes.isNotBlank()) " Notes: $notes." else ""
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
    private val beanNameKey = stringPreferencesKey("profile_bean_name")
    private val beanTypeKey = stringPreferencesKey("profile_bean_type")
    private val beanOriginKey = stringPreferencesKey("profile_bean_origin")
    private val roastKey = stringPreferencesKey("profile_roast_level")
    private val beanNotesKey = stringPreferencesKey("profile_bean_notes")
    private val memberSinceKey = longPreferencesKey("profile_member_since")

    val profile: Flow<UserProfile> = context.homeDataStore.data.map { prefs ->
        UserProfile(
            displayName = prefs[nameKey].orEmpty(),
            email = prefs[emailKey].orEmpty(),
            avatarPath = prefs[avatarKey].orEmpty(),
            coffeePreferences = CoffeePreferences(
                beanName = prefs[beanNameKey].orEmpty(),
                beanType = prefs[beanTypeKey].orEmpty(),
                origin = prefs[beanOriginKey].orEmpty(),
                roastLevel = prefs[roastKey].orEmpty(),
                notes = prefs[beanNotesKey].orEmpty(),
            ),
            memberSinceMillis = prefs[memberSinceKey] ?: 0L,
        )
    }

    suspend fun currentCoffeePreferences(): CoffeePreferences =
        profile.first().coffeePreferences

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
            prefs[beanNameKey] = preferences.beanName.trim()
            prefs[beanTypeKey] = preferences.beanType
            prefs[beanOriginKey] = preferences.origin.trim()
            prefs[roastKey] = preferences.roastLevel
            prefs[beanNotesKey] = preferences.notes.trim()
        }
    }
}
