package com.filetransfer.app.core.platform.service

import com.filetransfer.app.core.transfer.lan.TransferEngineState
import com.filetransfer.app.core.transfer.lan.TransferProgressCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TransferForegroundServiceTest {

    private val controller = TransferServiceController

    @Before
    fun setUp() {
        controller.reset()
    }

    @After
    fun tearDown() {
        controller.reset()
    }

    @Test
    fun testStartSessionUpdatesState() {
        controller.startSession(
            sessionId = "sess-123",
            documentName = "bao_cao_tot_nghiep.pdf",
            totalBytes = 10_000_000L,
            isLan = true
        )

        val state = controller.serviceState.value
        assertTrue(state.isRunning)
        assertEquals("sess-123", state.sessionId)
        assertEquals("bao_cao_tot_nghiep.pdf", state.documentName)
        assertEquals(10_000_000L, state.totalBytes)
        assertEquals(0L, state.bytesTransferred)
        assertTrue(state.isLan)
        assertEquals(TransferEngineState.CONNECTING, state.engineState)
    }

    @Test
    fun testNotifyProgressDispatchesToCallbacks() {
        val progressBytes = AtomicLong(0L)
        val testCallback = object : TransferProgressCallback {
            override fun onProgress(bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long) {
                progressBytes.set(bytesTransferred)
            }
        }

        controller.registerCallback(testCallback)
        controller.startSession("s1", "doc.pdf", 1000L, true)

        controller.notifyProgress(500L, 1000L, 250L)

        assertEquals(500L, progressBytes.get())
        assertEquals(500L, controller.serviceState.value.bytesTransferred)
        assertEquals(TransferEngineState.TRANSFERRING, controller.serviceState.value.engineState)

        controller.unregisterCallback(testCallback)
    }

    @Test
    fun testNotifyStateChangedUpdatesLifecycle() {
        controller.startSession("s1", "doc.pdf", 1000L, true)

        controller.notifyStateChanged(TransferEngineState.PAUSED)
        assertTrue(controller.serviceState.value.isRunning)
        assertEquals(TransferEngineState.PAUSED, controller.serviceState.value.engineState)

        controller.notifyStateChanged(TransferEngineState.COMPLETED)
        assertFalse(controller.serviceState.value.isRunning)
        assertEquals(TransferEngineState.COMPLETED, controller.serviceState.value.engineState)
    }

    @Test
    fun testNotifyCompletedAndError() {
        val completedCalled = AtomicBoolean(false)
        val errorCalled = AtomicBoolean(false)

        val testCallback = object : TransferProgressCallback {
            override fun onCompleted(docId: String, finalSha256: String) {
                completedCalled.set(true)
            }

            override fun onError(errorCode: String, message: String) {
                errorCalled.set(true)
            }
        }

        controller.registerCallback(testCallback)
        controller.startSession("s1", "doc.pdf", 1000L, true)

        controller.notifyCompleted("doc-1", "abc123sha256")
        assertTrue(completedCalled.get())
        assertFalse(controller.serviceState.value.isRunning)
        assertEquals("abc123sha256", controller.serviceState.value.finalSha256)

        controller.notifyError("ERR_TIMEOUT", "Mất kết nối mạng")
        assertTrue(errorCalled.get())
        assertEquals("ERR_TIMEOUT", controller.serviceState.value.errorCode)
    }

    @Test
    fun testCommandListenerPauseResumeCancel() {
        val pauseRequested = AtomicBoolean(false)
        val resumeRequested = AtomicBoolean(false)
        val cancelRequested = AtomicBoolean(false)

        controller.commandListener = object : TransferServiceController.CommandListener {
            override fun onPauseRequested() {
                pauseRequested.set(true)
            }

            override fun onResumeRequested() {
                resumeRequested.set(true)
            }

            override fun onCancelRequested() {
                cancelRequested.set(true)
            }
        }

        controller.requestPause()
        assertTrue(pauseRequested.get())

        controller.requestResume()
        assertTrue(resumeRequested.get())

        controller.requestCancel()
        assertTrue(cancelRequested.get())
    }

    @Test
    fun testConcurrentProgressUpdatesAreThreadSafe() = runBlocking {
        controller.startSession("sess-concurrent", "large_file.iso", 10_000_000L, true)

        val jobs = List(50) { index ->
            launch(Dispatchers.Default) {
                controller.notifyProgress(
                    bytesTransferred = (index + 1) * 1000L,
                    totalBytes = 10_000_000L,
                    speedBytesPerSec = 500_000L
                )
            }
        }
        jobs.joinAll()

        // StateFlow cập nhật nguyên tử, không bị crash, trạng thái là TRANSFERRING
        assertEquals(TransferEngineState.TRANSFERRING, controller.serviceState.value.engineState)
        assertTrue(controller.serviceState.value.bytesTransferred > 0L)
    }
}

