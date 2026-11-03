package com.example.file_tranfer.domain.model

/**
 * Thông tin mô tả và trạng thái truyền tải của một tệp tin.
 */
data class TransferFile(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String = "application/octet-stream",
    val expectedSha256: String,
    val bytesTransferred: Long = 0L,
    val status: FileTransferStatus = FileTransferStatus.PENDING
) {
    val isCompleted: Boolean
        get() = status == FileTransferStatus.COMPLETED

    val progress: Float
        get() = when {
            fileSize > 0L -> (bytesTransferred.toFloat() / fileSize).coerceIn(0f, 1f)
            isCompleted -> 1.0f
            else -> 0.0f
        }
}

