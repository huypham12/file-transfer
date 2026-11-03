package com.example.file_tranfer.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSessionTest {

    private val samplePeer = DeviceIdentity(
        deviceId = "dev-001",
        deviceName = "Pixel 8 Pro",
        fingerprint = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
        publicKeyBytes = byteArrayOf(1, 2, 3, 4)
    )

    @Test
    fun testTransferProgress_Calculation() {
        val session = TransferSession(
            sessionId = "s-01",
            peerDevice = samplePeer,
            direction = TransferDirection.SEND,
            totalBytes = 1000L,
            bytesTransferred = 500L
        )

        assertEquals(0.5f, session.overallProgress, 0.001f)
    }

    @Test
    fun testTransferProgress_ZeroTotalBytes() {
        val session = TransferSession(
            sessionId = "s-01",
            peerDevice = samplePeer,
            direction = TransferDirection.RECEIVE,
            totalBytes = 0L,
            bytesTransferred = 0L
        )

        assertEquals(0.0f, session.overallProgress, 0.001f)
    }

    @Test
    fun testCanResume_OnlyWhenPausedOrFailed() {
        val activeSession = TransferSession(
            sessionId = "s-01",
            peerDevice = samplePeer,
            direction = TransferDirection.SEND,
            status = SessionStatus.ACTIVE
        )
        assertFalse(activeSession.canResume)

        val pausedSession = activeSession.copy(status = SessionStatus.PAUSED)
        assertTrue(pausedSession.canResume)

        val failedSession = activeSession.copy(status = SessionStatus.FAILED)
        assertTrue(failedSession.canResume)

        val completedSession = activeSession.copy(status = SessionStatus.COMPLETED)
        assertFalse(completedSession.canResume)
    }

    @Test
    fun testTransferFile_ProgressAndCompletion() {
        val file = TransferFile(
            fileId = "f-1",
            fileName = "document.pdf",
            fileSize = 1000L,
            expectedSha256 = "hash123",
            bytesTransferred = 1000L,
            status = FileTransferStatus.COMPLETED
        )

        assertTrue(file.isCompleted)
        assertEquals(1.0f, file.progress, 0.001f)
    }

    @Test
    fun testTransferFile_ZeroByteFile_Completed_ReturnsProgressOne() {
        val emptyFile = TransferFile(
            fileId = "f-empty",
            fileName = "empty.txt",
            fileSize = 0L,
            expectedSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            bytesTransferred = 0L,
            status = FileTransferStatus.COMPLETED
        )

        assertTrue(emptyFile.isCompleted)
        assertEquals(1.0f, emptyFile.progress, 0.001f)
    }

    @Test
    fun testTransferSession_ZeroTotalBytes_Completed_ReturnsProgressOne() {
        val completedZeroSession = TransferSession(
            sessionId = "s-zero",
            peerDevice = samplePeer,
            direction = TransferDirection.RECEIVE,
            totalBytes = 0L,
            bytesTransferred = 0L,
            status = SessionStatus.COMPLETED
        )

        assertTrue(completedZeroSession.isCompleted)
        assertEquals(1.0f, completedZeroSession.overallProgress, 0.001f)
    }
}

