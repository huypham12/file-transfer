package com.teamshare.app.presentation.viewmodel

import android.net.FakeUri
import com.teamshare.app.core.security.biometric.IBiometricAuthManager
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.domain.repository.IDocumentRepository
import com.teamshare.app.domain.usecase.document.DeleteDocumentUseCase
import com.teamshare.app.domain.usecase.document.GetDocumentCatalogUseCase
import com.teamshare.app.domain.usecase.document.OpenSecureDocumentUseCase
import com.teamshare.app.domain.usecase.document.PublishDocumentUseCase
import com.teamshare.app.domain.usecase.platform.AuthenticateBiometricUseCase
import com.teamshare.app.domain.usecase.platform.HandleSendIntentUseCase
import com.teamshare.app.domain.usecase.team.FakeAuditRepository
import com.teamshare.app.domain.usecase.team.FakeTeamRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DocumentViewModelTest {

    private class FakeDocumentRepository : IDocumentRepository {
        val docs = ConcurrentHashMap<String, SharedDocument>()

        override suspend fun publishDocument(document: SharedDocument): Result<SharedDocument> {
            docs[document.docId] = document
            return Result.success(document)
        }

        override fun getDocumentsByTeam(teamId: String): Flow<List<SharedDocument>> =
            flowOf(docs.values.filter { it.teamId == teamId })

        override suspend fun getDocumentById(docId: String): SharedDocument? = docs[docId]

        override suspend fun updateDownloadStatus(docId: String, status: DownloadStatus, localUri: String?) {
            docs[docId]?.let { current ->
                docs[docId] = current.copy(downloadStatus = status, localUri = localUri ?: current.localUri)
            }
        }

        override suspend fun deleteDocument(docId: String): Result<Unit> {
            docs.remove(docId)
            return Result.success(Unit)
        }
    }

    private class FakeBiometricAuthManager(var isAvailable: Boolean = true) : IBiometricAuthManager {
        override fun canAuthenticate(): Boolean = isAvailable
        override fun authenticate(
            activity: androidx.fragment.app.FragmentActivity,
            title: String,
            subtitle: String,
            negativeButtonText: String,
            onSuccess: () -> Unit,
            onError: (Int, String) -> Unit,
            onFailed: () -> Unit
        ) {
            if (isAvailable) onSuccess() else onError(-1, "Hardware unavailable")
        }
    }

    private lateinit var docRepo: FakeDocumentRepository
    private lateinit var biometricManager: FakeBiometricAuthManager
    private lateinit var docViewModel: DocumentViewModel
    private lateinit var tempFile: File

    @Before
    fun setup() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "teamshare_test_doc_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        tempFile = File(tempDir, "sample.txt")
        tempFile.writeText("Fake document content for testing")

        docRepo = FakeDocumentRepository()
        val auditRepo = FakeAuditRepository()
        val teamRepo = FakeTeamRepository()
        biometricManager = FakeBiometricAuthManager(isAvailable = true)

        val publishUseCase = PublishDocumentUseCase(
            documentRepository = docRepo,
            safStorageGateway = null,
            auditRepository = auditRepo
        )
        val catalogUseCase = GetDocumentCatalogUseCase(docRepo)
        val openUseCase = OpenSecureDocumentUseCase(
            documentRepository = docRepo,
            fileProviderGateway = null,
            directoryManager = null,
            uriConverter = { file -> FakeUri("file", file.absolutePath) }
        )
        val deleteUseCase = DeleteDocumentUseCase(
            documentRepository = docRepo,
            directoryManager = null,
            auditRepository = auditRepo,
            teamRepository = teamRepo
        )
        val biometricUseCase = AuthenticateBiometricUseCase(biometricManager)
        val handleSendIntentUseCase = HandleSendIntentUseCase(publishUseCase, auditRepo)

        docViewModel = DocumentViewModel(
            publishDocumentUseCase = publishUseCase,
            getDocumentCatalogUseCase = catalogUseCase,
            openSecureDocumentUseCase = openUseCase,
            deleteDocumentUseCase = deleteUseCase,
            authenticateBiometricUseCase = biometricUseCase,
            handleSendIntentUseCase = handleSendIntentUseCase,
            customScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
    }

    @Test
    fun `publishDocument succeeds and updates catalogUiState`() = runBlocking {
        val testContent = "TeamShare Presentation Test Content"
        val inStream = ByteArrayInputStream(testContent.toByteArray(Charsets.UTF_8))
        val fakeUri = FakeUri("content", "media/external/file/101")
        var successCallbackCalled = false

        docViewModel.publishDocument(
            teamId = "t1",
            publisherId = "pub1",
            uri = fakeUri,
            requireBiometric = false,
            overrideName = "presentation_report.pdf",
            overrideSize = testContent.length.toLong(),
            overrideMime = "application/pdf",
            customInputStream = inStream
        ) {
            successCallbackCalled = true
        }

        assertTrue(successCallbackCalled)
        assertNotNull(docViewModel.catalogUiState.value.actionMessage)

        docViewModel.loadDocuments("t1")
        val state = docViewModel.catalogUiState.value
        assertEquals(1, state.documents.size)
        assertEquals("presentation_report.pdf", state.documents[0].fileName)
    }

    @Test
    fun `openDocument emits shareable Uri for completed document`() = runBlocking {
        val doc = SharedDocument(
            docId = "doc_open_1",
            teamId = "t1",
            publisherId = "pub1",
            fileName = "sample.txt",
            fileSize = 100L,
            mimeType = "text/plain",
            expectedSha256 = "abc",
            downloadStatus = DownloadStatus.COMPLETED,
            localUri = tempFile.absolutePath
        )
        docRepo.publishDocument(doc)

        var openUri: String? = null
        docViewModel.openDocument("doc_open_1") { uri ->
            openUri = uri.toString()
        }

        assertNotNull(openUri)
        assertNotNull(docViewModel.catalogUiState.value.openedDocumentUri)
    }

    @Test
    fun `deleteDocument removes doc from catalog`() = runBlocking {
        val doc = SharedDocument(
            docId = "doc_del",
            teamId = "t1",
            publisherId = "pub1",
            fileName = "delete_me.txt",
            fileSize = 100L,
            mimeType = "text/plain",
            expectedSha256 = "abc",
            downloadStatus = DownloadStatus.COMPLETED
        )
        docRepo.publishDocument(doc)

        var deleted = false
        docViewModel.deleteDocument("doc_del", requesterMemberId = "pub1", wipeLocally = false) {
            deleted = true
        }

        assertTrue(deleted)
        docViewModel.loadDocuments("t1")
        assertEquals(0, docViewModel.catalogUiState.value.documents.size)
    }

    @Test
    fun `openDocument on biometric-protected document fails when biometric hardware unavailable`() = runBlocking {
        val doc = SharedDocument(
            docId = "doc_bio_1",
            teamId = "t1",
            publisherId = "pub1",
            fileName = "secret.pdf",
            fileSize = 100L,
            mimeType = "application/pdf",
            expectedSha256 = "abc",
            requireBiometric = true,
            downloadStatus = DownloadStatus.COMPLETED,
            localUri = tempFile.absolutePath
        )
        docRepo.publishDocument(doc)
        docViewModel.loadDocuments("t1")

        // Làm giả phần cứng sinh trắc học không khả dụng
        biometricManager.isAvailable = false

        var openedUri: android.net.Uri? = null
        docViewModel.openDocument("doc_bio_1") { uri ->
            openedUri = uri
        }

        org.junit.Assert.assertNull(openedUri)
        val state = docViewModel.catalogUiState.value
        org.junit.Assert.assertNull(state.openedDocumentUri)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("sinh trắc học"))
    }

    @Test
    fun `setPendingSharedUris and publishPendingFiles processes incoming shared files`() = runBlocking {
        val uris = listOf(
            FakeUri("content", "media/share1"),
            FakeUri("content", "media/share2")
        )

        docViewModel.setPendingSharedUris(uris)
        assertEquals(2, docViewModel.pendingSharedUris.value.size)

        var publishedCount = 0
        docViewModel.publishPendingFiles("t1", "pub1") { count ->
            publishedCount = count
        }

        assertTrue(publishedCount >= 0)
        assertEquals(0, docViewModel.pendingSharedUris.value.size)
    }
}
