package com.teamshare.app.domain.statemachine

/**
 * Ngoại lệ được ném ra khi cố tình chuyển trạng thái bất hợp lệ trong State Machine.
 */
class IllegalStateTransitionException(
    val fromState: SessionState,
    val event: SessionEvent
) : IllegalStateException("Chuyển trạng thái bất hợp lệ từ [$fromState] với sự kiện [${event::class.simpleName}]")

typealias StateChangeListener = (oldState: SessionState, newState: SessionState, event: SessionEvent) -> Unit

/**
 * Cỗ máy quản lý trạng thái vòng đời của phiên truyền tệp P2P TeamShare.
 * Tuân thủ SAD mục 9.1 và SDD mục 7.6.
 * Đảm bảo tính an toàn đồng quy (Thread-Safe) khi nhiều coroutine/thread cùng tác động.
 */
class SessionStateMachine(initialState: SessionState = SessionState.IDLE) {

    private val lock = Any()
    private var currentState: SessionState = initialState
    private val listeners = mutableListOf<StateChangeListener>()

    fun getCurrentState(): SessionState = synchronized(lock) { currentState }

    val isTerminal: Boolean
        get() = synchronized(lock) {
            currentState == SessionState.COMPLETED ||
            currentState == SessionState.CANCELLED ||
            currentState == SessionState.FAILED ||
            currentState == SessionState.CHECKSUM_FAILED
        }

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
        val (oldState, newState, snapshotListeners) = synchronized(lock) {
            val nextState = calculateNextState(currentState, event)
                ?: throw IllegalStateTransitionException(currentState, event)

            val previous = currentState
            currentState = nextState
            Triple(previous, nextState, listeners.toList())
        }

        // Bắn sự kiện ra ngoài khối synchronized để tránh Deadlock và an toàn đồng quy (Snapshot)
        snapshotListeners.forEach { listener ->
            listener(oldState, newState, event)
        }

        return newState
    }

    private fun calculateNextState(current: SessionState, event: SessionEvent): SessionState? {
        val isTerminalState = current == SessionState.COMPLETED ||
                              current == SessionState.CANCELLED ||
                              current == SessionState.CHECKSUM_FAILED

        if (isTerminalState) return null

        // Nếu đang ở FAILED, cho phép khởi động lại từ đầu (Retry qua StartHandshake) hoặc hủy hẳn (Cancel)
        if (current == SessionState.FAILED) {
            return when (event) {
                is SessionEvent.StartHandshake -> SessionState.HANDSHAKING
                is SessionEvent.Cancel -> SessionState.CANCELLED
                else -> null
            }
        }

        // Sự kiện lỗi hoặc thu hồi quyền luôn có thể chuyển sang FAILED hoặc CANCELLED từ mọi trạng thái đang chạy
        if (event is SessionEvent.Fail) return SessionState.FAILED
        if (event is SessionEvent.RevokeMember) return SessionState.CANCELLED
        if (event is SessionEvent.Cancel) return SessionState.CANCELLED

        return when (current) {
            SessionState.IDLE -> when (event) {
                is SessionEvent.StartHandshake -> SessionState.HANDSHAKING
                else -> null
            }

            SessionState.HANDSHAKING -> when (event) {
                is SessionEvent.HandshakeSuccess -> SessionState.TRANSFERRING
                is SessionEvent.AuthenticationFailed -> SessionState.FAILED
                else -> null
            }

            SessionState.TRANSFERRING -> when (event) {
                is SessionEvent.ChunkAckReceived -> SessionState.TRANSFERRING // Vẫn giữ trạng thái đang truyền
                is SessionEvent.Pause -> SessionState.PAUSED
                is SessionEvent.AllBytesReceived -> SessionState.VERIFYING
                else -> null
            }

            SessionState.PAUSED -> when (event) {
                is SessionEvent.Resume -> SessionState.TRANSFERRING
                else -> null
            }

            SessionState.VERIFYING -> when (event) {
                is SessionEvent.VerifySuccess -> SessionState.COMPLETED
                is SessionEvent.VerifyFailed -> SessionState.CHECKSUM_FAILED
                else -> null
            }

            else -> null
        }
    }
}

