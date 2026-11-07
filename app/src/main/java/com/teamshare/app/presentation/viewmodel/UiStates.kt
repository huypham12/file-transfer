package com.teamshare.app.presentation.viewmodel

import android.graphics.Bitmap
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.QrInvitePayload
import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferProgress

/**
 * Trạng thái giao diện Màn hình Danh sách Nhóm (TeamListScreen).
 */
data class TeamListUiState(
    val teams: List<Team> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val newlyCreatedTeam: Team? = null
)

/**
 * Trạng thái giao diện Không gian làm việc Nhóm (WorkspaceScreen).
 */
data class TeamWorkspaceUiState(
    val team: Team? = null,
    val members: List<TeamMember> = emptyList(),
    val isOwner: Boolean = false,
    val currentMemberId: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val actionSuccessMessage: String? = null,
    val isDisbanded: Boolean = false
)

/**
 * Trạng thái giao diện Màn hình Lời mời QR (QrInviteScreen).
 */
data class QrInviteUiState(
    val payload: QrInvitePayload? = null,
    val qrBitmap: Bitmap? = null,
    val remainingSeconds: Long = 0L,
    val isExpired: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Trạng thái giao diện Tab Danh mục Tài liệu (DocumentCatalogTab).
 */
data class DocumentCatalogUiState(
    val documents: List<SharedDocument> = emptyList(),
    val folders: List<com.teamshare.app.domain.model.TeamFolder> = emptyList(),
    val currentFolderId: String? = null,
    val folderPath: List<com.teamshare.app.domain.model.TeamFolder> = emptyList(),
    val isLoading: Boolean = false,
    val isPublishing: Boolean = false,
    val errorMessage: String? = null,
    val actionMessage: String? = null,
    val openedDocumentUri: String? = null,
    val openedDocumentMimeType: String? = null
)

/**
 * Trạng thái giao diện Màn hình Tiến trình Truyền tải (TransferProgressScreen).
 * Được điều tiết chu kỳ tối thiểu 100ms để bảo vệ luồng đồ họa Compose.
 */
data class TransferUiState(
    val sessionId: String = "",
    val fileName: String = "",
    val totalBytes: Long = 0L,
    val transferredBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val progressPercent: Float = 0.0f,
    val channel: TransferChannel = TransferChannel.DIRECT_LAN,
    val state: TransferEngineState = TransferEngineState.IDLE,
    val sasCode: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun fromProgress(progress: TransferProgress, fileName: String = ""): TransferUiState {
            return TransferUiState(
                sessionId = progress.sessionId,
                fileName = fileName,
                totalBytes = progress.totalBytes,
                transferredBytes = progress.bytesTransferred,
                speedBytesPerSec = progress.speedBytesPerSec,
                progressPercent = progress.progressPercent,
                channel = progress.channel,
                state = progress.state,
                sasCode = progress.sasCode,
                errorCode = progress.errorCode,
                errorMessage = progress.errorMessage
            )
        }
    }
}

/**
 * Trạng thái giao diện Tab Nhật ký Hoạt động & Kiểm toán (ActivityLogTab).
 */
data class AuditUiState(
    val logs: List<AuditLog> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

