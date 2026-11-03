package com.teamshare.app.core.security.crypto

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Công cụ tính toán mã băm SHA-256 theo luồng (Streaming Hash Calculator).
 * Thiết kế bảo đảm:
 * 1. Độ phức tạp bộ nhớ là O(1) RAM: Không nạp toàn bộ tệp vào bộ nhớ, an toàn cho tệp dung lượng hàng GB.
 * 2. Tự động đóng dòng dữ liệu (I/O Stream) an toàn bằng khối [use].
 * 3. Chuỗi băm đầu ra được định dạng chuẩn 64 ký tự Hex chữ thường.
 */
object Sha256StreamCalculator {

    const val DEFAULT_BUFFER_SIZE_BYTES: Int = 64 * 1024 // 64 KB
    private const val SHA256_ALGORITHM = "SHA-256"

    /**
     * Băm dữ liệu từ [InputStream] theo từng khối nhỏ.
     */
    fun calculateStreamSha256(
        inputStream: InputStream,
        bufferSize: Int = DEFAULT_BUFFER_SIZE_BYTES
    ): String {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        val buffer = ByteArray(bufferSize)

        inputStream.use { stream ->
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                if (bytesRead > 0) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
        }

        return bytesToHex(digest.digest())
    }

    /**
     * Băm tệp tin vật lý trên đĩa [File].
     */
    fun calculateFileSha256(
        file: File,
        bufferSize: Int = DEFAULT_BUFFER_SIZE_BYTES
    ): String {
        require(file.exists() && file.isFile) { "Tệp tin không tồn tại hoặc không hợp lệ: ${file.absolutePath}" }
        return calculateStreamSha256(FileInputStream(file), bufferSize)
    }

    /**
     * Băm mảng byte trực tiếp (dùng cho metadata, header, payload nhỏ).
     */
    fun calculateBytesSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        return bytesToHex(digest.digest(data))
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = Character.forDigit(v ushr 4, 16)
            hexChars[i * 2 + 1] = Character.forDigit(v and 0x0F, 16)
        }
        return String(hexChars)
    }
}

