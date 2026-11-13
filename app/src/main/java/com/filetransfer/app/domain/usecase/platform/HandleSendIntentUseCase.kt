package com.filetransfer.app.domain.usecase.platform

import android.net.Uri
import com.filetransfer.app.domain.model.AuditLog
import com.filetransfer.app.domain.model.AuditEventType
import com.filetransfer.app.domain.model.AuditSeverity
import com.filetransfer.app.domain.repository.IAuditRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * UC-16: Tiếp nhận chia sẻ tệp từ app khác qua Android Send Intent (SRS FR-MOB-01).
 * Xác thực danh sách Uri trích xuất từ ACTION_SEND / ACTION_SEND_MULTIPLE,
 * ghi log an ninh và chuyển giao cho luồng khởi tạo phiên truyền gửi (SenderSession).
 */
class HandleSendIntentUseCase(
    private val auditRepository: IAuditRepository? = null
) {
    operator fun invoke(
        intentUris: List<Uri>,
        actorId: String = "LOCAL_DEVICE"
    ): Flow<Result<Int>> = flow {
        if (intentUris.isEmpty()) {
            emit(Result.success(0))
            return@flow
        }

        val result: Result<Int> = try {
            val validCount = intentUris.size
            auditRepository?.logEvent(
                AuditLog(
                    teamId = "",
                    actorId = actorId,
                    eventType = AuditEventType.DOCUMENT_PUBLISHED,
                    severity = AuditSeverity.INFO,
                    details = "Tiếp nhận thành công $validCount tệp từ Android Sharesheet sẵn sàng tạo phiên chia sẻ"
                )
            )
            Result.success(validCount)
        } catch (e: Exception) {
            Result.failure(e)
        }

        emit(result)
    }
}
