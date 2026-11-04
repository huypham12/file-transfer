package com.teamshare.app.domain.usecase.document

import android.net.FakeUri
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.domain.repository.IDocumentRepository
import com.teamshare.app.domain.usecase.team.FakeAuditRepository
import com.teamshare.app.domain.usecase.team.FakeTeamRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class FakeDocumentRepository : IDocumentRepository {
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

class DocumentUseCasesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var documentRepository: FakeDocumentRepository
    private lateinit var auditRepository: FakeAuditRepository

    private lateinit var publishDocumentUseCase: PublishDocumentUseCase
    private lateinit var getDocumentCatalogUseCase: GetDocumentCatalogUseCase
    private lateinit var openSecureDocumentUseCase: OpenSecureDocumentUseCase
    private lateinit var deleteDocumentUseCase: DeleteDocumentUseCase

    private val testTeamId = "team_docs_101"

    @Before
    fun setUp() {
        documentRepository = FakeDocumentRepository()
        auditRepository = FakeAuditRepository()

        publishDocumentUseCase = PublishDocumentUseCase(documentRepository, null, auditRepository)
        getDocumentCatalogUseCase = GetDocumentCatalogUseCase(documentRepository)
        openSecureDocumentUseCase = OpenSecureDocumentUseCase(
            documentRepository = documentRepository,
            fileProviderGateway = null,
            directoryManager = null,
            uriConverter = { file -> FakeUri("file", file.absolutePath) }
        )
        deleteDocumentUseCase = DeleteDocumentUseCase(documentRepository, null, auditRepository, FakeTeamRepository())
    }

    @Test
    fun testPublishDocumentSuccess() = runBlocking {
        val testContent = "TeamShare Secure Document Content"
        val inStream = ByteArrayInputStream(testContent.toByteArray(Charsets.UTF_8))
        val fakeUri = FakeUri("content", "media/external/files/100")

        val result = publishDocumentUseCase(
            teamId = testTeamId,
            publisherId = "pub_01",
            uri = fakeUri,
            requireBiometric = true,
            overrideName = "test_report.pdf",
            overrideSize = testContent.length.toLong(),
            overrideMime = "application/pdf",
            customInputStream = inStream
        ).first()

        assertTrue(result.isSuccess)
        val doc = result.getOrThrow()
        assertEquals("test_report.pdf", doc.fileName)
        assertEquals(testTeamId, doc.teamId)
        assertTrue(doc.requireBiometric)
        assertEquals(DownloadStatus.COMPLETED, doc.downloadStatus)
        assertTrue(doc.expectedSha256.isNotEmpty())

        val catalog = getDocumentCatalogUseCase(testTeamId).first()
        assertEquals(1, catalog.size)
    }

    @Test
    fun testPublishDocumentFailsOnZeroSize() = runBlocking {
        val fakeUri = FakeUri("content", "media/external/files/101")
        val inStream = ByteArrayInputStream(ByteArray(0))

        val result = publishDocumentUseCase(
            teamId = testTeamId,
            publisherId = "pub_01",
            uri = fakeUri,
            overrideName = "empty.txt",
            overrideSize = 0L,
            customInputStream = inStream
        ).first()

        assertTrue(result.isFailure)
    }

    @Test
    fun testOpenSecureDocumentSuccess() = runBlocking {
        val localFile = tempFolder.newFile("open_test.pdf")
        localFile.writeText("sample data")

        val doc = SharedDocument(
            docId = "doc_open_1",
            teamId = testTeamId,
            publisherId = "pub_01",
            fileName = "open_test.pdf",
            fileSize = 100L,
            mimeType = "application/pdf",
            expectedSha256 = "sha256_dummy",
            localUri = localFile.absolutePath,
            downloadStatus = DownloadStatus.COMPLETED
        )
        documentRepository.publishDocument(doc)

        val result = openSecureDocumentUseCase("doc_open_1").first()
        assertTrue(result.isSuccess)
        val uri = result.getOrThrow()
        assertNotNull(uri)
    }

    @Test
    fun testDeleteDocumentWithWipeSuccess() = runBlocking {
        val localFile = tempFolder.newFile("file_to_wipe.txt")
        localFile.writeBytes(ByteArray(1024) { 0x55.toByte() })

        val doc = SharedDocument(
            docId = "doc_delete_1",
            teamId = testTeamId,
            publisherId = "pub_01",
            fileName = "file_to_wipe.txt",
            fileSize = 1024L,
            mimeType = "text/plain",
            expectedSha256 = "sha256_dummy",
            localUri = localFile.absolutePath,
            downloadStatus = DownloadStatus.COMPLETED
        )
        documentRepository.publishDocument(doc)

        val result = deleteDocumentUseCase("doc_delete_1", requesterId = "pub_01", wipeLocally = true).first()
        assertTrue(result.isSuccess)

        // Verify removed from repository
        val remaining = documentRepository.getDocumentById("doc_delete_1")
        assertEquals(null, remaining)
    }

    @Test
    fun testDeleteDocumentAllowedByOwner() = runBlocking {
        val localFile = tempFolder.newFile("file_owner_delete.txt")
        val doc = SharedDocument(
            docId = "doc_delete_owner",
            teamId = testTeamId,
            publisherId = "pub_member_01",
            fileName = "file_owner_delete.txt",
            fileSize = 512L,
            mimeType = "text/plain",
            expectedSha256 = "sha256_dummy",
            localUri = localFile.absolutePath,
            downloadStatus = DownloadStatus.COMPLETED
        )
        documentRepository.publishDocument(doc)

        // Owner xóa tài liệu do thành viên khác tải lên
        val result = deleteDocumentUseCase(
            docId = "doc_delete_owner",
            requesterId = "owner_id_999",
            requesterRole = MemberRole.OWNER,
            wipeLocally = true
        ).first()

        assertTrue(result.isSuccess)
        assertEquals(null, documentRepository.getDocumentById("doc_delete_owner"))
    }

    @Test
    fun testDeleteDocumentForbiddenForOtherMembers() = runBlocking {
        val localFile = tempFolder.newFile("file_unauthorized_delete.txt")
        val doc = SharedDocument(
            docId = "doc_delete_forbidden",
            teamId = testTeamId,
            publisherId = "pub_member_01",
            fileName = "file_unauthorized_delete.txt",
            fileSize = 512L,
            mimeType = "text/plain",
            expectedSha256 = "sha256_dummy",
            localUri = localFile.absolutePath,
            downloadStatus = DownloadStatus.COMPLETED
        )
        documentRepository.publishDocument(doc)

        // Thành viên khác cố tình xóa tài liệu của pub_member_01
        val result = deleteDocumentUseCase(
            docId = "doc_delete_forbidden",
            requesterId = "attacker_member_02",
            requesterRole = MemberRole.MEMBER,
            wipeLocally = true
        ).first()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertNotNull(documentRepository.getDocumentById("doc_delete_forbidden"))
    }
}
