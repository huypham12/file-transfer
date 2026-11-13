package com.filetransfer.app.presentation.viewmodel

import com.filetransfer.app.data.local.db.dao.InMemoryTransferSessionDao
import com.filetransfer.app.data.repository.TransferRepositoryImpl
import com.filetransfer.app.domain.model.ApprovalMode
import com.filetransfer.app.domain.model.SessionStatus
import com.filetransfer.app.domain.model.TransferChannel
import com.filetransfer.app.domain.model.TransferDirection
import com.filetransfer.app.domain.model.TransferSession
import com.filetransfer.app.domain.model.TransferType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var transferRepository: TransferRepositoryImpl
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setUp() {
        val sessionDao = InMemoryTransferSessionDao()
        transferRepository = TransferRepositoryImpl(sessionDao)

        viewModel = HistoryViewModel(
            transferRepository = transferRepository,
            openFileSecurelyUseCase = null,
            customScope = CoroutineScope(Dispatchers.Unconfined)
        )
    }

    private suspend fun insertSampleSessions() {
        // 1. Received session (Completed)
        transferRepository.createSession(
            TransferSession(
                sessionId = "recv_001",
                sessionToken = "token_001",
                direction = TransferDirection.RECEIVER,
                channel = TransferChannel.DIRECT_LAN,
                peerDeviceModel = "Xiaomi 14",
                peerFingerprint = "fp_xiaomi14",
                approvalMode = ApprovalMode.AUTO_ACCEPT,
                transferType = TransferType.SINGLE_FILE,
                docId = "bao_cao_quy_4.pdf",
                totalBytes = 2097152L,
                status = SessionStatus.COMPLETED
            )
        )

        // 2. Sent session (Completed)
        transferRepository.createSession(
            TransferSession(
                sessionId = "sent_001",
                sessionToken = "token_002",
                direction = TransferDirection.SENDER,
                channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
                peerDeviceModel = "Pixel 8 Pro",
                peerFingerprint = "fp_pixel8",
                approvalMode = ApprovalMode.MANUAL_APPROVAL,
                transferType = TransferType.FOLDER_ZIP,
                docId = "source_code.zip",
                totalBytes = 10485760L,
                status = SessionStatus.COMPLETED
            )
        )

        // 3. Sent session (Failed)
        transferRepository.createSession(
            TransferSession(
                sessionId = "sent_002",
                sessionToken = "token_003",
                direction = TransferDirection.SENDER,
                channel = TransferChannel.DIRECT_LAN,
                peerDeviceModel = "Galaxy S24",
                peerFingerprint = "fp_s24",
                approvalMode = ApprovalMode.MANUAL_APPROVAL,
                transferType = TransferType.SINGLE_FILE,
                docId = "video_4k.mp4",
                totalBytes = 52428800L,
                status = SessionStatus.FAILED
            )
        )
    }

    @Test
    fun testLoadHistory_splitsReceivedAndSentSessionsCorrectly() = runBlocking {
        insertSampleSessions()
        viewModel.loadHistory()

        val state = viewModel.uiState.value
        assertEquals(1, state.receivedSessions.size)
        assertEquals("recv_001", state.receivedSessions[0].sessionId)
        assertEquals(2097152L, state.receivedSessions[0].totalBytes)

        assertEquals(2, state.sentSessions.size)
        val sentIds = state.sentSessions.map { it.sessionId }
        assertTrue(sentIds.contains("sent_001"))
        assertTrue(sentIds.contains("sent_002"))
    }

    @Test
    fun testSelectTab_updatesSelectedTab() {
        assertEquals(HistoryTab.RECEIVED, viewModel.uiState.value.selectedTab)
        viewModel.selectTab(HistoryTab.SENT)
        assertEquals(HistoryTab.SENT, viewModel.uiState.value.selectedTab)
        viewModel.selectTab(HistoryTab.RECEIVED)
        assertEquals(HistoryTab.RECEIVED, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun testResolveSessionFile_findsTempFilePathIfExists() {
        val testFile = tempFolder.newFile("sample_file.txt").apply {
            writeText("Test filetransfer content")
        }

        val session = TransferSession(
            sessionId = "test_sess",
            direction = TransferDirection.RECEIVER,
            totalBytes = testFile.length(),
            tempFilePath = testFile.absolutePath,
            docId = testFile.name
        )

        val resolved = viewModel.resolveSessionFile(
            context = null,
            session = session
        )

        assertNotNull(resolved)
        assertEquals(testFile.absolutePath, resolved?.absolutePath)
        assertTrue(resolved!!.exists())
    }

    @Test
    fun testClearFeedback_resetsMessage() {
        viewModel.clearFeedback()
        assertEquals(null, viewModel.uiState.value.errorMessage)
        assertEquals(null, viewModel.uiState.value.actionFeedbackMessage)
    }
}

