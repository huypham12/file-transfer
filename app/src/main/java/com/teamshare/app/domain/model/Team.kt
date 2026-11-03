package com.teamshare.app.domain.model

/**
 * Thực thể quản lý Không gian Nhóm cộng tác (Team Workspace).
 */
data class Team(
    val teamId: String,
    val teamName: String,
    val description: String,
    val ownerFingerprint: String,
    val status: TeamStatus = TeamStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isActive: Boolean get() = status == TeamStatus.ACTIVE
}

