package com.teamshare.app.data.local.db.dao

import com.teamshare.app.data.local.db.entity.SecurityAuditLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Kiểm thử đơn vị cho [AuditLogDao] theo chuẩn an toàn SDD Chương 5.
 */
class AuditLogDaoTest {

    private lateinit var dao: AuditLogDao

    @Before
    fun setUp() {
        dao = InMemoryAuditLogDao()
    }

    @Test
    fun testInsertAndQueryRecentLogs() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertLog(
            SecurityAuditLogEntity(
                sessionId = "s_1",
                timestamp = now - 2000,
                eventType = "SESSION_CREATED",
                actorDevice = "LOCAL_DEVICE",
                channel = "DIRECT_LAN",
                severity = "INFO",
                details = "Đã khởi tạo phiên phát QR"
            )
        )
        dao.insertLog(
            SecurityAuditLogEntity(
                sessionId = "s_1",
                timestamp = now - 1000,
                eventType = "DEVICE_CONNECTED",
                actorDevice = "Redmi Turbo 4 Pro",
                channel = "DIRECT_LAN",
                severity = "INFO",
                details = "Máy nhận đã kết nối"
            )
        )
        dao.insertLog(
            SecurityAuditLogEntity(
                sessionId = "s_1",
                timestamp = now,
                eventType = "TRANSFER_COMPLETED",
                actorDevice = "LOCAL_DEVICE",
                channel = "DIRECT_LAN",
                severity = "INFO",
                details = "Truyền tệp thành công"
            )
        )

        val recent = dao.getRecentLogs(limit = 10).first()
        assertEquals(3, recent.size)
        // Kiểm tra sắp xếp giảm dần theo thời gian (mới nhất lên đầu)
        assertEquals("TRANSFER_COMPLETED", recent[0].eventType)
        assertEquals("DEVICE_CONNECTED", recent[1].eventType)
        assertEquals("SESSION_CREATED", recent[2].eventType)
    }

    @Test
    fun testGetLogsBySession() = runBlocking {
        dao.insertLog(SecurityAuditLogEntity(sessionId = "sess_A", timestamp = 100L, eventType = "EV_1", severity = "INFO", details = "A1"))
        dao.insertLog(SecurityAuditLogEntity(sessionId = "sess_B", timestamp = 200L, eventType = "EV_2", severity = "INFO", details = "B1"))
        dao.insertLog(SecurityAuditLogEntity(sessionId = "sess_A", timestamp = 300L, eventType = "EV_3", severity = "INFO", details = "A2"))

        val logsA = dao.getLogsBySession("sess_A").first()
        assertEquals(2, logsA.size)
        assertTrue(logsA.all { it.sessionId == "sess_A" })

        val logsB = dao.getLogsBySession("sess_B").first()
        assertEquals(1, logsB.size)
        assertEquals("sess_B", logsB[0].sessionId)
    }

    @Test
    fun testForeignKeyCascadeSetNullBehavior() = runBlocking {
        // Mô phỏng hành vi ON DELETE SET NULL của Room CSDL:
        // Khi phiên 'sess_temp' bị xóa hoặc hoàn tất, các bản ghi kiểm toán liên quan
        // không bị xóa mất (bất biến) mà chỉ chuyển sessionId sang null
        val inMemoryDao = dao as InMemoryAuditLogDao
        inMemoryDao.insertLog(
            SecurityAuditLogEntity(
                sessionId = "sess_to_delete",
                timestamp = 1000L,
                eventType = "TRANSFER_STARTED",
                severity = "INFO",
                details = "Bắt đầu truyền"
            )
        )

        val beforeDelete = dao.getRecentLogs(10).first()
        assertEquals("sess_to_delete", beforeDelete[0].sessionId)

        // Thực hiện mô phỏng trigger ON DELETE SET NULL khi xóa session
        inMemoryDao.simulateCascadeSetNull("sess_to_delete")

        val afterDelete = dao.getRecentLogs(10).first()
        assertEquals(1, afterDelete.size)
        assertNull("Khóa ngoại phải chuyển thành NULL khi session cha bị xóa", afterDelete[0].sessionId)
        assertEquals("TRANSFER_STARTED", afterDelete[0].eventType)
    }

    @Test
    fun testAuditQueryPerformanceUnder15ms() = runBlocking {
        val count = 100
        val startTime = System.currentTimeMillis()

        for (i in 1..count) {
            dao.insertLog(
                SecurityAuditLogEntity(
                    sessionId = "sess_$i",
                    timestamp = startTime + i,
                    eventType = "AUDIT_EVENT_$i",
                    actorDevice = "Device_$i",
                    channel = "DIRECT_LAN",
                    severity = "INFO",
                    details = "Chi tiết sự kiện $i"
                )
            )
        }

        // Khởi động trước (Warm-up JIT)
        dao.getRecentLogs(limit = 10).first()

        val queryCount = 50
        val queryStartTime = System.currentTimeMillis()
        repeat(queryCount) {
            dao.getRecentLogs(limit = 50).first()
        }
        val queryDuration = System.currentTimeMillis() - queryStartTime
        val avgQueryDuration = queryDuration.toDouble() / queryCount

        val totalDuration = System.currentTimeMillis() - startTime
        val avgWriteTime = totalDuration.toDouble() / count

        println("Audit performance: $count writes in ${totalDuration}ms (Avg write: ${avgWriteTime}ms), $queryCount queries in ${queryDuration}ms (Avg query: ${avgQueryDuration}ms)")
        assertTrue("Thời gian ghi log trung bình phải < 15ms (Thực tế: ${avgWriteTime}ms)", avgWriteTime < 15.0)
        assertTrue("Thời gian truy vấn log gần nhất trung bình phải < 15ms (Thực tế: ${avgQueryDuration}ms)", avgQueryDuration < 15.0)
    }
}

/**
 * Hiện thực hóa Thread-safe InMemory DAO cho SecurityAuditLogEntity.
 */
class InMemoryAuditLogDao : AuditLogDao {
    private val logs = CopyOnWriteArrayList<SecurityAuditLogEntity>()
    private val nextId = AtomicLong(1L)
    private val stateFlow = MutableStateFlow<List<SecurityAuditLogEntity>>(emptyList())

    private fun notifyChanged() {
        stateFlow.value = logs.toList()
    }

    override suspend fun insertLog(log: SecurityAuditLogEntity): Long {
        val id = nextId.getAndIncrement()
        val entityWithId = if (log.logId == 0L) log.copy(logId = id) else log
        logs.add(entityWithId)
        notifyChanged()
        return id
    }

    override suspend fun insertLogs(logs: List<SecurityAuditLogEntity>): List<Long> {
        val ids = mutableListOf<Long>()
        logs.forEach {
            ids.add(insertLog(it))
        }
        return ids
    }

    override fun getRecentLogs(limit: Int): Flow<List<SecurityAuditLogEntity>> {
        return stateFlow.map { list ->
            list.sortedByDescending { it.timestamp }.take(limit)
        }
    }

    override fun getLogsBySession(sessionId: String): Flow<List<SecurityAuditLogEntity>> {
        return stateFlow.map { list ->
            list.filter { it.sessionId == sessionId }.sortedByDescending { it.timestamp }
        }
    }

    override suspend fun getLogsSince(sinceTimestamp: Long): List<SecurityAuditLogEntity> {
        return logs.filter { it.timestamp > sinceTimestamp }.sortedBy { it.timestamp }
    }

    override suspend fun getLogsSince(teamId: String, sinceTimestamp: Long): List<SecurityAuditLogEntity> {
        return getLogsSince(sinceTimestamp)
    }

    override fun getLogsByTeam(teamId: String): Flow<List<SecurityAuditLogEntity>> {
        return getRecentLogs(100)
    }

    override suspend fun clearLogs(): Int {
        val size = logs.size
        logs.clear()
        notifyChanged()
        return size
    }

    fun simulateCascadeSetNull(deletedSessionId: String) {
        val updated = logs.map {
            if (it.sessionId == deletedSessionId) it.copy(sessionId = null) else it
        }
        logs.clear()
        logs.addAll(updated)
        notifyChanged()
    }
}
