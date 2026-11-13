package com.filetransfer.app.domain.usecase.platform

import android.net.FakeUri
import androidx.fragment.app.FragmentActivity
import com.filetransfer.app.core.security.biometric.IBiometricAuthManager
import com.filetransfer.app.core.storage.saf.FileProviderGateway
import com.filetransfer.app.data.local.db.dao.InMemoryAuditLogDao
import com.filetransfer.app.data.repository.AuditRepositoryImpl
import com.filetransfer.app.domain.model.AuditSeverity
import com.filetransfer.app.domain.repository.IAuditRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class OpenFileSecurelyUseCaseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class MockBiometricAuthManager(
        var canAuth: Boolean = true,
        var shouldSucceed: Boolean = true
    ) : IBiometricAuthManager {
        override fun canAuthenticate(): Boolean = canAuth

        override fun authenticate(
            activity: FragmentActivity,
            title: String,
            subtitle: String,
            negativeButtonText: String,
            allowDeviceCredential: Boolean,
            onSuccess: () -> Unit,
            onError: (errorCode: Int, errString: String) -> Unit,
            onFailed: () -> Unit
        ) {
            if (shouldSucceed) {
                onSuccess()
            } else {
                onFailed()
            }
        }
    }

    private lateinit var mockBiometric: MockBiometricAuthManager
    private lateinit var fileProviderGateway: FileProviderGateway
    private lateinit var auditRepository: IAuditRepository

    @Before
    fun setUp() {
        mockBiometric = MockBiometricAuthManager()
        fileProviderGateway = FileProviderGateway(
            uriProvider = { file, auth -> FakeUri("content", "$auth/${file.name}") }
        )
        auditRepository = AuditRepositoryImpl(InMemoryAuditLogDao())
    }

    @Test
    fun testNonExistentFileFails() = runBlocking {
        val useCase = OpenFileSecurelyUseCase(mockBiometric, fileProviderGateway, auditRepository)
        val missingFile = File(tempFolder.root, "not_found.pdf")

        val result = useCase.execute(missingFile)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FileNotFoundException)
    }

    @Test
    fun testOpenWithoutBiometricRequirement() = runBlocking {
        val useCase = OpenFileSecurelyUseCase(mockBiometric, fileProviderGateway, auditRepository)
        val file = tempFolder.newFile("public_doc.txt").apply { writeText("Public Data") }

        val result = useCase.execute(file, mimeType = "text/plain", requireBiometric = false)
        assertTrue(result.isSuccess)

        val intent = result.getOrThrow()
        assertNotNull(intent)

        // Kiểm tra audit log
        val logs = auditRepository.getRecentLogs().first()
        assertTrue(logs.any { it.severity == AuditSeverity.INFO && it.details.contains("public_doc.txt") })
    }

    @Test
    fun testOpenWithBiometricSuccessInHeadlessMode() = runBlocking {
        val useCase = OpenFileSecurelyUseCase(mockBiometric, fileProviderGateway, auditRepository)
        val file = tempFolder.newFile("secret_blueprint.pdf").apply { writeText("Top Secret") }

        // Headless testing (activity == null) kiểm tra canAuthenticate() == true
        val result = useCase.execute(file, mimeType = "application/pdf", requireBiometric = true, activity = null)
        assertTrue(result.isSuccess)

        val intent = result.getOrThrow()
        assertNotNull(intent)

        // Kiểm tra audit log
        val logs = auditRepository.getRecentLogs().first()
        assertTrue(logs.any { it.severity == AuditSeverity.INFO && it.details.contains("secret_blueprint.pdf") })
    }

    @Test
    fun testDeviceWithoutBiometricHardwareFailsWithCode0x401() = runBlocking {
        mockBiometric.canAuth = false
        val useCase = OpenFileSecurelyUseCase(mockBiometric, fileProviderGateway, auditRepository)
        val file = tempFolder.newFile("restricted.dat").apply { writeText("Restricted") }

        // Thiết bị không hỗ trợ vân tay phải bị từ chối với mã lỗi 0x401
        val result = useCase.execute(file, mimeType = "application/octet-stream", requireBiometric = true)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue("Thông báo lỗi phải chứa mã 0x401", exception?.message?.contains("0x401") == true)

        // Kiểm tra audit log bảo mật
        val logs = auditRepository.getRecentLogs().first()
        assertTrue(logs.any { it.severity == AuditSeverity.SECURITY_ALERT && it.details.contains("0x401") })
    }

    @Test
    fun testOpenDirectorySuccessfully() = runBlocking {
        val useCase = OpenFileSecurelyUseCase(mockBiometric, fileProviderGateway, auditRepository)
        val directory = tempFolder.newFolder("extracted_folder")

        val result = useCase.execute(directory, requireBiometric = false)
        assertTrue("Mở thư mục phải thành công không quăng FileNotFoundException", result.isSuccess)

        val intent = result.getOrThrow()
        assertNotNull(intent)

        val logs = auditRepository.getRecentLogs().first()
        assertTrue(logs.any { it.severity == AuditSeverity.INFO && it.details.contains("thư mục") })
    }
}
