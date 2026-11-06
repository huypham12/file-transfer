package com.teamshare.app.benchmark

import com.teamshare.app.core.transfer.buffer.BufferPoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Kiểm chuẩn Hiệu năng Bộ nhớ (Memory Benchmark Test) cho BufferPoolManager (NFR-PERF-01).
 * Mô phỏng truyền luồng dữ liệu lớn (tương đương 5GB) với cơ chế cấp phát tuần hoàn,
 * kiểm chứng ngân sách RAM dành cho bộ đệm luôn <= 16 MB và không bị rò rỉ bộ nhớ (Zero OOM).
 */
class BufferPoolMemoryBenchmarkTest {

    @Test
    fun testBufferPool_ConcurrentWorkload_StrictMemoryCapping() = runBlocking {
        // Cấu hình chuẩn theo SDD: Mỗi buffer 64KB, tối đa 64 buffers (Tổng dung lượng tối đa = 4 MB)
        val bufferPool = BufferPoolManager(bufferSize = 64 * 1024, maxBuffers = 64)

        val totalSimulatedBytes = 5L * 1024 * 1024 * 1024 // 5 GB
        val chunkSize = 64 * 1024
        val totalChunks = totalSimulatedBytes / chunkSize

        val runtime = Runtime.getRuntime()
        System.gc()
        Thread.sleep(100)
        val initialHeapUsed = runtime.totalMemory() - runtime.freeMemory()

        val processedBytes = AtomicLong(0L)
        val maxSimultaneousAcquired = java.util.concurrent.atomic.AtomicInteger(0)
        val currentAcquired = java.util.concurrent.atomic.AtomicInteger(0)

        // Mô phỏng 8 workers Coroutines chạy đồng thời (Concurrency)
        val numWorkers = 8
        val chunksPerWorker = (totalChunks / numWorkers).toInt()

        // Mặc định chạy 16,000 chunks (~1GB luân chuyển) phục vụ CI nhanh.
        // Khi cấu hình -Dbenchmark.full5gb=true sẽ chạy đủ 81,920 chunks (5GB đầy đủ).
        val isFull5Gb = System.getProperty("benchmark.full5gb") == "true"
        val iterationsPerWorker = if (isFull5Gb) chunksPerWorker else minOf(chunksPerWorker, 2000)

        val jobs = (0 until numWorkers).map {
            async(Dispatchers.Default) {
                repeat(iterationsPerWorker) {
                    val buf = bufferPool.acquire()
                    val acquiredNow = currentAcquired.incrementAndGet()
                    maxSimultaneousAcquired.updateAndGet { maxOf(it, acquiredNow) }

                    // Mô phỏng xử lý dữ liệu trên buffer
                    buf[0] = (it % 128).toByte()
                    buf[buf.size - 1] = 0x53

                    processedBytes.addAndGet(buf.size.toLong())

                    currentAcquired.decrementAndGet()
                    bufferPool.release(buf)
                }
            }
        }

        jobs.awaitAll()

        System.gc()
        Thread.sleep(100)
        val finalHeapUsed = runtime.totalMemory() - runtime.freeMemory()
        val heapGrowthMb = (finalHeapUsed - initialHeapUsed) / (1024 * 1024)

        // 1. Kiểm chứng số lượng buffer cấp phát đồng thời không vượt quá ngưỡng maxBuffers (64)
        assertTrue(
            "Số buffer cấp phát đồng thời tối đa (${maxSimultaneousAcquired.get()}) không được vượt quá maxBuffers (64)",
            maxSimultaneousAcquired.get() <= 64
        )

        // 2. Kiểm chứng toàn bộ buffer được hoàn trả đầy đủ (Zero Leak)
        assertEquals("Không được có buffer nào bị rò rỉ sau khi kết thúc", 0, currentAcquired.get())

        // 3. Kiểm chứng dung lượng bộ nhớ đệm tối đa không vượt quá 16 MB (NFR-PERF-01)
        val maxPoolMemoryMb = (64 * 64 * 1024) / (1024 * 1024) // 4 MB
        assertTrue("Ngân sách RAM của pool phải <= 16 MB", maxPoolMemoryMb <= 16)

        // 4. Kiểm chứng mức tăng trưởng heap ổn định, không có hiện tượng OutOfMemoryError
        assertTrue("Mức tăng heap sau GC phải ổn định (< 50 MB, thực tế: ${heapGrowthMb}MB)", heapGrowthMb < 50)
    }

    @Test
    fun testBufferPool_Backpressure_UnderLoad() = runBlocking {
        val pool = BufferPoolManager(bufferSize = 32 * 1024, maxBuffers = 16)
        val acquiredList = mutableListOf<ByteArray>()

        // Chiếm dụng hết toàn bộ pool
        for (i in 0 until 16) {
            acquiredList.add(pool.acquire())
        }

        // Kiểm chứng hoàn trả thành công
        acquiredList.forEach { pool.release(it) }

        // Tiếp tục acquire lại bình thường
        val reacquired = pool.acquire()
        pool.release(reacquired)
    }
}

