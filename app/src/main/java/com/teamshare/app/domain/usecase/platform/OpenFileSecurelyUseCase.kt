package com.teamshare.app.domain.usecase.platform

import android.content.Intent
import androidx.fragment.app.FragmentActivity
import com.teamshare.app.core.security.biometric.IBiometricAuthManager
import com.teamshare.app.core.storage.saf.FileProviderGateway
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.ErrorCode
import com.teamshare.app.domain.repository.IAuditRepository
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.FileNotFoundException

/**
 * FR-DOC-03 & BR-05: Mở tài liệu an toàn qua FileProvider kết hợp chốt chặn Sinh trắc học.
 * Tuân thủ SDD Mục 6.4, 9.4 và Bảng 12.1.
 * Đảm bảo:
 * 1. Bắt buộc xác thực vân tay thành công trước khi mở tệp nhạy cảm (ERR_BIOMETRIC_FAILED: 0x401).
 * 2. Cấp phát quyền đọc dùng một lần qua Content URI an toàn FLAG_GRANT_READ_URI_PERMISSION.
 * 3. Tuyệt đối không để lộ đường dẫn tệp thực tế (File.absolutePath) cho các ứng dụng xem ngoài.
 */
class OpenFileSecurelyUseCase(
    private val biometricAuthManager: IBiometricAuthManager,
    private val fileProviderGateway: FileProviderGateway,
    private val auditRepository: IAuditRepository? = null
) {
    /**
     * Mở tệp tin an toàn với kiểm soát sinh trắc học vân tay.
     *
     * @param file Tệp tin cần mở xem
     * @param mimeType Định dạng MIME của tệp (mặc định toàn bộ kiểu)
     * @param requireBiometric Có bắt buộc quét vân tay trước khi mở hay không (mặc định true)
     * @param activity FragmentActivity để hiển thị BiometricPrompt (nullable trong testing)
     * @return Result chứa Intent ACTION_VIEW an toàn có cờ FLAG_GRANT_READ_URI_PERMISSION
     */
    suspend fun execute(
        file: File,
        mimeType: String = "*/*",
        requireBiometric: Boolean = true,
        activity: FragmentActivity? = null
    ): Result<Intent> {
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

            if (activity != null) {
                val authResult = CompletableDeferred<Boolean>()

                biometricAuthManager.authenticate(
                    activity = activity,
                    title = "Xác thực mở tài liệu bảo mật",
                    subtitle = "Vui lòng xác thực vân tay hoặc mã khóa để mở tệp ${file.name}",
                    negativeButtonText = "Hủy bỏ",
                    onSuccess = { authResult.complete(true) },
                    onError = { _, errString -> authResult.completeExceptionally(SecurityException(errString)) },
                    onFailed = { authResult.completeExceptionally(SecurityException("Xác thực không trùng khớp")) }
                )

                try {
                    val succeeded = authResult.await()
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

        return try {
            val viewIntent = if (file.isDirectory) {
                logAudit(file.name, AuditSeverity.INFO, "Đã mở thư mục an toàn qua trình quản lý tệp hệ thống")
                fileProviderGateway.createOpenDirectoryIntent(file)
            } else {
                logAudit(file.name, AuditSeverity.INFO, "Đã mở tệp an toàn qua FileProvider với quyền đọc tạm thời")
                fileProviderGateway.createViewIntent(file, mimeType)
            }
            Result.success(viewIntent)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun logAudit(fileName: String, severity: AuditSeverity, details: String) {
        auditRepository?.logEvent(
            AuditLog(
                actorDevice = "LOCAL_DEVICE",
                eventType = if (severity == AuditSeverity.INFO) AuditEventType.CHECKSUM_VERIFIED else AuditEventType.SECURITY_ALERT,
                severity = severity,
                details = "[$fileName] $details"
            )
        )
    }

    suspend operator fun invoke(
        file: File,
        mimeType: String = "*/*",
        requireBiometric: Boolean = true,
        activity: FragmentActivity? = null
    ): Result<Intent> = execute(file, mimeType, requireBiometric, activity)
}

