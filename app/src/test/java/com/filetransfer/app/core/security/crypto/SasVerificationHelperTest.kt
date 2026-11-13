package com.filetransfer.app.core.security.crypto

import org.junit.Assert.*
import org.junit.Test

class SasVerificationHelperTest {

    @Test
    fun testSasCodeFormatIsExactlySixDigits() {
        val pubKeyA = ByteArray(32) { 0x01 }
        val pubKeyB = ByteArray(32) { 0x02 }
        val nonce = ByteArray(16) { 0x03 }

        val sasCode = SasVerificationHelper.generateSasCode(pubKeyA, pubKeyB, nonce)

        assertNotNull(sasCode)
        assertEquals(6, sasCode.length)
        assertTrue("SAS code phải là 6 chữ số: $sasCode", sasCode.matches(Regex("^\\d{6}$")))
    }

    @Test
    fun testSasCodeSymmetryBetweenTwoDevices() {
        val pubKeyA = "Device-A-Ephemeral-PublicKey-32Bytes".toByteArray()
        val pubKeyB = "Device-B-Ephemeral-PublicKey-32Bytes".toByteArray()
        val nonce = "Session-Nonce-16".toByteArray()

        val sasDeviceA = SasVerificationHelper.generateSasCode(pubKeyA, pubKeyB, nonce)
        val sasDeviceB = SasVerificationHelper.generateSasCode(pubKeyB, pubKeyA, nonce)

        assertEquals("Mã SAS của Thiết bị A và Thiết bị B phải hoàn toàn đồng nhất", sasDeviceA, sasDeviceB)
    }

    @Test
    fun testSasCodeChangesWhenNonceChanges() {
        val pubKeyA = "Device-A-Ephemeral-PublicKey-32Bytes".toByteArray()
        val pubKeyB = "Device-B-Ephemeral-PublicKey-32Bytes".toByteArray()
        val nonce1 = "Session-Nonce-11".toByteArray()
        val nonce2 = "Session-Nonce-22".toByteArray()

        val sas1 = SasVerificationHelper.generateSasCode(pubKeyA, pubKeyB, nonce1)
        val sas2 = SasVerificationHelper.generateSasCode(pubKeyA, pubKeyB, nonce2)

        assertNotEquals(sas1, sas2)
    }
}

