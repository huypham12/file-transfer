package com.teamshare.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.teamshare.app.data.local.db.entity.MemberEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object cho bảng 'team_members'.
 */
@Dao
interface MemberDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: MemberEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<MemberEntity>): List<Long>

    @Query("SELECT * FROM team_members WHERE team_id = :teamId ORDER BY joined_at ASC")
    fun getMembersByTeam(teamId: String): Flow<List<MemberEntity>>

    @Query("SELECT * FROM team_members WHERE member_id = :memberId LIMIT 1")
    suspend fun getMemberById(memberId: String): MemberEntity?

    @Query("UPDATE team_members SET is_active = :isActive WHERE team_id = :teamId AND member_id = :memberId")
    suspend fun updateActiveStatus(teamId: String, memberId: String, isActive: Boolean): Int

    @Query("SELECT is_active FROM team_members WHERE team_id = :teamId AND member_id = :memberId LIMIT 1")
    suspend fun isMemberActive(teamId: String, memberId: String): Boolean?

    @Query("DELETE FROM team_members WHERE team_id = :teamId AND member_id = :memberId")
    suspend fun deleteMember(teamId: String, memberId: String): Int
}

