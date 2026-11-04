package com.teamshare.app.core.transfer.buffer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BufferPoolManagerTest {

    @Test
    fun testBufferAcquireAndRelease() = runBlocking {
        val pool = BufferPoolManager(bufferSize = 1024, maxBuffers = 4)
        assertEquals(4, pool.availablePermits())
        assertEquals(0, pool.totalAllocatedBuffers())

        val buf1 = pool.acquire()
        assertEquals(1024, buf1.size)
        assertEquals(3, pool.availablePermits())
        assertEquals(1, pool.totalAllocatedBuffers())

        // Ghi dữ liệu vào buf1
        buf1[0] = 0xAA.toByte()
        buf1[1023] = 0xBB.toByte()

        // Release buf1 -> phải được zeroize
        pool.release(buf1)
        assertEquals(4, pool.availablePermits())
        assertEquals(1, pool.idleBuffersCount())
        assertEquals(0, buf1[0].toInt())
        assertEquals(0, buf1[1023].toInt())

        // Reacquire -> nhận lại chính buffer cũ
        val bufReused = pool.acquire()
        assertSame(buf1, bufReused)
        assertEquals(3, pool.availablePermits())
    }

    @Test
    fun testTryAcquireWhenExhausted() {
        val pool = BufferPoolManager(bufferSize = 512, maxBuffers = 2)

        val buf1 = pool.tryAcquire()
        assertNotNull(buf1)
        val buf2 = pool.tryAcquire()
        assertNotNull(buf2)

        // Đã hết 2 permits
        val buf3 = pool.tryAcquire()
        assertNull(buf3)

        // Release 1 buffer
        pool.release(buf1!!)
        val buf4 = pool.tryAcquire()
        assertNotNull(buf4)
    }

    @Test
    fun testBackpressureSuspension() = runBlocking {
        val pool = BufferPoolManager(bufferSize = 256, maxBuffers = 1)

        val buf1 = pool.acquire()
        assertEquals(0, pool.availablePermits())

        var acquiredSecond = false
        val job = async(Dispatchers.Default) {
            val buf2 = pool.acquire()
            acquiredSecond = true
            pool.release(buf2)
        }

        // Chờ một chút để đảm bảo job đang bị tạm ngưng (suspended) bởi Semaphore
        delay(100)
        assertFalse("Coroutine phải bị chặn do Backpressure khi hồ đệm đã đầy", acquiredSecond)

        // Giải phóng buf1 -> coroutine tiếp tục chạy
        pool.release(buf1)
        job.await()
        assertTrue("Coroutine phải tiếp tục sau khi permit được giải phóng", acquiredSecond)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testReleaseInvalidBufferSizeThrowsException() {
        val pool = BufferPoolManager(bufferSize = 1024, maxBuffers = 2)
        val badBuffer = ByteArray(512) // Kích thước không đúng
        pool.release(badBuffer)
    }

    @Test
    fun testClearPool() = runBlocking {
        val pool = BufferPoolManager(bufferSize = 1024, maxBuffers = 2)
        val buf = pool.acquire()
        pool.release(buf)
        assertEquals(1, pool.idleBuffersCount())

        pool.clear()
        assertEquals(0, pool.idleBuffersCount())
    }
}

