package com.example.file_tranfer.domain.statemachine

import com.example.file_tranfer.domain.model.QrPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {

    @Test
    fun testInitialState_IsIdle() {
        val sm = SessionStateMachine()
        assertEquals(SessionState.IDLE, sm.getCurrentState())
    }

    @Test
    fun testHappyPath_CompleteTransferLifecycle() {
        val sm = SessionStateMachine()

        // 1. IDLE -> Generate QR -> QR_GENERATED
        assertEquals(SessionState.QR_GENERATED, sm.transition(SessionEvent.GenerateQr))

        // 2. QR_GENERATED -> StartPairing -> PAIRING_NEGOTIATING
        assertEquals(SessionState.PAIRING_NEGOTIATING, sm.transition(SessionEvent.StartPairing))

        // 3. PAIRING_NEGOTIATING -> SasCalculated -> SAS_VERIFYING
        assertEquals(SessionState.SAS_VERIFYING, sm.transition(SessionEvent.SasCalculated(sasCode = "582 914")))

        // 4. SAS_VERIFYING -> SasVerified(true) -> POLICY_NEGOTIATING
        assertEquals(SessionState.POLICY_NEGOTIATING, sm.transition(SessionEvent.SasVerified(isConfirmed = true)))

        // 5. POLICY_NEGOTIATING -> PolicyAccepted -> TRANSFERRING
        assertEquals(SessionState.TRANSFERRING, sm.transition(SessionEvent.PolicyAccepted))

        // 6. TRANSFERRING -> PauseTransfer -> PAUSED
        assertEquals(SessionState.PAUSED, sm.transition(SessionEvent.PauseTransfer))

        // 7. PAUSED -> ResumeTransfer -> TRANSFERRING
        assertEquals(SessionState.TRANSFERRING, sm.transition(SessionEvent.ResumeTransfer))

        // 8. TRANSFERRING -> StartHashVerification -> VERIFYING_HASH
        assertEquals(SessionState.VERIFYING_HASH, sm.transition(SessionEvent.StartHashVerification))

        // 9. VERIFYING_HASH -> HashVerificationResult(true) -> COMPLETED
        assertEquals(SessionState.COMPLETED, sm.transition(SessionEvent.HashVerificationResult(isSuccess = true)))
    }

    @Test
    fun testDirectSasVerifiedFromPairing_ThrowsException() {
        val sm = SessionStateMachine(SessionState.PAIRING_NEGOTIATING)

        // Không được phép nhảy cóc qua SAS_VERIFYING
        assertThrows(IllegalStateTransitionException::class.java) {
            sm.transition(SessionEvent.SasVerified(isConfirmed = true))
        }
    }

    @Test
    fun testInvalidTransition_ThrowsIllegalStateTransitionException() {
        val sm = SessionStateMachine()

        // IDLE cannot directly jump to TRANSFERRING
        assertThrows(IllegalStateTransitionException::class.java) {
            sm.transition(SessionEvent.StartTransfer)
        }
    }

    @Test
    fun testRevoke_FromActiveTransfer_TransitionsToRevoked() {
        val sm = SessionStateMachine(SessionState.TRANSFERRING)

        val newState = sm.transition(SessionEvent.Revoke(reason = "Người gửi chủ động thu hồi"))
        assertEquals(SessionState.REVOKED, newState)
    }

    @Test
    fun testSasFailure_TransitionsToFailed() {
        val sm = SessionStateMachine(SessionState.SAS_VERIFYING)

        val newState = sm.transition(SessionEvent.SasVerified(isConfirmed = false))
        assertEquals(SessionState.FAILED, newState)
    }

    @Test
    fun testTerminalState_Reset_TransitionsToIdle() {
        val sm = SessionStateMachine(SessionState.COMPLETED)
        assertEquals(SessionState.IDLE, sm.transition(SessionEvent.Reset))
    }

    @Test
    fun testListener_ReceivesStateChangeNotification() {
        val sm = SessionStateMachine()
        var listenerCalled = false

        sm.addListener { oldState, newState, event ->
            listenerCalled = true
            assertEquals(SessionState.IDLE, oldState)
            assertEquals(SessionState.QR_GENERATED, newState)
            assertTrue(event is SessionEvent.GenerateQr)
        }

        sm.transition(SessionEvent.GenerateQr)
        assertTrue(listenerCalled)
    }
}

