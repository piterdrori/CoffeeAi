package com.personaledge.ai.sync

import java.security.SecureRandom

/**
 * Pure, Android-framework-free rules for the Stage 1 device identity so install-id generation,
 * validation, the "needs registration" decision, and the bearer header can be unit tested without
 * a device. Persistence (DataStore) and networking live in [SyncClient].
 */
object DeviceIdentityLogic {
    private val INSTALL_ID_RE = Regex("^[A-Za-z0-9_-]{16,128}$")
    private val random = SecureRandom()
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** A cryptographically-random, URL-safe install id (server validates ^[A-Za-z0-9_-]{16,128}$). */
    fun newInstallId(): String {
        val sb = StringBuilder("coffee-")
        repeat(32) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    fun isValidInstallId(id: String?): Boolean = id != null && INSTALL_ID_RE.matches(id)

    /** Registration is needed only when no device token has been stored yet. */
    fun needsRegistration(deviceToken: String?): Boolean = deviceToken.isNullOrBlank()

    /** Builds the Authorization header value for a device token, or null when there is none. */
    fun bearerHeader(deviceToken: String?): String? =
        deviceToken?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
}
