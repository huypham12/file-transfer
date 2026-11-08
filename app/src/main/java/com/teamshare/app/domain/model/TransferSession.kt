package com.teamshare.app.domain.model

/**
 * Quản lý trạng thái và tiến trình của một phiên truyền tải an toàn 1-N Drop Session (SDD Chương 4 & 5).
 *
 * @property sessionId Mã định danh phiên độc nhất (UUID)
 * @property sessionToken Mã bí mật phiên dùng trong QR code để xác thực kết nối ngoài băng
 * @property direction Hướng truyền tải (SENDER: Máy phát, RECEIVER: Máy nhận)
 * @property channel Kênh mạng truyền tải (DIRECT_LAN hoặc RELAY_E2EE)
 * @property peerDeviceModel Model phần cứng của thiết bị đối tác (vd: Build.MODEL như Redmi Turbo 4 Pro)
 * @property peerFingerprint Dấu vân tay định danh SHA-256 của thiết bị đối tác
 * @property approvalMode Chế độ phê duyệt phiên (MANUAL_APPROVAL hoặc AUTO_ACCEPT)
 * @property transferType Phân loại nội dung truyền tải (SINGLE_FILE, BATCH_FILES, FOLDER_ZIP)
 * @property manifestJson Danh mục tệp tin chi tiết định dạng JSON
 * @property totalBytes Tổng dung lượng toàn bộ tệp cần truyền tải (bytes)
 * @property confirmedOffset Tọa độ byte lớn nhất đã được ghi đĩa và xác nhận qua CHUNK_ACK (Checkpoint)
 * @property tempFilePath Đường dẫn tệp tạm (.part) trên bộ nhớ trong Sandbox
 * @property expectedSha256 Mã băm SHA-256 kỳ vọng của toàn bộ luồng truyền tải
 * @property status Trạng thái hiện tại của phiên truyền tải
 * @property errorCode Mã lỗi hệ thống nếu phiên gặp sự cố (0x101 - 0x401)
 * @property startedAt Dấu thời gian bắt đầu phiên (epoch millis)
 * @property completedAt Dấu thời gian kết thúc phiên thành công hoặc thất bại (epoch millis)
 */
data class TransferSession(
    val sessionId: String,
    val sessionToken: String = "",
    val direction: TransferDirection,
    val channel: TransferChannel = TransferChannel.DIRECT_LAN,
    val peerDeviceModel: String = "",
    val peerFingerprint: String = "",
    val approvalMode: ApprovalMode = ApprovalMode.MANUAL_APPROVAL,
    val transferType: TransferType = TransferType.SINGLE_FILE,
    val manifestJson: String = "[]",
    val totalBytes: Long,
    val confirmedOffset: Long = 0L,
    val tempFilePath: String = "",
    val expectedSha256: String = "",
    val status: SessionStatus = SessionStatus.PENDING,
    val errorCode: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,

    // Deprecated fields with defaults for backward compatibility
    @Deprecated("Legacy docId from Team architecture")
    val docId: String = "",
    @Deprecated("Legacy peerMemberId, use peerDeviceModel / peerFingerprint")
    val peerMemberId: String = "",
    @Deprecated("Use confirmedOffset as source of truth")
    val bytesTransferred: Long = confirmedOffset
) {
    val progressPercent: Float
        get() = when {
            isCompleted -> 1.0f
            totalBytes > 0L -> (confirmedOffset.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> 0.0f
        }

    val isCompleted: Boolean get() = status == SessionStatus.COMPLETED
    val canResume: Boolean get() = status == SessionStatus.PAUSED || status == SessionStatus.FAILED
    val canRetry: Boolean get() = status == SessionStatus.FAILED
}
