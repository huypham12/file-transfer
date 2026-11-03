package com.example.file_tranfer.domain.model

/**
 * Chính sách chia sẻ tài liệu an toàn (Zero-Trust Share Policy).
 * Thiết lập các ràng buộc về thời gian hết hạn (TTL), số lần tải tối đa và mật mã bảo vệ.
 */
data class SharePolicy(
    val policyId: String,
    val policyName: String = "Default Policy",
    val expiresAt: Long = 0L, // Unix Epoch ms; 0 nghĩa là không giới hạn thời gian
    val maxDownloads: Int = 1, // Số lượt tải tối đa; 0 nghĩa là không giới hạn
    val downloadCount: Int = 0,
    val passcodeHash: String? = null,
    val passcodeSalt: String? = null
) {
    /**
     * Kiểm tra phiên chia sẻ đã hết hạn hiệu lực hay chưa.
     */
    fun isExpired(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return expiresAt > 0L && currentTimeMillis >= expiresAt
    }

    /**
     * Kiểm tra phiên đã đạt hoặc vượt quá số lượt tải cho phép hay chưa.
     */
    fun isDownloadLimitReached(): Boolean {
        return maxDownloads > 0 && downloadCount >= maxDownloads
    }

    /**
     * Kiểm tra phiên có được bảo vệ bằng mật mã truy cập hay không.
     */
    fun hasPasscode(): Boolean = !passcodeHash.isNullOrBlank()
}

