package com.teamshare.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.teamshare.app.data.local.db.entity.TeamEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object cho bảng 'teams'.
 */
@Dao
interface TeamDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTeam(team: TeamEntity): Long

    @Query("SELECT * FROM teams WHERE team_id = :teamId LIMIT 1")
    fun getTeamById(teamId: String): Flow<TeamEntity?>

    @Query("SELECT * FROM teams WHERE team_id = :teamId LIMIT 1")
    suspend fun findTeamByIdSync(teamId: String): TeamEntity?

    @Query("SELECT * FROM teams ORDER BY created_at DESC")
    fun getAllTeams(): Flow<List<TeamEntity>>

    @Query("UPDATE teams SET status = 'DISBANDED', updated_at = :timestamp WHERE team_id = :teamId")
    suspend fun markDisbanded(teamId: String, timestamp: Long): Int

    @Query("DELETE FROM teams WHERE team_id = :teamId")
    suspend fun deleteTeam(teamId: String): Int
}

