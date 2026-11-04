package com.teamshare.app.domain.usecase.transfer

import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.storage.saf.SafeFileDeleter
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.repository.IAuditRepository
import java.io.File
import java.util.Locale

/**
 * UC-14: Xác thực toàn vẹn tệp đầu cuối bằng mã băm SHA-256 (SRS FR-TRF-04).
 * Tính toán mã băm SHA-256 của tệp tạm .part trên đĩa, đối chiếu với expectedSha256.
 * Nếu không khớp (dù chỉ 1 bit): Kích hoạt SafeFileDeleter ghi đè byte 0x00 để tiêu hủy
 * an toàn tệp hỏng chống giả mạo, ghi cảnh báo an ninh SECURITY_ALERT và trả về false.
 */
class VerifyChecksumUseCase(
    private val auditRepository: IAuditRepository? = null
) {
    suspend fun execute(
        tempFile: File,
        expectedSha256: String,
        docId: String = "",
        teamId: String = ""
    ): Boolean {
        if (!tempFile.exists() || !tempFile.isFile) return false

        val actualSha256 = Sha256StreamCalculator.calculateFileSha256(tempFile)
        val isMatched = actualSha256.equals(expectedSha256.trim(), ignoreCase = true)

        if (!isMatched) {
            // Tiêu hủy tệp hỏng bằng 0x00 theo chuẩn OWASP MASVS-STORAGE
            SafeFileDeleter.wipeAndSanitizeFile(tempFile)

            auditRepository?.logEvent(
                AuditLog(
                    teamId = teamId,
                    actorId = "system_verifier",
                    eventType = AuditEventType.CHECKSUM_FAILED,
                    severity = AuditSeverity.SECURITY_ALERT,
                    details = "Sai lệch mã băm SHA-256 trên tệp ${tempFile.name}. Kỳ vọng: $expectedSha256, Thực tế: $actualSha256. Đã tiêu hủy tệp an toàn."
                )
            )
        }

        return isMatched
    }

    suspend operator fun invoke(
        tempFile: File,
        expectedSha256: String,
        docId: String = "",
        teamId: String = ""
    ): Boolean = execute(tempFile, expectedSha256, docId, teamId)
}

