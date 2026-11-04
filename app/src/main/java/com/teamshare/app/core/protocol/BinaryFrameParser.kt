package com.teamshare.app.core.protocol

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phân tích cú pháp và giải mã luồng byte nhị phân thành các đối tượng ChunkFrame theo SDD Chương 6.2.
 */
class BinaryFrameParser {

    /**
     * Đọc tuần tự 1 ChunkFrame từ luồng InputStream.
     * Trả về null nếu đạt điểm kết thúc luồng (EOF) một cách sạch sẽ trước khi frame mới bắt đầu.
     * Ném FrameCorruptedException nếu luồng bị ngắt giữa chừng, sai Magic bytes hoặc kích thước payload bất thường.
     */
    @Throws(FrameCorruptedException::class)
    fun parseNextFrame(inputStream: InputStream): ChunkFrame? {
        val headerBuffer = ByteArray(ChunkFrame.HEADER_SIZE)
        val readBytes = readFully(inputStream, headerBuffer, 0, ChunkFrame.HEADER_SIZE, allowPartialAtStart = true)
        if (readBytes == 0) {
            return null // Đóng luồng sạch sẽ
        }
        if (readBytes < ChunkFrame.HEADER_SIZE) {
            throw FrameCorruptedException(
                "Luồng kết nối bị ngắt giữa chừng khi chưa nhận đủ header (đã đọc $readBytes / ${ChunkFrame.HEADER_SIZE} bytes)"
            )
        }

        // Kiểm tra Magic bytes 0x53 0x46 ("SF")
        if (headerBuffer[0] != ChunkFrame.MAGIC_BYTE_0 || headerBuffer[1] != ChunkFrame.MAGIC_BYTE_1) {
            val hex0 = String.format("%02X", headerBuffer[0])
            val hex1 = String.format("%02X", headerBuffer[1])
            throw FrameCorruptedException("Sai Magic bytes: mong đợi 0x5346 nhưng nhận được 0x$hex0$hex1")
        }

        val msgType = headerBuffer[2]
        if (!ChunkFrame.isValidType(msgType)) {
            val hexType = String.format("%02X", msgType)
            throw FrameCorruptedException("Loại thông điệp không hợp lệ: 0x$hexType")
        }

        val bb = ByteBuffer.wrap(headerBuffer, 3, 8).order(ByteOrder.BIG_ENDIAN)
        val streamId = bb.int
        val payloadLen = bb.int

        if (payloadLen < 0 || payloadLen > ChunkFrame.MAX_PAYLOAD_SIZE) {
            throw FrameCorruptedException(
                "Độ dài payload không hợp lệ: $payloadLen bytes (cho phép trong khoảng 0..${ChunkFrame.MAX_PAYLOAD_SIZE})"
            )
        }

        val payload = ByteArray(payloadLen)
        if (payloadLen > 0) {
            val payloadRead = readFully(inputStream, payload, 0, payloadLen, allowPartialAtStart = false)
            if (payloadRead < payloadLen) {
                throw FrameCorruptedException(
                    "Luồng kết nối bị ngắt khi chưa nhận đủ payload (đã đọc $payloadRead / $payloadLen bytes)"
                )
            }
        }

        return ChunkFrame(
            msgType = msgType,
            streamId = streamId,
            payload = payload
        )
    }

    /**
     * Đọc chính xác length bytes vào target bắt đầu từ offset, xử lý trường hợp luồng bị phân mảnh mạng.
     */
    private fun readFully(
        inputStream: InputStream,
        target: ByteArray,
        offset: Int,
        length: Int,
        allowPartialAtStart: Boolean
    ): Int {
        var totalRead = 0
        while (totalRead < length) {
            val read = inputStream.read(target, offset + totalRead, length - totalRead)
            if (read == -1) {
                if (totalRead == 0 && allowPartialAtStart) {
                    return 0
                }
                return totalRead
            }
            totalRead += read
        }
        return totalRead
    }
}

