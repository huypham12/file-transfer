package com.filetransfer.app.domain.statemachine

import com.filetransfer.app.domain.model.DeviceProfile
import org.junit.Assert.*
import org.junit.Test

class SessionStateMachineTest {

    private val samplePeer = DeviceProfile(
        manufacturer = "Xiaomi",
        model = "Redmi Turbo 4 Pro",
        fingerprint = "mock_fingerprint_001"
    )

    @Test
    fun testAutoAcceptHappyPathFlow() {
        val sm = SessionStateMachine(SessionState.PENDING)
        assertEquals(SessionState.PENDING, sm.getCurrentState())
        assertFalse(sm.isTerminal)

        val history = mutableListOf<SessionState>()
        sm.addListener { _, newState, _ -> history.add(newState) }

        // PENDING -> CONNECTING
        val s1 = sm.transition(SessionEvent.PeerConnecting(samplePeer))
        assertEquals(SessionState.CONNECTING, s1)

        // CONNECTING -> TRANSFERRING (Auto Accept)
        val s2 = sm.transition(SessionEvent.ApproveConnection)
        assertEquals(SessionState.TRANSFERRING, s2)

        // TRANSFERRING -> TRANSFERRING (ACK chunks)
        val s3 = sm.transition(SessionEvent.ChunkAckReceived(1024L))
        assertEquals(SessionState.TRANSFERRING, s3)

        // TRANSFERRING -> VERIFYING
        val s4 = sm.transition(SessionEvent.AllBytesReceived)
        assertEquals(SessionState.VERIFYING, s4)

        // VERIFYING -> COMPLETED
        val s5 = sm.transition(SessionEvent.VerifySuccess)
        assertEquals(SessionState.COMPLETED, s5)
        assertTrue(sm.isTerminal)

        assertEquals(
            listOf(
                SessionState.CONNECTING,
                SessionState.TRANSFERRING,
                SessionState.TRANSFERRING,
                SessionState.VERIFYING,
                SessionState.COMPLETED
            ),
            history
        )
    }

    @Test
    fun testManualApprovalFlow() {
        val sm = SessionStateMachine(SessionState.PENDING)

        // PENDING -> CONNECTING
        sm.transition(SessionEvent.PeerConnecting(samplePeer))
        assertEquals(SessionState.CONNECTING, sm.getCurrentState())

        // CONNECTING -> APPROVING
        sm.transition(SessionEvent.RequireApproval(samplePeer))
        assertEquals(SessionState.APPROVING, sm.getCurrentState())

        // APPROVING -> TRANSFERRING
        sm.transition(SessionEvent.ApproveConnection)
        assertEquals(SessionState.TRANSFERRING, sm.getCurrentState())
    }

    @Test
    fun testManualRejectionFlow() {
        val sm = SessionStateMachine(SessionState.PENDING)

        sm.transition(SessionEvent.PeerConnecting(samplePeer))
        assertEquals(SessionState.CONNECTING, sm.getCurrentState())

        sm.transition(SessionEvent.RequireApproval(samplePeer))
        assertEquals(SessionState.APPROVING, sm.getCurrentState())

        sm.transition(SessionEvent.RejectConnection("Người dùng từ chối nhận file"))
        assertEquals(SessionState.REJECTED, sm.getCurrentState())
        assertTrue(sm.isTerminal)
    }

    @Test
    fun testDirectRejectionFromConnecting() {
        val sm = SessionStateMachine(SessionState.CONNECTING)

        sm.transition(SessionEvent.RejectConnection("Token không hợp lệ"))
        assertEquals(SessionState.REJECTED, sm.getCurrentState())
        assertTrue(sm.isTerminal)
    }

    @Test
    fun testPauseAndResumeFlow() {
        val sm = SessionStateMachine(SessionState.TRANSFERRING)

        sm.transition(SessionEvent.Pause)
        assertEquals(SessionState.PAUSED, sm.getCurrentState())
        assertFalse(sm.isTerminal)

        sm.transition(SessionEvent.Resume)
        assertEquals(SessionState.TRANSFERRING, sm.getCurrentState())
    }

    @Test
    fun testCancelFromAnyActiveState() {
        val statesToTest = listOf(
            SessionState.PENDING,
            SessionState.CONNECTING,
            SessionState.APPROVING,
            SessionState.TRANSFERRING,
            SessionState.PAUSED,
            SessionState.VERIFYING
        )

        for (st in statesToTest) {
            val sm = SessionStateMachine(st)
            sm.transition(SessionEvent.Cancel)
            assertEquals("Failed to cancel from $st", SessionState.CANCELLED, sm.getCurrentState())
            assertTrue(sm.isTerminal)
        }
    }

    @Test
    fun testChecksumFailureFlow() {
        val sm = SessionStateMachine(SessionState.VERIFYING)

        sm.transition(SessionEvent.VerifyFailed)
        assertEquals(SessionState.CHECKSUM_FAILED, sm.getCurrentState())
        assertTrue(sm.isTerminal)
    }

    @Test
    fun testFailAndRetryFlow() {
        val sm = SessionStateMachine(SessionState.TRANSFERRING)

        sm.transition(SessionEvent.Fail("Mất kết nối mạng"))
        assertEquals(SessionState.FAILED, sm.getCurrentState())
        assertFalse(sm.isTerminal)

        // Retry by transitioning to CONNECTING
        sm.transition(SessionEvent.StartHandshake)
        assertEquals(SessionState.CONNECTING, sm.getCurrentState())
    }

    @Test(expected = IllegalStateTransitionException::class)
    fun testIllegalTransitionFromPendingToVerifying() {
        val sm = SessionStateMachine(SessionState.PENDING)
        sm.transition(SessionEvent.AllBytesReceived)
    }

    @Test(expected = IllegalStateTransitionException::class)
    fun testTransitionFromTerminalStateCompletedThrowsException() {
        val sm = SessionStateMachine(SessionState.COMPLETED)
        sm.transition(SessionEvent.StartTransfer)
    }

    @Test(expected = IllegalStateTransitionException::class)
    fun testTransitionFromTerminalStateCancelledThrowsException() {
        val sm = SessionStateMachine(SessionState.CANCELLED)
        sm.transition(SessionEvent.Resume)
    }
}

