package com.filetransfer.app.domain.model

import org.junit.Assert.*
import org.junit.Test

class TransferSessionTest {

    @Test
    fun testProgressCalculation() {
        val session = TransferSession(
            sessionId = "session-001",
            docId = "doc-456",
            peerMemberId = "mem-2",
            direction = TransferDirection.OUTGOING,
            channel = TransferChannel.DIRECT_LAN,
            totalBytes = 100_000L,
            bytesTransferred = 50_000L,
            confirmedOffset = 50_000L,
            status = SessionStatus.TRANSFERRING
        )

        assertEquals(0.5f, session.progressPercent, 0.001f)
        assertFalse(session.isCompleted)
        assertFalse(session.canResume)
    }

    @Test
    fun testCanResumeInPausedOrFailed() {
        val runningSession = TransferSession(
            sessionId = "session-001",
            docId = "doc-456",
            peerMemberId = "mem-2",
            direction = TransferDirection.INCOMING,
            totalBytes = 200_000L,
            confirmedOffset = 100_000L,
            status = SessionStatus.TRANSFERRING
        )
        assertFalse(runningSession.canResume)

        val pausedSession = runningSession.copy(status = SessionStatus.PAUSED)
        assertTrue(pausedSession.canResume)

        val failedSession = runningSession.copy(status = SessionStatus.FAILED)
        assertTrue(failedSession.canResume)

        val completedSession = runningSession.copy(status = SessionStatus.COMPLETED)
        assertFalse(completedSession.canResume)
        assertTrue(completedSession.isCompleted)
        assertEquals(1.0f, completedSession.progressPercent, 0.001f)
    }

    @Test
    fun testCanRetryProperty() {
        val session = TransferSession(
            sessionId = "session-002",
            docId = "doc-789",
            peerMemberId = "mem-3",
            direction = TransferDirection.OUTGOING,
            totalBytes = 50_000L,
            status = SessionStatus.TRANSFERRING
        )

        assertFalse(session.canRetry)
        assertFalse(session.copy(status = SessionStatus.PAUSED).canRetry)
        assertFalse(session.copy(status = SessionStatus.COMPLETED).canRetry)
        assertFalse(session.copy(status = SessionStatus.CANCELLED).canRetry)
        assertTrue(session.copy(status = SessionStatus.FAILED).canRetry)
    }
}

