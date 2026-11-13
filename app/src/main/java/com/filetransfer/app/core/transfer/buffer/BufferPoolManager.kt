package com.filetransfer.app.core.transfer.buffer

import com.filetransfer.app.core.constant.AppConstants
import kotlinx.coroutines.sync.Semaphore
import java.util.Arrays
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bộ đệm tuần hoàn (Ring Buffer / Object Pool) quản lý các khối nhớ ByteArray cố định 256KB.
 * Giới hạn tối đa 20 khối (tương đương 5MB RAM), áp dụng Semaphore để kiểm soát Backpressure cho Coroutines,
 * đảm bảo hệ thống duy trì hiệu năng cao mà không gây áp lực GC heap theo đặc tả kiến trúc v2.2 và SRS-PERF-01.
 */
class BufferPoolManager(
    val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    val maxBuffers: Int = DEFAULT_MAX_BUFFERS
) {
    companion object {
        const val DEFAULT_BUFFER_SIZE = AppConstants.Buffer.CHUNK_SIZE_BYTES
        val DEFAULT_MAX_BUFFERS: Int
            get() = AppConstants.Buffer.MAX_POOL_CAPACITY
    }

    private val pool = ConcurrentLinkedQueue<ByteArray>()
    private val semaphore = Semaphore(maxBuffers)
    private val allocatedCount = AtomicInteger(0)

    /**
     * Thu hồi một khối đệm ByteArray từ hồ đệm hoặc cấp phát mới nếu chưa chạm giới hạn.
     * Tạm ngưng (suspend) coroutine người gọi nếu toàn bộ 64 khối đệm đang được sử dụng (Backpressure).
     */
    suspend fun acquire(): ByteArray {
        semaphore.acquire()
        val buffer = pool.poll()
        return if (buffer != null) {
            buffer
        } else {
            allocatedCount.incrementAndGet()
            ByteArray(bufferSize)
        }
    }

    /**
     * Thử thu hồi khối đệm không phong tỏa. Trả về null nếu không có buffer sẵn sàng.
     */
    fun tryAcquire(): ByteArray? {
        if (!semaphore.tryAcquire()) {
            return null
        }
        val buffer = pool.poll()
        return if (buffer != null) {
            buffer
        } else {
            allocatedCount.incrementAndGet()
            ByteArray(bufferSize)
        }
    }

    /**
     * Hoàn trả khối đệm về hồ.
     * Tẩy xóa sạch dữ liệu (zeroize) để tránh lưu vết nhạy cảm trong bộ nhớ heap, sau đó nhả semaphore permit.
     */
    fun release(buffer: ByteArray) {
        require(buffer.size == bufferSize) {
            "Kích thước buffer hoàn trả (${buffer.size}) không khớp với bufferSize của hồ ($bufferSize)"
        }
        // Xóa sạch bộ nhớ trước khi tái sử dụng (Zeroization)
        Arrays.fill(buffer, 0.toByte())
        pool.offer(buffer)
        semaphore.release()
    }

    /**
     * Số giấy phép (permits) hiện còn khả dụng trong Semaphore.
     */
    fun availablePermits(): Int = semaphore.availablePermits

    /**
     * Tổng số khối đệm thực tế đã được cấp phát trên heap.
     */
    fun totalAllocatedBuffers(): Int = allocatedCount.get()

    /**
     * Số khối đệm hiện đang nhàn rỗi trong pool.
     */
    fun idleBuffersCount(): Int = pool.size

    /**
     * Dọn sạch hồ đệm khi dừng phiên hoặc giải phóng tài nguyên.
     */
    fun clear() {
        pool.clear()
    }
}

