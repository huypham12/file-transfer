package com.teamshare.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.teamshare.app.data.local.db.entity.RevocationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RevocationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRevocation(record: RevocationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRevocations(records: List<RevocationEntity>): List<Long>

    @Query("SELECT * FROM revocation_records WHERE team_id = :teamId ORDER BY revoked_at DESC")
    fun getRevocationsByTeam(teamId: String): Flow<List<RevocationEntity>>

    @Query("SELECT * FROM revocation_records WHERE team_id = :teamId AND revoked_member_id = :memberId LIMIT 1")
    suspend fun getRevocationForMember(teamId: String, memberId: String): RevocationEntity?

    @Query("SELECT * FROM revocation_records WHERE team_id = :teamId AND revocation_type = 'TEAM_DISBAND' LIMIT 1")
    suspend fun getTeamDisbandRecord(teamId: String): RevocationEntity?

    @Query("SELECT * FROM revocation_records WHERE team_id = :teamId")
    suspend fun getAllRevocationsList(teamId: String): List<RevocationEntity>
}
