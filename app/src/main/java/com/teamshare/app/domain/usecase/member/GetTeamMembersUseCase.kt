package com.teamshare.app.domain.usecase.member

import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.repository.ITeamRepository
import kotlinx.coroutines.flow.Flow

/**
 * UC-05: Xem danh sách thành viên & Danh tính mật mã thiết bị (SRS FR-MEM-03).
 * Đọc luồng danh sách thành viên trong nhóm kèm vai trò và deviceFingerprint.
 */
class GetTeamMembersUseCase(
    private val teamRepository: ITeamRepository
) {
    operator fun invoke(teamId: String): Flow<List<TeamMember>> {
        return teamRepository.getMembersByTeam(teamId)
    }
}

