package com.teamshare.app.domain.usecase.transfer

import com.teamshare.app.core.platform.storage.IStorageAuditor
import com.teamshare.app.core.platform.storage.StatFsStorageAuditor
import com.teamshare.app.core.platform.storage.StorageAuditResult
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.repository.IAuditRepository
import java.io.File

/**
 * FR-RCV-02: Đo đĩa trống thực tế thiết bị nhận (StatFs) & Cảnh báo dung lượng.
 * Tuân thủ SDD Bảng 12.1 và Mục 4.6, 8.5.
 */
class CheckReceiverStorageUseCase(
    private val storageAuditor: IStorageAuditor = StatFsStorageAuditor(),
    private val auditRepository: IAuditRepository? = null
) {
    /**
     * Kiểm tra dung lượng đĩa khả dụng trước khi chấp nhận tải tệp/thư mục.
     *
     * @param requiredBytes Dung lượng tệp tin cần tải về (byte)
     * @param targetDir Thư mục tiếp nhận tải về (Sandbox)
     * @param sessionId Định danh phiên truyền (tùy chọn để ghi log)
     * @return StorageAuditResult kết quả đánh giá dung lượng chi tiết
     */
    suspend fun execute(
        requiredBytes: Long,
        targetDir: File? = null,
        sessionId: String = ""
    ): StorageAuditResult {
        val result = storageAuditor.auditStorage(requiredBytes, targetDir)
        if (!result.isSufficient) {
            auditRepository?.logEvent(
                AuditLog(
                    sessionId = sessionId,
                    actorId = "storage_auditor",
                    eventType = AuditEventType.SECURITY_ALERT,
                    severity = AuditSeverity.WARNING,
                    details = "Không đủ bộ nhớ lưu trữ: Cần ${result.requiredBytes} bytes + ${result.safetyMarginBytes} bytes buffer, nhưng chỉ còn ${result.availableBytes} bytes trống. Thiếu ${result.shortfallBytes} bytes."
                )
            )
        }
        return result
    }

    suspend operator fun invoke(requiredBytes: Long, targetDir: File? = null): Boolean {
        return execute(requiredBytes, targetDir).isSufficient
    }
}

