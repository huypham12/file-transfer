package com.example.file_tranfer.domain.statemachine

/**
 * Ngoại lệ được ném ra khi cố tình chuyển trạng thái bất hợp lệ trong State Machine.
 */
class IllegalStateTransitionException(
    val fromState: SessionState,
    val event: SessionEvent
) : IllegalStateException("Chuyển trạng thái bất hợp lệ từ [$fromState] với sự kiện [${event::class.simpleName}]")

typealias StateChangeListener = (oldState: SessionState, newState: SessionState, event: SessionEvent) -> Unit

/**
 * Cỗ máy quản lý 12 trạng thái vòng đời của phiên truyền tệp P2P SecureShare.
 * Đảm bảo tính an toàn đồng quy (Thread-Safe) khi nhiều coroutine/thread cùng tác động.
 */
class SessionStateMachine(initialState: SessionState = SessionState.IDLE) {

    private val lock = Any()
    private var currentState: SessionState = initialState
    private val listeners = mutableListOf<StateChangeListener>()

    fun getCurrentState(): SessionState = synchronized(lock) { currentState }

    fun addListener(listener: StateChangeListener) = synchronized(lock) {
        listeners.add(listener)
    }

    fun removeListener(listener: StateChangeListener) = synchronized(lock) {
        listeners.remove(listener)
    }

    /**
     * Thực hiện chuyển dịch trạng thái dựa trên sự kiện kích hoạt.
     * Ném ra [IllegalStateTransitionException] nếu bước chuyển không hợp lệ.
     */
    fun transition(event: SessionEvent): SessionState {
        val (oldState, newState) = synchronized(lock) {
            val nextState = calculateNextState(currentState, event)
                ?: throw IllegalStateTransitionException(currentState, event)

            val previous = currentState
            currentState = nextState
            Pair(previous, nextState)
        }

        // Bắn sự kiện ra ngoài ngoài khối synchronized để tránh Deadlock
        listeners.forEach { listener ->
            listener(oldState, newState, event)
        }

        return newState
    }

    private fun calculateNextState(current: SessionState, event: SessionEvent): SessionState? {
        // Sự kiện thu hồi và sự kiện lỗi luôn có quyền chuyển sang REVOKED / FAILED từ mọi trạng thái chưa kết thúc
        if (event is SessionEvent.Revoke && !current.isTerminal) return SessionState.REVOKED
        if (event is SessionEvent.Fail && !current.isTerminal) return SessionState.FAILED
        if (event is SessionEvent.Reset && current.isTerminal) return SessionState.IDLE

        return when (current) {
            SessionState.IDLE -> when (event) {
                is SessionEvent.GenerateQr -> SessionState.QR_GENERATED
                is SessionEvent.ScanQr -> SessionState.QR_SCANNED
                is SessionEvent.StartPairing -> SessionState.PAIRING_NEGOTIATING
                else -> null
            }

            SessionState.QR_GENERATED -> when (event) {
                is SessionEvent.StartPairing -> SessionState.PAIRING_NEGOTIATING
                is SessionEvent.Reset -> SessionState.IDLE
                else -> null
            }

            SessionState.QR_SCANNED -> when (event) {
                is SessionEvent.StartPairing -> SessionState.PAIRING_NEGOTIATING
                is SessionEvent.Reset -> SessionState.IDLE
                else -> null
            }

            SessionState.PAIRING_NEGOTIATING -> when (event) {
                is SessionEvent.SasCalculated -> SessionState.SAS_VERIFYING
                else -> null
            }

            SessionState.SAS_VERIFYING -> when (event) {
                is SessionEvent.SasVerified -> if (event.isConfirmed) SessionState.POLICY_NEGOTIATING else SessionState.FAILED
                else -> null
            }

            SessionState.POLICY_NEGOTIATING -> when (event) {
                is SessionEvent.PolicyAccepted -> SessionState.TRANSFERRING
                is SessionEvent.StartTransfer -> SessionState.TRANSFERRING
                is SessionEvent.PolicyRejected -> SessionState.FAILED
                else -> null
            }

            SessionState.TRANSFERRING -> when (event) {
                is SessionEvent.PauseTransfer -> SessionState.PAUSED
                is SessionEvent.StartHashVerification -> SessionState.VERIFYING_HASH
                else -> null
            }

            SessionState.PAUSED -> when (event) {
                is SessionEvent.ResumeTransfer -> SessionState.TRANSFERRING
                else -> null
            }

            SessionState.VERIFYING_HASH -> when (event) {
                is SessionEvent.HashVerificationResult -> if (event.isSuccess) SessionState.COMPLETED else SessionState.FAILED
                else -> null
            }

            SessionState.COMPLETED,
            SessionState.REVOKED,
            SessionState.FAILED -> null
        }
    }
}

