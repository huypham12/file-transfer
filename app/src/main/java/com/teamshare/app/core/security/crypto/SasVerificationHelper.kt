package com.teamshare.app.core.security.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/**
 * Tiện ích tính toán Chuỗi Xác thực Ngắn (Short Authentication String - SAS).
 * Tuân thủ đặc tả SDD mục 9.3:
 * Triệt tiêu hoàn toàn tấn công Man-In-The-Middle (MITM) trên kênh truyền chuyển tiếp Relay.
 */
object SasVerificationHelper {

    private const val SAS_MODULO: Int = 1_000_000

    /**
     * Sinh mã SAS 6 chữ số từ 2 khóa công khai tạm thời và nonce của phiên truyền.
     * Sắp xếp từ điển (canonical lexicographical order) giữa pubKeyA và pubKeyB để đảm bảo
     * tính đối xứng: generateSasCode(A, B, nonce) == generateSasCode(B, A, nonce).
     *
     * Công thức:
     * SAS = | Int32(SHA-256(min(pubKeyA, pubKeyB) || max(pubKeyA, pubKeyB) || nonce)) | mod 1,000,000
     * Định dạng chuỗi: "%06d"
     */
    fun generateSasCode(pubKeyA: ByteArray, pubKeyB: ByteArray, nonce: ByteArray): String {
        val (firstKey, secondKey) = if (compareByteArrays(pubKeyA, pubKeyB) <= 0) {
            pubKeyA to pubKeyB
        } else {
            pubKeyB to pubKeyA
        }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(firstKey)
        digest.update(secondKey)
        digest.update(nonce)
        val hash = digest.digest()

        val intVal = ByteBuffer.wrap(hash, 0, 4).int
        val sasNumber = (intVal and 0x7FFFFFFF) % SAS_MODULO // Tránh tràn số âm khi intVal == Int.MIN_VALUE

        return String.format(Locale.US, "%06d", sasNumber)
    }

    /**
     * So sánh 2 mảng byte theo thứ tự từ điển không dấu (unsigned lexicographical comparison).
     */
    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val byteA = a[i].toInt() and 0xFF
            val byteB = b[i].toInt() and 0xFF
            if (byteA != byteB) {
                return byteA.compareTo(byteB)
            }
        }
        return a.size.compareTo(b.size)
    }
}

