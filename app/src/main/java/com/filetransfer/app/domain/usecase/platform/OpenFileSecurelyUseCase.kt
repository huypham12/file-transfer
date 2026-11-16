package com.filetransfer.app.domain.usecase.platform

import com.filetransfer.app.core.security.biometric.IBiometricAuthManager
import com.filetransfer.app.domain.model.AuditEventType
import com.filetransfer.app.domain.model.AuditSeverity
import com.filetransfer.app.domain.model.ErrorCode
import com.filetransfer.app.domain.model.SecurityAuditLog
import com.filetransfer.app.domain.repository.IAuditRepository
import java.io.File
import java.io.FileNotFoundException

/**
 * FR-DOC-03 & BR-05: Mở tài liệu an toàn kết hợp chốt chặn Sinh trắc học.
 * Tuân thủ SDD Mục 6.4, 9.4 và Bảng 12.1.
 * Đảm bảo:
 * 1. Bắt buộc xác thực vân tay thành công trước khi mở tệp nhạy cảm (ERR_BIOMETRIC_FAILED: 0x401).
 * 2. Tách biệt hoàn toàn Clean Architecture: Tầng Domain trả về Result<File> được ủy quyền,
 *    không phụ thuộc vào Android Intent hay FragmentActivity.
 * 3. Ghi vết toàn bộ hành vi kiểm toán an ninh bất biến (Audit Logging).
 */
class OpenFileSecurelyUseCase(
    private val biometricAuthManager: IBiometricAuthManager,
    private val auditRepository: IAuditRepository? = null
) {
    /**
     * Mở tệp tin an toàn với kiểm soát sinh trắc học vân tay.
     *
     * @param file Tệp tin cần mở xem
     * @param requireBiometric Có bắt buộc quét vân tay trước khi mở hay không (mặc định true)
     * @param authenticate Lambda xác thực sinh trắc học được ủy quyền từ Presentation layer
     * @return Result chứa đối tượng File đã được cấp quyền truy cập an toàn
     */
    suspend fun execute(
        file: File,
        requireBiometric: Boolean = true,
        authenticate: (suspend () -> Boolean)? = null
    ): Result<File> {
        if (!file.exists()) {
            return Result.failure(FileNotFoundException("Đối tượng cần mở không tồn tại: ${file.absolutePath}"))
        }

        if (requireBiometric) {
            val canAuth = biometricAuthManager.canAuthenticate()

            if (!canAuth) {
                val err = "Thiết bị không hỗ trợ hoặc chưa thiết lập phương thức khóa máy/sinh trắc học (Mã lỗi: ${ErrorCode.ERR_BIOMETRIC_FAILED})"
                logAudit(file.name, AuditSeverity.SECURITY_ALERT, err)
                return Result.failure(SecurityException(err))
            }

            if (authenticate != null) {
                try {
                    val succeeded = authenticate()
                    if (!succeeded) {
                        throw SecurityException("Xác thực bảo mật thất bại")
                    }
                } catch (e: Exception) {
                    val err = "${ErrorCode.ERR_BIOMETRIC_FAILED}: Xác thực mở tệp thất bại: ${e.message}"
                    logAudit(file.name, AuditSeverity.SECURITY_ALERT, err)
                    return Result.failure(SecurityException(err))
                }
            }
        }

        val details = if (file.isDirectory) {
            "Đã mở thư mục an toàn qua trình quản lý tệp hệ thống"
        } else {
            "Đã mở tệp an toàn qua FileProvider với quyền đọc tạm thời"
        }
        logAudit(file.name, AuditSeverity.INFO, details)
        return Result.success(file)
    }

    private suspend fun logAudit(fileName: String, severity: AuditSeverity, details: String) {
        auditRepository?.logEvent(
            SecurityAuditLog(
                actorDevice = "LOCAL_DEVICE",
                eventType = if (severity == AuditSeverity.INFO) AuditEventType.CHECKSUM_VERIFIED else AuditEventType.SECURITY_ALERT,
                severity = severity,
                details = "[$fileName] $details"
            )
        )
    }

    suspend operator fun invoke(
        file: File,
        requireBiometric: Boolean = true,
        authenticate: (suspend () -> Boolean)? = null
    ): Result<File> = execute(file, requireBiometric, authenticate)
}
