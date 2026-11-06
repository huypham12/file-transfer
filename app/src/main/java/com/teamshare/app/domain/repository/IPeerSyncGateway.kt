package com.teamshare.app.domain.repository

import com.teamshare.app.domain.model.QrInvitePayload
import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.domain.model.TeamMember

/**
 * Cổng giao tiếp đồng bộ trạng thái P2P giữa các thiết bị thành viên (LAN & Relay).
 */
interface IPeerSyncGateway {

    /**
     * Gửi yêu cầu bắt tay gia nhập nhóm (JOIN_REQUEST) tới Trưởng nhóm qua LAN Socket hoặc Relay Server,
     * đồng thời chờ phản hồi xác thực và danh bạ nhóm từ Trưởng nhóm.
     */
    suspend fun sendJoinRequest(
        qrPayload: QrInvitePayload,
        member: TeamMember
    ): Result<JoinSyncResponse>
}

data class JoinSyncResponse(
    val status: String,
    val message: String,
    val members: List<TeamMember>,
    val documents: List<SharedDocument>
)

