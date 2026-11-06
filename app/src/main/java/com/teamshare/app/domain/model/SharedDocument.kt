package com.teamshare.app.domain.model

/**
 * Thực thể quản lý Tài liệu chia sẻ trong Không gian Nhóm.
 */
data class SharedDocument(
    val docId: String,
    val teamId: String,
    val publisherId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val expectedSha256: String,
    val requireBiometric: Boolean = false,
    val localUri: String? = null,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val createdAt: Long = System.currentTimeMillis(),
    val publisherHost: String? = null,
    val publisherPort: Int = 0
) {
    val isDownloaded: Boolean get() = downloadStatus == DownloadStatus.COMPLETED
    val isDownloading: Boolean get() = downloadStatus == DownloadStatus.DOWNLOADING
}

