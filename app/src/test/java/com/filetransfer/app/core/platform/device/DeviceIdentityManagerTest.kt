package com.filetransfer.app.core.platform.device

import com.filetransfer.app.core.security.keystore.SoftwareKeyManager
import com.filetransfer.app.domain.model.DeviceProfile
import org.junit.Assert.*
import org.junit.Test

class DeviceIdentityManagerTest {

    @Test
    fun testDefaultDeviceProfileExtraction() {
        val manager = DeviceIdentityManager()
        val profile = manager.getDeviceProfile()

        assertNotNull(profile)
        assertTrue(profile.manufacturer.isNotBlank())
        assertTrue(profile.model.isNotBlank())
        assertTrue(profile.fingerprint.isNotBlank())
        assertEquals(profile.fingerprint, profile.deviceId)
    }

    @Test
    fun testKeyManagerFingerprintExtraction() {
        val softwareKeyManager = SoftwareKeyManager()
        val manager = DeviceIdentityManager(softwareKeyManager)

        val profile = manager.getDeviceProfile()
        assertNotNull(profile)
        assertTrue(profile.fingerprint.isNotBlank())
        assertEquals(64, profile.fingerprint.length) // SHA-256 hex string has 64 chars
    }

    @Test
    fun testSetOverrideProfileForTesting() {
        val manager = DeviceIdentityManager()
        val customProfile = DeviceProfile(
            manufacturer = "Xiaomi",
            model = "Redmi Turbo 4 Pro",
            fingerprint = "0123456789abcdef0123456789abcdef"
        )

        manager.setOverrideProfile(customProfile)
        val retrieved = manager.getDeviceProfile()

        assertEquals("Xiaomi", retrieved.manufacturer)
        assertEquals("Redmi Turbo 4 Pro", retrieved.model)
        assertEquals("0123456789abcdef0123456789abcdef", retrieved.fingerprint)

        // Clear override
        manager.setOverrideProfile(null)
        val fallback = manager.getDeviceProfile()
        assertNotNull(fallback)
    }
}
