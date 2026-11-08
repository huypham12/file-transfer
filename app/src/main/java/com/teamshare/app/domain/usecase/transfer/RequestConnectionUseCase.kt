package com.teamshare.app.domain.usecase.transfer

import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.DeviceProfile
import com.teamshare.app.domain.model.SessionQrPayload
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITransferRepository
import java.io.File
import java.io.IOException

/**
 * FR-RCV-03: Gửi yêu cầu kết nối kèm danh tính phần cứng thiết bị.
 * Tuân thủ SDD Mục 7.4, 8.1 và Bảng 12.1.
 */
class RequestConnectionUseCase(
    private val transferRepository: ITransferRepository,
    private val checkReceiverStorageUseCase: CheckReceiverStorageUseCase,
    private val auditRepository: IAuditRepository? = null
) {
    /**
     * Thực hiện kết nối tới máy phát từ thông tin mã QR đã quét.
     *
     * @param payload Dữ liệu mã QR phiên phát
     * @param myProfile Hồ sơ định danh phần cứng máy nhận
     * @param targetDirectory Thư mục Sandbox tiếp nhận tệp
     * @param lanEngine Động cơ truyền tải mạng LAN
     * @return Result<File> Tệp tin hoặc thư mục đã nhận thành công
     */
    suspend fun execute(
        payload: SessionQrPayload,
        myProfile: DeviceProfile,
        targetDirectory: File,
        lanEngine: LanTransferEngine
    ): Result<File> {
        // 1. Kiểm tra dung lượng bộ nhớ đĩa trống (StatFs) trước khi kết nối (FR-RCV-02)
        val hasSpace = checkReceiverStorageUseCase(payload.totalBytes, targetDirectory)
        if (!hasSpace) {
            val err = "0x302: Thiết bị không đủ bộ nhớ trống để nhận ${payload.totalBytes} bytes (+ 50MB an toàn)"
            return Result.failure(IOException(err))
        }

        // 2. Khởi tạo phiên nhận cục bộ trong CSDL
        val session = TransferSession(
            sessionId = payload.sessionId,
            sessionToken = payload.sessionToken,
            direction = TransferDirection.RECEIVER,
            channel = payload.channel,
            peerDeviceModel = payload.senderDeviceModel,
            peerFingerprint = "",
            transferType = payload.transferType,
            totalBytes = payload.totalBytes,
            confirmedOffset = 0L,
            tempFilePath = "",
            expectedSha256 = "",
            status = SessionStatus.HANDSHAKING,
            startedAt = System.currentTimeMillis()
        )
        transferRepository.createSession(session)

        auditRepository?.logEvent(
            AuditLog(
                sessionId = payload.sessionId,
                actorId = myProfile.model,
                eventType = AuditEventType.DEVICE_CONNECTED,
                severity = AuditSeverity.INFO,
                details = "Đã gửi yêu cầu kết nối tới ${payload.senderIp}:${payload.senderPort} từ thiết bị ${myProfile.model}"
            )
        )

        // 3. Kết nối Socket TCP tới Sender qua LanTransferEngine
        val transferResult = lanEngine.connectReceiver(
            host = payload.senderIp,
            port = payload.senderPort,
            sessionToken = payload.sessionToken,
            sessionId = payload.sessionId,
            targetDirectory = targetDirectory,
            deviceModel = myProfile.model,
            deviceManufacturer = myProfile.manufacturer,
            deviceFingerprint = myProfile.fingerprint
        )

        if (transferResult.isSuccess) {
            transferRepository.updateSessionStatus(payload.sessionId, SessionStatus.COMPLETED)
            auditRepository?.logEvent(
                AuditLog(
                    sessionId = payload.sessionId,
                    actorId = myProfile.model,
                    eventType = AuditEventType.TRANSFER_COMPLETED,
                    severity = AuditSeverity.INFO,
                    details = "Truyền nhận thành công tệp/thư mục từ ${payload.senderDeviceModel}"
                )
            )
        } else {
            val exception = transferResult.exceptionOrNull()
            val isRejected = exception?.message?.contains("0x102") == true
            val status = if (isRejected) SessionStatus.CANCELLED else SessionStatus.FAILED
            transferRepository.updateSessionStatus(payload.sessionId, status, errorCode = exception?.message)

            auditRepository?.logEvent(
                AuditLog(
                    sessionId = payload.sessionId,
                    actorId = myProfile.model,
                    eventType = if (isRejected) AuditEventType.SESSION_REJECTED else AuditEventType.SECURITY_ALERT,
                    severity = if (isRejected) AuditSeverity.WARNING else AuditSeverity.SECURITY_ALERT,
                    details = "Yêu cầu kết nối không thành công: ${exception?.message}"
                )
            )
        }

        return transferResult
    }
}

