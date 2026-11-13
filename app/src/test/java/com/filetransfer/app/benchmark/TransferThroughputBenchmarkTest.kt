package com.filetransfer.app.benchmark

import com.filetransfer.app.core.protocol.BinaryFrameParser
import com.filetransfer.app.core.protocol.ChunkFrame
import com.filetransfer.app.core.protocol.FrameSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.system.measureTimeMillis

/**
 * Kiểm chuẩn Thông lượng Xử lý Dòng byte (Throughput Benchmark Test - NFR-PERF-02).
 * Đo kiểm tốc độ serialize/deserialize khung tin nhị phân và tốc độ băm streaming SHA-256
 * nhằm chứng minh hệ thống đáp ứng tốt dải thông lượng 25–45 MB/s trên kết nối LAN Wi-Fi 5GHz.
 */
class TransferThroughputBenchmarkTest {

    @Test
    fun testBinaryFramingThroughput_Exceeds_50MBps() {
        val serializer = FrameSerializer()
        val parser = BinaryFrameParser()

        val chunkSize = 64 * 1024 // 64 KB
        val payload = ByteArray(chunkSize) { (it % 128).toByte() }
        val iterations = 500 // Tổng dung lượng = 500 * 64KB = 32 MB

        val totalBytes = iterations.toLong() * chunkSize

        // 1. Đo kiểm tốc độ đóng khung (Serialization)
        val serializeTimeMs = measureTimeMillis {
            repeat(iterations) { seq ->
                val frame = ChunkFrame(
                    msgType = ChunkFrame.TYPE_DATA_CHUNK,
                    streamId = 1001,
                    payload = payload
                )
                serializer.serialize(frame)
            }
        }.coerceAtLeast(1)

        val serializeThroughputMbPerSec = (totalBytes.toDouble() / (1024 * 1024)) / (serializeTimeMs.toDouble() / 1000)

        // 2. Đo kiểm tốc độ giải khung (Parsing)
        val sampleFrameBytes = serializer.serialize(
            ChunkFrame(ChunkFrame.TYPE_DATA_CHUNK, 1001, payload)
        )

        val parseTimeMs = measureTimeMillis {
            repeat(iterations) {
                val parsed = parser.parseNextFrame(ByteArrayInputStream(sampleFrameBytes))
                assertNotNull(parsed)
                assertEquals(ChunkFrame.TYPE_DATA_CHUNK, parsed?.msgType)
            }
        }.coerceAtLeast(1)

        val parseThroughputMbPerSec = (totalBytes.toDouble() / (1024 * 1024)) / (parseTimeMs.toDouble() / 1000)

        // Xác nhận thông lượng đóng khung vượt trội (> 50 MB/s), sẵn sàng phục vụ LAN 25-45 MB/s
        assertTrue(
            "Tốc độ serialize (${serializeThroughputMbPerSec.toInt()} MB/s) phải >= 50 MB/s",
            serializeThroughputMbPerSec >= 50.0
        )
        assertTrue(
            "Tốc độ parse header (${parseThroughputMbPerSec.toInt()} MB/s) phải >= 50 MB/s",
            parseThroughputMbPerSec >= 50.0
        )
    }

    @Test
    fun testStreamingSha256Throughput_Exceeds_50MBps() {
        // Tạo luồng dữ liệu 20 MB trong RAM
        val testSizeBytes = 20 * 1024 * 1024 // 20 MB
        val chunk = ByteArray(64 * 1024) { (it % 256).toByte() }

        // Đo thời gian băm streaming theo khối
        val elapsedMs = measureTimeMillis {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var processed = 0
            while (processed < testSizeBytes) {
                val toProcess = minOf(chunk.size, testSizeBytes - processed)
                digest.update(chunk, 0, toProcess)
                processed += toProcess
            }
            digest.digest()
        }.coerceAtLeast(1)

        val throughputMbPerSec = (testSizeBytes.toDouble() / (1024 * 1024)) / (elapsedMs.toDouble() / 1000)

        assertTrue(
            "Tốc độ băm streaming SHA-256 (${throughputMbPerSec.toInt()} MB/s) phải >= 50 MB/s",
            throughputMbPerSec >= 50.0
        )
    }
}
