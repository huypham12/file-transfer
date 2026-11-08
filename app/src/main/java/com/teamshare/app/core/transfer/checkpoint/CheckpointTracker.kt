package com.teamshare.app.core.transfer.checkpoint

import com.teamshare.app.core.constant.AppConstants
import com.teamshare.app.data.local.db.dao.TransferSessionDao
import java.nio.channels.FileChannel

/**
 * Quản lý điểm kiểm tra an toàn (Checkpoint) theo chu kỳ 2MB (FLUSH_THRESHOLD).
 * Khi tích lũy đủ 2MB dữ liệu nhận được, CheckpointTracker sẽ:
 * 1. Gọi fileChannel.force(true) để đẩy toàn bộ dữ liệu từ OS page cache xuống phần cứng lưu trữ vật lý (fsync).
 * 2. Cập nhật vị trí confirmed_offset vào Room CSDL để bảo vệ tiến trình khi sự cố xảy ra.
 * 3. Trả về giá trị mốc checkpoint để gửi khung tin CHUNK_ACK (0x05) về máy gửi.
 */
class CheckpointTracker(
    val sessionId: String,
    private val sessionDao: TransferSessionDao? = null,
    private val onCheckpointPersisted: (suspend (Long) -> Unit)? = null,
    val flushThreshold: Long = FLUSH_THRESHOLD
) {
    companion object {
        const val FLUSH_THRESHOLD: Long = AppConstants.FlowControl.CHECKPOINT_THRESHOLD_RELAY
        const val LAN_FLUSH_THRESHOLD: Long = AppConstants.FlowControl.CHECKPOINT_THRESHOLD_LAN
    }

    private var currentOffset: Long = 0L
    private var lastConfirmedOffset: Long = 0L
    private var unconfirmedBytes: Long = 0L

    /**
     * Khởi tạo mốc offset ban đầu (dùng khi Resume phiên truyền từ điểm lưu trữ trước đó).
     */
    fun initOffset(initialOffset: Long) {
        require(initialOffset >= 0L) { "Initial offset không được âm: $initialOffset" }
        currentOffset = initialOffset
        lastConfirmedOffset = initialOffset
        unconfirmedBytes = 0L
    }

    /**
     * Ghi nhận một lượng byte vừa được ghi vào fileChannel.
     * Nếu tích lũy unconfirmedBytes vượt ngưỡng flushThreshold (2MB), thực hiện fsync đĩa và lưu Room DB.
     * Trả về confirmedOffset nếu đạt mốc checkpoint cần gửi ACK, hoặc null nếu chưa đủ ngưỡng.
     */
    suspend fun recordBytesWritten(fileChannel: FileChannel, bytesCount: Int): Long? {
        if (bytesCount <= 0) return null

        currentOffset += bytesCount
        unconfirmedBytes += bytesCount

        if (unconfirmedBytes >= flushThreshold) {
            return performFlush(fileChannel)
        }
        return null
    }

    /**
     * Ép ghi đĩa bắt buộc (force fsync) và cập nhật CSDL cho lượng dữ liệu còn sót lại ở cuối phiên truyền,
     * hoặc khi tạm dừng (PAUSE).
     */
    suspend fun flushFinal(fileChannel: FileChannel): Long {
        return performFlush(fileChannel)
    }

    private suspend fun performFlush(fileChannel: FileChannel): Long {
        // 1. fsync vật lý xuống SSD/NAND Flash
        fileChannel.force(true)

        // 2. Cập nhật cơ sở dữ liệu Room nếu có sessionDao
        sessionDao?.updateCheckpoint(sessionId, currentOffset)

        // 3. Gọi callback bổ sung nếu có
        onCheckpointPersisted?.invoke(currentOffset)

        lastConfirmedOffset = currentOffset
        unconfirmedBytes = 0L
        return lastConfirmedOffset
    }

    fun getCurrentOffset(): Long = currentOffset

    fun getLastConfirmedOffset(): Long = lastConfirmedOffset

    fun getUnconfirmedBytes(): Long = unconfirmedBytes
}

