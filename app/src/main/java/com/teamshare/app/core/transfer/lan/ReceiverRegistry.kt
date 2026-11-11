package com.teamshare.app.core.transfer.lan

import com.teamshare.app.core.constant.AppConstants
import com.teamshare.app.domain.model.DeviceProfile
import com.teamshare.app.domain.model.ErrorCode
import kotlinx.coroutines.Job
import java.net.Socket

/**
 * Trạng thái của một slot thiết bị nhận trong phiên chia sẻ 1-N.
 * Tuân thủ đặc tả kiến trúc v2.2 (Mục 3).
 */
enum class ReceiverSlotStatus {
    /** Đang chờ người gửi phê duyệt (trong chế độ MANUAL approval) - Chiếm 1 slot */
    PENDING,
    /** Đang truyền tải dữ liệu tích cực - Chiếm 1 slot */
    ACTIVE,
    /** Đã mất kết nối socket nhưng giữ slot để cho phép resume - Chiếm 1 slot */
    DISCONNECTED,
    /** Đã hoàn thành 100% tệp tin và xác thực SHA-256 thành công - Giữ slot để tránh thiết bị lạ chen ngang */
    COMPLETED,
    /** Đã bị người gửi chủ động thu hồi quyền hoặc hủy - Slot được giải phóng */
    REVOKED,
    /** Gặp lỗi không thể phục hồi - Chiếm 1 slot cho đến khi người gửi thu hồi hoặc kết thúc phiên */
    FAILED
}

/**
 * Đại diện cho một slot của thiết bị nhận trong phiên chia sẻ.
 */
data class ReceiverSlot(
    val fingerprint: String,
    val deviceProfile: DeviceProfile,
    var status: ReceiverSlotStatus,
    var connectionGeneration: Long = 1L,
    var activeSocket: Socket? = null,
    var transferJob: Job? = null,
    val connectedAt: Long = System.currentTimeMillis(),
    var lastActivityAt: Long = System.currentTimeMillis()
) {
    val isTransferring: Boolean
        get() = status == ReceiverSlotStatus.ACTIVE

    val occupiesSlot: Boolean
        get() = status != ReceiverSlotStatus.REVOKED
}

/**
 * Kết quả yêu cầu cấp phát slot (Admission).
 */
sealed class AdmissionResult {
    data class Admitted(
        val slot: ReceiverSlot,
        val isReconnect: Boolean
    ) : AdmissionResult()

    data class SessionFull(
        val currentOccupied: Int,
        val maxCapacity: Int
    ) : AdmissionResult()

    data class Rejected(
        val reason: String,
        val errorCode: String
    ) : AdmissionResult()
}

/**
 * Bộ quản lý thành viên và slot phiên chia sẻ 1-N (ReceiverRegistry).
 * Đảm bảo:
 * 1. Giới hạn cứng tối đa MAX_SESSION_RECEIVERS (mặc định 5) thiết bị nhận duy nhất trong phiên.
 * 2. Slot reservation: Giữ slot cho PENDING, DISCONNECTED, COMPLETED.
 * 3. Atomic admission và chống race condition giữa các kết nối đồng thời.
 * 4. Xử lý Reconnect: Tăng generation, hủy kết nối/job cũ nếu còn sót.
 * 5. Cung cấp API thu hồi (revoke) chủ động từ người gửi để giải phóng slot.
 */
class ReceiverRegistry(
    maxCapacity: Int = AppConstants.FlowControl.MAX_SESSION_RECEIVERS
) {
    private val lock = Any()
    var maxCapacity: Int = maxCapacity
        private set
    private val slots = LinkedHashMap<String, ReceiverSlot>()

    /**
     * Yêu cầu tiếp nhận thiết bị nhận vào phiên chia sẻ.
     * Thực hiện nguyên tử (thread-safe):
     * - Nếu fingerprint đã tồn tại: Coi là Reconnect (tăng connectionGeneration, trả về Admitted).
     * - Nếu fingerprint mới: Kiểm tra số lượng slot đang chiếm dụng. Nếu >= maxCapacity -> SessionFull.
     *   Ngược lại -> Cấp phát slot mới với trạng thái initialStatus.
     */
    fun requestAdmission(
        profile: DeviceProfile,
        initialStatus: ReceiverSlotStatus = ReceiverSlotStatus.ACTIVE
    ): AdmissionResult = synchronized(lock) {
        val existingSlot = slots[profile.fingerprint]
        if (existingSlot != null) {
            if (existingSlot.status == ReceiverSlotStatus.REVOKED) {
                return@synchronized AdmissionResult.Rejected(
                    reason = "Thiết bị này đã bị người gửi thu hồi quyền truy cập trong phiên",
                    errorCode = ErrorCode.ERR_DEVICE_REJECTED
                )
            }

            // Tái kết nối hợp lệ
            existingSlot.connectionGeneration++
            existingSlot.lastActivityAt = System.currentTimeMillis()
            // Đóng socket cũ nếu chưa kịp giải phóng
            try {
                existingSlot.activeSocket?.close()
            } catch (_: Exception) {}
            existingSlot.activeSocket = null
            // Hủy Job cũ nếu còn chạy
            existingSlot.transferJob?.cancel()
            existingSlot.transferJob = null

            return@synchronized AdmissionResult.Admitted(
                slot = existingSlot,
                isReconnect = true
            )
        }

        // Kiểm tra số slot đang chiếm dụng
        val occupiedCount = slots.values.count { it.occupiesSlot }
        if (occupiedCount >= maxCapacity) {
            return@synchronized AdmissionResult.SessionFull(
                currentOccupied = occupiedCount,
                maxCapacity = maxCapacity
            )
        }

        val newSlot = ReceiverSlot(
            fingerprint = profile.fingerprint,
            deviceProfile = profile,
            status = initialStatus,
            connectionGeneration = 1L,
            lastActivityAt = System.currentTimeMillis()
        )
        slots[profile.fingerprint] = newSlot

        AdmissionResult.Admitted(
            slot = newSlot,
            isReconnect = false
        )
    }

    /**
     * Thu hồi quyền truy cập của một thiết bị theo fingerprint.
     * Chuyển trạng thái sang REVOKED, đóng socket, hủy job truyền tải và xóa khỏi danh sách slot
     * để giải phóng dung lượng cho thiết bị mới.
     * @return ReceiverSlot vừa bị thu hồi, hoặc null nếu không tìm thấy.
     */
    fun revoke(fingerprint: String): ReceiverSlot? = synchronized(lock) {
        val slot = slots.remove(fingerprint) ?: return@synchronized null
        slot.status = ReceiverSlotStatus.REVOKED
        try {
            slot.activeSocket?.close()
        } catch (_: Exception) {}
        slot.activeSocket = null
        slot.transferJob?.cancel()
        slot.transferJob = null
        slot
    }

    /**
     * Cập nhật trạng thái slot thành ACTIVE khi bắt đầu truyền dữ liệu.
     */
    fun markActive(
        fingerprint: String,
        socket: Socket?,
        job: Job?,
        generation: Long
    ): Boolean = synchronized(lock) {
        val slot = slots[fingerprint] ?: return@synchronized false
        // Chỉ chấp nhận cập nhật nếu generation khớp hoặc mới hơn
        if (generation < slot.connectionGeneration) {
            return@synchronized false
        }
        slot.status = ReceiverSlotStatus.ACTIVE
        slot.activeSocket = socket
        slot.transferJob = job
        slot.lastActivityAt = System.currentTimeMillis()
        true
    }

    /**
     * Đánh dấu thiết bị đã ngắt kết nối (DISCONNECTED).
     * Slot vẫn được giữ để cho phép Reconnect / Resume.
     */
    fun markDisconnected(fingerprint: String, generation: Long? = null): Unit = synchronized(lock) {
        val slot = slots[fingerprint] ?: return@synchronized
        if (generation != null && generation < slot.connectionGeneration) {
            return@synchronized
        }
        if (slot.status != ReceiverSlotStatus.COMPLETED && slot.status != ReceiverSlotStatus.REVOKED) {
            slot.status = ReceiverSlotStatus.DISCONNECTED
        }
        try {
            slot.activeSocket?.close()
        } catch (_: Exception) {}
        slot.activeSocket = null
        slot.transferJob = null
        slot.lastActivityAt = System.currentTimeMillis()
    }

    /**
     * Đánh dấu thiết bị đã nhận xong tệp và xác thực thành công (COMPLETED).
     */
    fun markCompleted(fingerprint: String): Unit = synchronized(lock) {
        val slot = slots[fingerprint] ?: return@synchronized
        slot.status = ReceiverSlotStatus.COMPLETED
        try {
            slot.activeSocket?.close()
        } catch (_: Exception) {}
        slot.activeSocket = null
        slot.transferJob = null
        slot.lastActivityAt = System.currentTimeMillis()
    }

    /**
     * Đánh dấu thiết bị thất bại không thể phục hồi (FAILED).
     */
    fun markFailed(fingerprint: String): Unit = synchronized(lock) {
        val slot = slots[fingerprint] ?: return@synchronized
        slot.status = ReceiverSlotStatus.FAILED
        try {
            slot.activeSocket?.close()
        } catch (_: Exception) {}
        slot.activeSocket = null
        slot.transferJob = null
        slot.lastActivityAt = System.currentTimeMillis()
    }

    /**
     * Cập nhật thời điểm hoạt động cuối cùng (heartbeat/keepalive/chunk progress).
     */
    fun updateLastActivity(fingerprint: String): Unit = synchronized(lock) {
        slots[fingerprint]?.let {
            it.lastActivityAt = System.currentTimeMillis()
        }
    }

    fun getSlot(fingerprint: String): ReceiverSlot? = synchronized(lock) {
        slots[fingerprint]?.copy()
    }

    fun getAllSlots(): List<ReceiverSlot> = synchronized(lock) {
        slots.values.map { it.copy() }
    }

    fun getOccupiedCount(): Int = synchronized(lock) {
        slots.values.count { it.occupiesSlot }
    }

    fun getActiveCount(): Int = synchronized(lock) {
        slots.values.count { it.status == ReceiverSlotStatus.ACTIVE }
    }

    /**
     * Kiểm tra phiên có bị bất động (inactivity) quá thời gian timeout hay không.
     * Phiên được coi là inactive nếu không có kết nối ACTIVE nào và thời gian trôi qua kể từ hoạt động
     * cuối cùng của tất cả các slot (hoặc từ khi mở phiên) vượt quá timeoutMs.
     */
    fun isSessionInactive(timeoutMs: Long): Boolean = synchronized(lock) {
        if (slots.isEmpty()) return@synchronized false
        val hasActive = slots.values.any { it.status == ReceiverSlotStatus.ACTIVE || it.status == ReceiverSlotStatus.PENDING }
        if (hasActive) return@synchronized false

        val maxLastActivity = slots.values.maxOfOrNull { it.lastActivityAt } ?: 0L
        return@synchronized (System.currentTimeMillis() - maxLastActivity) >= timeoutMs
    }

    /**
     * Đóng, dọn dẹp toàn bộ phiên và tùy chọn điều chỉnh lại sức chứa tối đa cho phiên tiếp theo.
     */
    fun reset(newCapacity: Int = maxCapacity): Unit = synchronized(lock) {
        this.maxCapacity = newCapacity
        for (slot in slots.values) {
            try {
                slot.activeSocket?.close()
            } catch (_: Exception) {}
            slot.transferJob?.cancel()
        }
        slots.clear()
    }

    /**
     * Đóng và dọn dẹp toàn bộ phiên (giữ nguyên maxCapacity).
     */
    fun clearAll(): Unit = reset(maxCapacity)
}
