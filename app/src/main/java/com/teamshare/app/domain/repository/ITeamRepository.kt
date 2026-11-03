package com.teamshare.app.domain.repository

import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import kotlinx.coroutines.flow.Flow

/**
 * Hợp đồng dữ liệu quản lý Không gian Nhóm và Thành viên (Team & Members).
 */
interface ITeamRepository {
    suspend fun createTeam(team: Team): Result<Team>
    fun getTeamById(teamId: String): Flow<Team?>
    fun getAllTeams(): Flow<List<Team>>
    suspend fun disbandTeam(teamId: String): Result<Unit>
    suspend fun addMember(member: TeamMember): Result<Unit>
    suspend fun revokeMember(teamId: String, memberId: String): Result<Unit>
    fun getMembersByTeam(teamId: String): Flow<List<TeamMember>>
    suspend fun isMemberActive(teamId: String, memberId: String): Boolean
}

