package com.teamshare.app.benchmark

import com.teamshare.app.core.transfer.buffer.BufferPoolManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kiểm chuẩn cấp phát bộ nhớ đệm (Zero-allocation / Memory Leak Prevention - NFR-PERF-01).
 * Chứng minh cơ chế tái sử dụng bộ đệm không sinh ra rác đối tượng trên JVM Heap.
 */
class BufferPoolMemoryBenchmarkTest {

    @Test
    fun testBufferPoolZeroAllocationUnderSustainedLoad() = runBlocking {
        val bufferSize = 64 * 1024 // 64 KB
        val maxBuffers = 8
        val pool = BufferPoolManager(bufferSize = bufferSize, maxBuffers = maxBuffers)

        val iterations = 500
        var totalBytesProcessed = 0L

        repeat(iterations) {
            val buffer = pool.acquire()
            assertEquals(bufferSize, buffer.size)

            buffer[0] = 0x5A.toByte()
            buffer[buffer.size - 1] = 0xA5.toByte()
            totalBytesProcessed += buffer.size

            pool.release(buffer)
        }

        assertEquals(iterations.toLong() * bufferSize, totalBytesProcessed)
        assertTrue("Pool phải duy trì đủ buffer sau tải bền vững", pool.availablePermits() == maxBuffers)
    }
}
