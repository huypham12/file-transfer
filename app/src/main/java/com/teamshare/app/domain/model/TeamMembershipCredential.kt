package com.teamshare.app.domain.model

/**
 * Chứng thư số hóa xác nhận tư cách thành viên do Trưởng nhóm (Owner) ký số.
 */
data class TeamMembershipCredential(
    val teamId: String,
    val memberId: String,
    val deviceFingerprint: String,
    val role: MemberRole,
    val issuedAt: Long,
    val ownerSignature: String
)

