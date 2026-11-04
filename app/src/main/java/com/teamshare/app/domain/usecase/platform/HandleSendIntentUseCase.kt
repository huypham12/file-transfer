package com.teamshare.app.domain.usecase.platform

import android.net.Uri
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.usecase.document.PublishDocumentUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.io.InputStream

/**
 * UC-16: Tiếp nhận chia sẻ tệp từ app khác qua Android Send Intent (SRS FR-MOB-01).
 * Lặp qua danh sách Uri trích xuất từ ACTION_SEND / ACTION_SEND_MULTIPLE,
 * gọi PublishDocumentUseCase để băm SHA-256 stream và lưu vào không gian nhóm mục tiêu.
 */
class HandleSendIntentUseCase(
    private val publishDocumentUseCase: PublishDocumentUseCase,
    private val auditRepository: IAuditRepository? = null
) {
    operator fun invoke(
        intentUris: List<Uri>,
        targetTeamId: String,
        publisherId: String,
        customStreams: Map<Uri, InputStream>? = null
    ): Flow<Result<Int>> = flow {
        if (targetTeamId.isBlank() || publisherId.isBlank()) {
            emit(Result.failure(IllegalArgumentException("targetTeamId và publisherId không được để trống")))
            return@flow
        }

        if (intentUris.isEmpty()) {
            emit(Result.success(0))
            return@flow
        }

        val result: Result<Int> = try {
            var successCount = 0
            for (uri in intentUris) {
                val publishResult = publishDocumentUseCase(
                    teamId = targetTeamId,
                    publisherId = publisherId,
                    uri = uri,
                    customInputStream = customStreams?.get(uri)
                ).first()

                if (publishResult.isSuccess) {
                    successCount++
                }
            }

            auditRepository?.logEvent(
                AuditLog(
                    teamId = targetTeamId,
                    actorId = publisherId,
                    eventType = AuditEventType.DOCUMENT_PUBLISHED,
                    severity = AuditSeverity.INFO,
                    details = "Tiếp nhận và công bố thành công $successCount/${intentUris.size} tệp từ Android Sharesheet"
                )
            )

            Result.success(successCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        emit(result)
    }
}

