package com.teamshare.app.domain.model

/**
 * Thực thể quản lý Thành viên trong Không gian Nhóm.
 */
data class TeamMember(
    val memberId: String,
    val teamId: String,
    val memberName: String,
    val deviceFingerprint: String,
    val role: MemberRole = MemberRole.MEMBER,
    val credentialData: String,
    val isActive: Boolean = true,
    val joinedAt: Long = System.currentTimeMillis()
) {
    val isOwner: Boolean get() = role == MemberRole.OWNER
}

