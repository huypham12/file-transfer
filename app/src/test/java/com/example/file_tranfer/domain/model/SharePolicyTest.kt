package com.example.file_tranfer.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePolicyTest {

    @Test
    fun testPolicy_NotExpired_WhenCurrentTimeBeforeExpiry() {
        val policy = SharePolicy(
            policyId = "p-1",
            expiresAt = 2000L
        )
        assertFalse(policy.isExpired(currentTimeMillis = 1000L))
    }

    @Test
    fun testPolicy_Expired_WhenCurrentTimeAfterExpiry() {
        val policy = SharePolicy(
            policyId = "p-1",
            expiresAt = 2000L
        )
        assertTrue(policy.isExpired(currentTimeMillis = 2000L))
        assertTrue(policy.isExpired(currentTimeMillis = 2500L))
    }

    @Test
    fun testPolicy_NoExpiry_WhenExpiresAtIsZero() {
        val policy = SharePolicy(
            policyId = "p-1",
            expiresAt = 0L
        )
        assertFalse(policy.isExpired(currentTimeMillis = 9999999L))
    }

    @Test
    fun testPolicy_DownloadLimitReached() {
        val policy = SharePolicy(
            policyId = "p-1",
            maxDownloads = 2,
            downloadCount = 2
        )
        assertTrue(policy.isDownloadLimitReached())
    }

    @Test
    fun testPolicy_DownloadLimitNotReached() {
        val policy = SharePolicy(
            policyId = "p-1",
            maxDownloads = 2,
            downloadCount = 1
        )
        assertFalse(policy.isDownloadLimitReached())
    }

    @Test
    fun testPolicy_PasscodeDetection() {
        val withPasscode = SharePolicy(
            policyId = "p-1",
            passcodeHash = "some_hash_string"
        )
        assertTrue(withPasscode.hasPasscode())

        val withoutPasscode = SharePolicy(
            policyId = "p-2",
            passcodeHash = null
        )
        assertFalse(withoutPasscode.hasPasscode())
    }
}

