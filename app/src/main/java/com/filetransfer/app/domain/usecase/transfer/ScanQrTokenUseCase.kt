package com.filetransfer.app.domain.usecase.transfer

import com.filetransfer.app.domain.model.AuditEventType
import com.filetransfer.app.domain.model.AuditLog
import com.filetransfer.app.domain.model.AuditSeverity
import com.filetransfer.app.domain.model.SessionQrPayload
import com.filetransfer.app.domain.repository.IAuditRepository

/**
 * FR-RCV-01: Quét mã QR & Phân giải Token ngoài băng.
 * Tuân thủ SDD Mục 8.1 và Bảng 12.1.
 */
class ScanQrTokenUseCase(
    private val auditRepository: IAuditRepository? = null
) {
    /**
     * Phân giải chuỗi mã QR raw nhận được từ Camera Scanner.
     * Kiểm tra cú pháp và thời hạn hiệu lực TTL 5 phút chống Replay Attack (mã lỗi 0x103).
     *
     * @param qrRawText Chuỗi văn bản quét được từ mã QR
     * @return Result<SessionQrPayload> Dữ liệu phân giải phiên
     */
    suspend fun execute(qrRawText: String): Result<SessionQrPayload> {
        return try {
            val payload = SessionQrPayload.parse(qrRawText)

            // Kiểm tra thời hạn hiệu lực (TTL 5 phút theo SDD 6.5 & 8.1)
            if (payload.isExpired) {
                auditRepository?.logEvent(
                    AuditLog(
                        sessionId = payload.sessionId,
                        actorId = "qr_scanner",
                        eventType = AuditEventType.SECURITY_ALERT,
                        severity = AuditSeverity.WARNING,
                        details = "Quét phải mã QR đã hết hạn hiệu lực (Mã lỗi 0x103: ERR_QR_EXPIRED)"
                    )
                )
                return Result.failure(SecurityException("0x103: Mã QR đã hết hạn hiệu lực. Vui lòng yêu cầu người gửi tạo mã mới."))
            }

            Result.success(payload)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend operator fun invoke(qrRawText: String): Result<SessionQrPayload> = execute(qrRawText)
}

