package com.teamshare.app.data.repository

import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.data.local.db.dao.MemberDao
import com.teamshare.app.data.local.db.dao.TeamDao
import com.teamshare.app.data.local.db.mapper.toDomain
import com.teamshare.app.data.local.db.mapper.toEntity
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.domain.repository.ITeamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Hiện thực hóa ITeamRepository quản lý Không gian Nhóm và Thành viên (Team & Members).
 */
class TeamRepositoryImpl(
    private val teamDao: TeamDao,
    private val memberDao: MemberDao,
    private val keyManager: IKeyManager
) : ITeamRepository {

    override suspend fun createTeam(team: Team): Result<Team> = withContext(Dispatchers.IO) {
        runCatching {
            teamDao.insertTeam(team.toEntity())
            team
        }
    }

    override fun getTeamById(teamId: String): Flow<Team?> {
        return teamDao.getTeamById(teamId).map { it?.toDomain() }
    }

    override fun getAllTeams(): Flow<List<Team>> {
        return teamDao.getAllTeams().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun disbandTeam(teamId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            teamDao.markDisbanded(teamId, System.currentTimeMillis())
            // Xóa khóa Master của Owner trong Keystore liên quan tới nhóm này
            val keyAlias = IKeyManager.MASTER_KEY_PREFIX + teamId
            keyManager.deleteKey(keyAlias)
            Unit
        }
    }

    override suspend fun addMember(member: TeamMember): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            memberDao.insertMember(member.toEntity())
            Unit
        }
    }

    override suspend fun revokeMember(teamId: String, memberId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            memberDao.updateActiveStatus(teamId, memberId, false)
            Unit
        }
    }

    override fun getMembersByTeam(teamId: String): Flow<List<TeamMember>> {
        return memberDao.getMembersByTeam(teamId).map { list -> list.map { it.toDomain() } }
    }

    override suspend fun isMemberActive(teamId: String, memberId: String): Boolean = withContext(Dispatchers.IO) {
        val team = teamDao.findTeamByIdSync(teamId)
        if (team == null || team.status != TeamStatus.ACTIVE.name) {
            return@withContext false
        }
        memberDao.isMemberActive(teamId, memberId) ?: false
    }
}

