package com.teamshare.app.domain.statemachine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SessionStateMachineTest {

    private lateinit var stateMachine: SessionStateMachine

    @Before
    fun setUp() {
        stateMachine = SessionStateMachine(SessionState.IDLE)
    }

    @Test
    fun testHappyPathToCompletion() {
        assertEquals(SessionState.IDLE, stateMachine.getCurrentState())

        // 1. Bắt đầu bắt tay
        stateMachine.transition(SessionEvent.StartHandshake)
        assertEquals(SessionState.HANDSHAKING, stateMachine.getCurrentState())

        // 2. Thẩm định Credential 2 chiều thành công -> Chuyển sang truyền tệp
        stateMachine.transition(SessionEvent.HandshakeSuccess)
        assertEquals(SessionState.TRANSFERRING, stateMachine.getCurrentState())

        // 3. Nhận các ACK chunk
        stateMachine.transition(SessionEvent.ChunkAckReceived(1024L))
        assertEquals(SessionState.TRANSFERRING, stateMachine.getCurrentState())

        // 4. Nhận đủ 100% byte tổng -> Chuyển sang kiểm tra toàn vẹn băm SHA-256
        stateMachine.transition(SessionEvent.AllBytesReceived)
        assertEquals(SessionState.VERIFYING, stateMachine.getCurrentState())

        // 5. Hash trùng khớp 100% -> Hoàn tất thành công
        stateMachine.transition(SessionEvent.VerifySuccess)
        assertEquals(SessionState.COMPLETED, stateMachine.getCurrentState())
        assertTrue(stateMachine.isTerminal)
    }

    @Test
    fun testChecksumMismatchPath() {
        stateMachine.transition(SessionEvent.StartHandshake)
        stateMachine.transition(SessionEvent.HandshakeSuccess)
        stateMachine.transition(SessionEvent.AllBytesReceived)
        assertEquals(SessionState.VERIFYING, stateMachine.getCurrentState())

        // Hash sai lệch -> CHECKSUM_FAILED
        stateMachine.transition(SessionEvent.VerifyFailed)
        assertEquals(SessionState.CHECKSUM_FAILED, stateMachine.getCurrentState())
        assertTrue(stateMachine.isTerminal)
    }

    @Test
    fun testPauseAndResumeFlow() {
        stateMachine.transition(SessionEvent.StartHandshake)
        stateMachine.transition(SessionEvent.HandshakeSuccess)
        assertEquals(SessionState.TRANSFERRING, stateMachine.getCurrentState())

        // Tạm dừng do mất mạng hoặc người dùng bấm Pause
        stateMachine.transition(SessionEvent.Pause)
        assertEquals(SessionState.PAUSED, stateMachine.getCurrentState())

        // Tiếp tục truyền từ Checkpoint
        stateMachine.transition(SessionEvent.Resume)
        assertEquals(SessionState.TRANSFERRING, stateMachine.getCurrentState())
    }

    @Test
    fun testMemberRevocationForcedCancellation() {
        stateMachine.transition(SessionEvent.StartHandshake)
        stateMachine.transition(SessionEvent.HandshakeSuccess)
        assertEquals(SessionState.TRANSFERRING, stateMachine.getCurrentState())

        // Owner loại thành viên cưỡng bức -> CANCELLED
        stateMachine.transition(SessionEvent.RevokeMember)
        assertEquals(SessionState.CANCELLED, stateMachine.getCurrentState())
        assertTrue(stateMachine.isTerminal)
    }

    @Test
    fun testAuthenticationFailure() {
        stateMachine.transition(SessionEvent.StartHandshake)
        assertEquals(SessionState.HANDSHAKING, stateMachine.getCurrentState())

        stateMachine.transition(SessionEvent.AuthenticationFailed)
        assertEquals(SessionState.FAILED, stateMachine.getCurrentState())
        assertTrue(stateMachine.isTerminal)
    }

    @Test(expected = IllegalStateTransitionException::class)
    fun testIllegalTransitionThrowsException() {
        // Đang ở IDLE mà gửi VerifySuccess -> Bất hợp lệ
        stateMachine.transition(SessionEvent.VerifySuccess)
    }

    @Test
    fun testRetryFromFailedState() {
        // 1. Chuyển sang FAILED do lỗi xác thực
        stateMachine.transition(SessionEvent.StartHandshake)
        stateMachine.transition(SessionEvent.AuthenticationFailed)
        assertEquals(SessionState.FAILED, stateMachine.getCurrentState())
        assertTrue(stateMachine.isTerminal)

        // 2. Thử lại (Retry) bằng sự kiện StartHandshake -> Chuyển lại HANDSHAKING
        stateMachine.transition(SessionEvent.StartHandshake)
        assertEquals(SessionState.HANDSHAKING, stateMachine.getCurrentState())
        assertFalse(stateMachine.isTerminal)

        // 3. Tiếp tục hoàn tất phiên
        stateMachine.transition(SessionEvent.HandshakeSuccess)
        stateMachine.transition(SessionEvent.AllBytesReceived)
        stateMachine.transition(SessionEvent.VerifySuccess)
        assertEquals(SessionState.COMPLETED, stateMachine.getCurrentState())
        assertTrue(stateMachine.isTerminal)
    }

    @Test(expected = IllegalStateTransitionException::class)
    fun testInvalidResumeFromFailedStateThrows() {
        stateMachine.transition(SessionEvent.StartHandshake)
        stateMachine.transition(SessionEvent.AuthenticationFailed)
        assertEquals(SessionState.FAILED, stateMachine.getCurrentState())

        // Từ FAILED mà gọi Resume -> Bất hợp lệ, bắt buộc phải StartHandshake lại
        stateMachine.transition(SessionEvent.Resume)
    }

    @Test
    fun testListenerSelfRemoval() {
        var listenerInvoked = false
        lateinit var selfRemovingListener: StateChangeListener

        selfRemovingListener = { _, newState, _ ->
            if (newState == SessionState.COMPLETED) {
                listenerInvoked = true
                stateMachine.removeListener(selfRemovingListener)
            }
        }

        stateMachine.addListener(selfRemovingListener)

        stateMachine.transition(SessionEvent.StartHandshake)
        stateMachine.transition(SessionEvent.HandshakeSuccess)
        stateMachine.transition(SessionEvent.AllBytesReceived)
        stateMachine.transition(SessionEvent.VerifySuccess)

        assertEquals(SessionState.COMPLETED, stateMachine.getCurrentState())
        assertTrue(listenerInvoked)
    }

    @Test
    fun testConcurrentListenerNotificationAndMutation() {
        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val hasConcurrentError = AtomicBoolean(false)
        val notificationsCount = AtomicInteger(0)

        // Đăng ký listener liên tục đếm thông báo
        for (i in 0 until 5) {
            stateMachine.addListener { _, _, _ ->
                notificationsCount.incrementAndGet()
            }
        }

        // Chạy đa luồng vừa add/remove listener vừa transition
        val threads = (0 until threadCount).map { index ->
            Thread {
                try {
                    val dummyListener: StateChangeListener = { _, _, _ -> }
                    stateMachine.addListener(dummyListener)
                    if (index % 2 == 0) {
                        stateMachine.removeListener(dummyListener)
                    }
                } catch (t: Throwable) {
                    hasConcurrentError.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        threads.forEach { it.start() }
        stateMachine.transition(SessionEvent.StartHandshake)
        stateMachine.transition(SessionEvent.HandshakeSuccess)

        val completedInTime = latch.await(3, TimeUnit.SECONDS)
        assertTrue("Các luồng phải hoàn tất trong 3 giây", completedInTime)
        assertFalse("Không được có ConcurrentModificationException khi xử lý đa luồng", hasConcurrentError.get())
        assertTrue("Phải nhận được các thông báo hợp lệ", notificationsCount.get() > 0)
    }
}

