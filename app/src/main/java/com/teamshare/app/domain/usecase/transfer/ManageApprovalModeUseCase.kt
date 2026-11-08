package com.teamshare.app.domain.usecase.transfer

import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITransferRepository

/**
 * FR-SND-04: Quản lý Chế độ Phê duyệt Thiết bị (Auto-Accept vs Manual Approval).
 * Tuân thủ SDD Mục 7.4 và Bảng 12.1.
 */
class ManageApprovalModeUseCase(
    private val transferRepository: ITransferRepository,
    private val auditRepository: IAuditRepository? = null
) {
    /**
     * Cập nhật chế độ phê duyệt cho một phiên gửi đang hoạt động.
     *
     * @param sessionId Định danh phiên truyền
     * @param mode Chế độ phê duyệt mới (AUTO_ACCEPT hoặc MANUAL_APPROVAL)
     */
    suspend fun execute(sessionId: String, mode: ApprovalMode) {
        if (sessionId.isBlank()) return

        transferRepository.updateApprovalMode(sessionId, mode)

        auditRepository?.logEvent(
            AuditLog(
                sessionId = sessionId,
                actorId = "sender_ui",
                eventType = AuditEventType.SESSION_APPROVED,
                severity = AuditSeverity.INFO,
                details = "Đã cập nhật chế độ phê duyệt thiết bị sang: ${mode.name}"
            )
        )
    }

    suspend operator fun invoke(sessionId: String, mode: ApprovalMode) = execute(sessionId, mode)
}

