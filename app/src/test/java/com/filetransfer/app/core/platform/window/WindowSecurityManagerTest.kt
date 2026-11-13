package com.filetransfer.app.core.platform.window

import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test

class WindowSecurityManagerTest {

    @Test
    fun testFlagSecureConstantMatchesAndroidWindowManager() {
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, WindowSecurityManager.FLAG_SECURE)
        assertTrue(WindowSecurityManager.FLAG_SECURE > 0)
    }

    @Test
    fun testIsFlagSecureBitSet() {
        val flagSecure = WindowSecurityManager.FLAG_SECURE

        // Có cờ FLAG_SECURE
        val flagsWithSecure = flagSecure or 0x00000001
        assertTrue(WindowSecurityManager.isFlagSecureBitSet(flagsWithSecure))

        // Không có cờ FLAG_SECURE
        val flagsWithoutSecure = 0x00000001 or 0x00000002
        assertFalse(WindowSecurityManager.isFlagSecureBitSet(flagsWithoutSecure))
    }
}

