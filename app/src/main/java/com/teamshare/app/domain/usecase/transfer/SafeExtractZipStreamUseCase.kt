package com.teamshare.app.domain.usecase.transfer

import com.teamshare.app.core.transfer.buffer.BufferPoolManager
import com.teamshare.app.core.transfer.zip.SafeStreamExtractor
import com.teamshare.app.core.transfer.zip.ZipExtractResult
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.repository.IAuditRepository
import java.io.File
import java.io.InputStream

/**
 * FR-RCV-05: Giải nén an toàn thư mục chống tấn công Zip Slip.
 * Tuân thủ SDD Bảng 12.1 và Mục 4.5.3, 9.5.
 */
class SafeExtractZipStreamUseCase(
    private val bufferPool: BufferPoolManager? = null,
    private val auditRepository: IAuditRepository? = null
) {
    /**
     * Giải nén an toàn từ file nén ZIP vào thư mục Sandbox đích.
     */
    suspend fun execute(
        zipFile: File,
        destinationDir: File,
        sessionId: String = "",
        onProgress: ((bytesExtracted: Long, currentEntry: String) -> Unit)? = null
    ): Result<ZipExtractResult> {
        return try {
            val result = SafeStreamExtractor.extractZipFile(
                zipFile = zipFile,
                destinationDir = destinationDir,
                bufferPool = bufferPool,
                onProgress = onProgress
            )
            Result.success(result)
        } catch (e: SecurityException) {
            auditRepository?.logEvent(
                AuditLog(
                    sessionId = sessionId,
                    actorId = "zip_slip_validator",
                    eventType = AuditEventType.SECURITY_ALERT,
                    severity = AuditSeverity.SECURITY_ALERT,
                    details = "Phát hiện tấn công Zip Slip: ${e.message}. Toàn bộ tệp tạm đã được tiêu hủy."
                )
            )
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Giải nén an toàn từ luồng InputStream vào thư mục Sandbox đích.
     */
    suspend fun execute(
        inputStream: InputStream,
        destinationDir: File,
        sessionId: String = "",
        onProgress: ((bytesExtracted: Long, currentEntry: String) -> Unit)? = null
    ): Result<ZipExtractResult> {
        return try {
            val result = SafeStreamExtractor.extractZipStream(
                inputStream = inputStream,
                destinationDir = destinationDir,
                bufferPool = bufferPool,
                onProgress = onProgress
            )
            Result.success(result)
        } catch (e: SecurityException) {
            auditRepository?.logEvent(
                AuditLog(
                    sessionId = sessionId,
                    actorId = "zip_slip_validator",
                    eventType = AuditEventType.SECURITY_ALERT,
                    severity = AuditSeverity.SECURITY_ALERT,
                    details = "Phát hiện tấn công Zip Slip: ${e.message}. Toàn bộ tệp tạm đã được tiêu hủy."
                )
            )
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

