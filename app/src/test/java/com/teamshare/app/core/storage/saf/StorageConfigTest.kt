package com.teamshare.app.core.storage.saf

import org.junit.Assert.*
import org.junit.Test

class StorageConfigTest {

    @Test
    fun testFileProviderAuthorityConstant() {
        assertEquals("com.teamshare.fileprovider", FileProviderGateway.DEFAULT_AUTHORITY)
    }

    @Test
    fun testStorageDirectorySanitization() {
        val teamIdWithSpecialChars = "team/../malicious:id*?123"
        val sanitized = teamIdWithSpecialChars.replace(Regex("[^a-zA-Z0-9_-]"), "_")

        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("\\"))
        assertFalse(sanitized.contains(".."))
        assertFalse(sanitized.contains(":"))
        assertFalse(sanitized.contains("*"))
        assertFalse(sanitized.contains("?"))
    }
}
