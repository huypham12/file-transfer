package com.teamshare.app.core.transfer.checkpoint

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class CheckpointTrackerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCheckpointFlushesAtThreshold() = runBlocking {
        val testFile = tempFolder.newFile("test_checkpoint.bin")
        val raf = RandomAccessFile(testFile, "rw")
        val channel = raf.channel

        var persistedOffset = -1L
        val tracker = CheckpointTracker(
            sessionId = "session-test-01",
            flushThreshold = 1000L, // Ngưỡng test nhỏ: 1000 bytes
            onCheckpointPersisted = { persistedOffset = it }
        )

        // Ghi 600 bytes -> chưa đạt ngưỡng
        val ack1 = tracker.recordBytesWritten(channel, 600)
        assertNull(ack1)
        assertEquals(600L, tracker.getCurrentOffset())
        assertEquals(600L, tracker.getUnconfirmedBytes())
        assertEquals(-1L, persistedOffset)

        // Ghi tiếp 500 bytes -> tổng 1100 bytes >= 1000 -> kích hoạt flush!
        val ack2 = tracker.recordBytesWritten(channel, 500)
        assertNotNull(ack2)
        assertEquals(1100L, ack2)
        assertEquals(1100L, tracker.getCurrentOffset())
        assertEquals(1100L, tracker.getLastConfirmedOffset())
        assertEquals(0L, tracker.getUnconfirmedBytes())
        assertEquals(1100L, persistedOffset)

        channel.close()
        raf.close()
    }

    @Test
    fun testFlushFinalHandlesResidualBytes() = runBlocking {
        val testFile = tempFolder.newFile("test_final.bin")
        val raf = RandomAccessFile(testFile, "rw")
        val channel = raf.channel

        var persistedOffset = -1L
        val tracker = CheckpointTracker(
            sessionId = "session-test-02",
            flushThreshold = 2000L,
            onCheckpointPersisted = { persistedOffset = it }
        )

        // Ghi 800 bytes -> chưa đạt ngưỡng
        tracker.recordBytesWritten(channel, 800)
        assertEquals(-1L, persistedOffset)
        assertEquals(800L, tracker.getUnconfirmedBytes())

        // Kết thúc phiên truyền -> flushFinal
        val finalAck = tracker.flushFinal(channel)
        assertEquals(800L, finalAck)
        assertEquals(800L, tracker.getLastConfirmedOffset())
        assertEquals(0L, tracker.getUnconfirmedBytes())
        assertEquals(800L, persistedOffset)

        channel.close()
        raf.close()
    }

    @Test
    fun testInitOffsetForResume() = runBlocking {
        val testFile = tempFolder.newFile("test_resume.bin")
        val raf = RandomAccessFile(testFile, "rw")
        val channel = raf.channel

        var persistedOffset = -1L
        val tracker = CheckpointTracker(
            sessionId = "session-test-03",
            flushThreshold = 1000L,
            onCheckpointPersisted = { persistedOffset = it }
        )

        // Khởi tạo resume từ 5000L
        tracker.initOffset(5000L)
        assertEquals(5000L, tracker.getCurrentOffset())
        assertEquals(5000L, tracker.getLastConfirmedOffset())
        assertEquals(0L, tracker.getUnconfirmedBytes())

        // Ghi thêm 1200 bytes
        val ack = tracker.recordBytesWritten(channel, 1200)
        assertNotNull(ack)
        assertEquals(6200L, ack)
        assertEquals(6200L, tracker.getCurrentOffset())
        assertEquals(6200L, tracker.getLastConfirmedOffset())
        assertEquals(6200L, persistedOffset)

        channel.close()
        raf.close()
    }
}

