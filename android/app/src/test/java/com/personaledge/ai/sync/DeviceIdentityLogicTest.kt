package com.personaledge.ai.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure device-identity rules backing Stage 1 registration and bearer auth.
 */
class DeviceIdentityLogicTest {

    // 13 (support) — a freshly generated install id is valid and reusable across launches.
    @Test
    fun newInstallId_isValidAndUnique() {
        val a = DeviceIdentityLogic.newInstallId()
        val b = DeviceIdentityLogic.newInstallId()
        assertTrue(DeviceIdentityLogic.isValidInstallId(a))
        assertTrue(DeviceIdentityLogic.isValidInstallId(b))
        assertFalse(a == b)
    }

    @Test
    fun isValidInstallId_enforcesCharsetAndLength() {
        assertTrue(DeviceIdentityLogic.isValidInstallId("install-abcdef0123456789"))
        assertFalse(DeviceIdentityLogic.isValidInstallId("short"))
        assertFalse(DeviceIdentityLogic.isValidInstallId("has spaces!!"))
        assertFalse(DeviceIdentityLogic.isValidInstallId(null))
    }

    // 13/14 — register only when no token yet; reuse the stored token afterwards.
    @Test
    fun needsRegistration_onlyWhenNoToken() {
        assertTrue(DeviceIdentityLogic.needsRegistration(null))
        assertTrue(DeviceIdentityLogic.needsRegistration(""))
        assertTrue(DeviceIdentityLogic.needsRegistration("   "))
        assertFalse(DeviceIdentityLogic.needsRegistration("opaque-token"))
    }

    // 14 — after registration the device uses Authorization: Bearer <token>.
    @Test
    fun bearerHeader_builtOnlyWhenTokenPresent() {
        assertEquals("Bearer abc123", DeviceIdentityLogic.bearerHeader("abc123"))
        assertNull(DeviceIdentityLogic.bearerHeader(null))
        assertNull(DeviceIdentityLogic.bearerHeader(""))
    }
}
