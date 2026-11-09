package com.teamshare.app.domain.usecase.transfer

import com.teamshare.app.core.constant.AppConstants
import com.teamshare.app.core.platform.network.NetworkConfig
import com.teamshare.app.core.transfer.lan.TransferProgressCallback
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.core.transfer.zip.SafeStreamExtractor
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.DeviceProfile
import com.teamshare.app.domain.model.DomainError
import com.teamshare.app.domain.model.ErrorCode
import com.teamshare.app.domain.model.SessionQrPayload
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.model.TransferType
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITransferRepository
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * FR-RCV-03: Gửi yêu cầu kết nối kèm danh tính phần cứng thiết bị.
 * Hỗ trợ chế độ kênh đôi: Ưu tiên LAN trực tiếp, tự động chuyển tiếp dự phòng (Fallback) sang Server Go Relay.
 * Tuân thủ SDD Mục 7.4, 8.1, 8.3, 10.5.
 */
class RequestConnectionUseCase(
    private val transferRepository: ITransferRepository,
    private val checkReceiverStorageUseCase: CheckReceiverStorageUseCase,
    private val auditRepository: IAuditRepository? = null,
    private val relayEngine: RelayClientEngine? = null
) {
    /**
     * Thực hiện kết nối tới máy phát từ thông tin mã QR đã quét.
     * Tự động dò kênh và tự động fallback sang Relay nếu kết nối LAN bị nghẽn (NAT/chặn cổng).
     *
     * @param payload Dữ liệu mã QR phiên phát
     * @param myProfile Hồ sơ định danh phần cứng máy nhận
     * @param targetDirectory Thư mục Sandbox tiếp nhận tệp
     * @param lanEngine Động cơ truyền tải mạng LAN
     * @param relayEngineOverride Động cơ Relay ghi đè (tùy chọn)
     * @return Result<File> Tệp tin hoặc thư mục đã nhận thành công
     */
    suspend fun execute(
        payload: SessionQrPayload,
        myProfile: DeviceProfile,
        targetDirectory: File,
        lanEngine: LanTransferEngine,
        relayEngineOverride: RelayClientEngine? = null,
        callback: TransferProgressCallback? = null
    ): Result<File> {
        val effectiveRelay = relayEngineOverride ?: relayEngine

        // 1. Kiểm tra dung lượng bộ nhớ đĩa trống (StatFs) trước khi kết nối (FR-RCV-02)
        val hasSpace = checkReceiverStorageUseCase(payload.totalBytes, targetDirectory)
        if (!hasSpace) {
            val err = "${ErrorCode.ERR_INSUFFICIENT_STORAGE}: Thiết bị không đủ bộ nhớ trống để nhận ${payload.totalBytes} bytes (+ ${AppConstants.FlowControl.DISK_SAFETY_MARGIN_BYTES / (1024 * 1024)}MB an toàn)"
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

        // 3. Nếu mã QR chỉ định kênh ZERO_KNOWLEDGE_RELAY, kết nối thẳng qua Relay
        if (payload.channel == TransferChannel.ZERO_KNOWLEDGE_RELAY && effectiveRelay != null) {
            return receiveViaRelay(payload, session, myProfile, targetDirectory, effectiveRelay, callback)
        }

        // 4. Nếu mã QR là DIRECT_LAN, thử kết nối LAN Socket TCP (thời gian chờ nhanh LAN_FAST_PROBE_TIMEOUT_MS)
        val transferResult = lanEngine.connectReceiver(
            host = payload.senderIp,
            port = payload.senderPort,
            sessionToken = payload.sessionToken,
            sessionId = payload.sessionId,
            targetDirectory = targetDirectory,
            deviceModel = myProfile.model,
            deviceManufacturer = myProfile.manufacturer,
            deviceFingerprint = myProfile.fingerprint,
            timeoutMs = NetworkConfig.LAN_FAST_PROBE_TIMEOUT_MS,
            callback = callback
        )

        if (transferResult.isSuccess) {
            transferRepository.updateSessionStatus(payload.sessionId, SessionStatus.COMPLETED)
            auditRepository?.logEvent(
                AuditLog(
                    sessionId = payload.sessionId,
                    actorId = myProfile.model,
                    eventType = AuditEventType.TRANSFER_COMPLETED,
                    severity = AuditSeverity.INFO,
                    details = "Truyền nhận thành công tệp/thư mục qua mạng LAN từ ${payload.senderDeviceModel}"
                )
            )
            return transferResult
        }

        // 5. Kết nối LAN không thành công -> Kiểm tra xem có phải do mạng không thể tiếp cận (ETIMEDOUT, ECONNREFUSED)
        val exception = transferResult.exceptionOrNull()
        val rawError = exception?.message ?: "Lỗi kết nối không xác định"
        val isNetworkUnreachable = rawError.contains("ETIMEDOUT", ignoreCase = true) ||
                rawError.contains("timed out", ignoreCase = true) ||
                rawError.contains("ECONNREFUSED", ignoreCase = true) ||
                rawError.contains("Connection refused", ignoreCase = true) ||
                rawError.contains("No route to host", ignoreCase = true) ||
                rawError.contains("CONNECT_ERROR", ignoreCase = true)

        // TỰ ĐỘNG CHUYỂN TIẾP DỰ PHÒNG (TRANSPARENT FAILOVER SANG RELAY SERVER)
        if (isNetworkUnreachable && effectiveRelay != null) {
            auditRepository?.logEvent(
                AuditLog(
                    sessionId = payload.sessionId,
                    actorId = myProfile.model,
                    eventType = AuditEventType.DEVICE_CONNECTED,
                    severity = AuditSeverity.WARNING,
                    details = "Kết nối LAN tới ${payload.senderIp}:${payload.senderPort} không phản hồi. Tự động chuyển tiếp qua Zero-Knowledge Relay Server..."
                )
            )
            return receiveViaRelay(payload, session, myProfile, targetDirectory, effectiveRelay, callback)
        }

        // Xử lý các lỗi khác không thể fallback (vd: người gửi bấm từ chối 0x102)
        val isRejected = exception?.message?.contains(ErrorCode.ERR_DEVICE_REJECTED) == true || exception?.message?.contains("0x102") == true
        val isSecurityAlert = rawError.contains("checksum", ignoreCase = true) || rawError.contains("credential", ignoreCase = true)
        val status = if (isRejected) SessionStatus.CANCELLED else SessionStatus.FAILED
        transferRepository.updateSessionStatus(payload.sessionId, status, errorCode = exception?.message)

        val friendlyDetails = when {
            isRejected -> "Người gửi đã từ chối yêu cầu kết nối thiết bị"
            else -> "Yêu cầu kết nối không thành công: $rawError"
        }

        val eventType = when {
            isRejected -> AuditEventType.SESSION_REJECTED
            isSecurityAlert -> AuditEventType.SECURITY_ALERT
            else -> AuditEventType.TRANSFER_CANCELLED
        }

        val severity = when {
            isSecurityAlert -> AuditSeverity.SECURITY_ALERT
            isRejected -> AuditSeverity.WARNING
            else -> AuditSeverity.WARNING
        }

        auditRepository?.logEvent(
            AuditLog(
                sessionId = payload.sessionId,
                actorId = myProfile.model,
                eventType = eventType,
                severity = severity,
                details = friendlyDetails
            )
        )
        return Result.failure(IOException(friendlyDetails, exception))
    }

    /**
     * Kéo luồng tệp tin an toàn từ Zero-Knowledge Go Relay Server.
     */
    private suspend fun receiveViaRelay(
        payload: SessionQrPayload,
        session: TransferSession,
        myProfile: DeviceProfile,
        targetDirectory: File,
        relayEngine: RelayClientEngine,
        callback: TransferProgressCallback? = null
    ): Result<File> {
        val targetRelayUrl = NetworkConfig.resolveRelayUrlForReceiver(payload.relayUrl)

        auditRepository?.logEvent(
            AuditLog(
                sessionId = payload.sessionId,
                actorId = myProfile.model,
                eventType = AuditEventType.DEVICE_CONNECTED,
                severity = AuditSeverity.INFO,
                details = "Đang kết nối nhận tệp qua Zero-Knowledge Relay Server ($targetRelayUrl)..."
            )
        )

        val isZip = payload.transferType == TransferType.FOLDER_ZIP
        val targetFileName = when (payload.transferType) {
            TransferType.FOLDER_ZIP -> "folder_${payload.sessionId.take(8)}.zip"
            TransferType.BATCH_FILES -> "batch_${payload.sessionId.take(8)}.zip"
            else -> "file_${payload.sessionId.take(8)}"
        }
        val targetFile = File(targetDirectory, targetFileName)

        val relaySession = session.copy(
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY
        )

        return try {
            val success = relayEngine.receiveFileViaRelayWithRetry(
                relayWsUrl = targetRelayUrl,
                session = relaySession,
                targetFile = targetFile,
                expectedSha256 = "",
                callback = callback
            )

            if (success && targetFile.exists()) {
                // Nếu là tệp Zip nén thư mục, tự động giải nén an toàn
                val finalFile = if (isZip) {
                    val extractDir = File(targetDirectory, "folder_${payload.sessionId.take(8)}")
                    try {
                        SafeStreamExtractor.extractZipFile(
                            zipFile = targetFile,
                            destinationDir = extractDir
                        )
                        targetFile.delete()
                        extractDir
                    } catch (_: Exception) {
                        targetFile
                    }
                } else {
                    targetFile
                }

                transferRepository.updateSessionStatus(payload.sessionId, SessionStatus.COMPLETED)
                auditRepository?.logEvent(
                    AuditLog(
                        sessionId = payload.sessionId,
                        actorId = myProfile.model,
                        eventType = AuditEventType.TRANSFER_COMPLETED,
                        severity = AuditSeverity.INFO,
                        details = "Truyền nhận thành công tệp/thư mục từ ${payload.senderDeviceModel} qua Zero-Knowledge Relay Server"
                    )
                )
                Result.success(finalFile)
            } else {
                transferRepository.updateSessionStatus(payload.sessionId, SessionStatus.FAILED, errorCode = "RELAY_RECEIVE_FAILED")
                Result.failure(IOException("Không thể nhận tệp qua Relay Server (kết nối thất bại hoặc tệp lỗi)"))
            }
        } catch (e: Exception) {
            transferRepository.updateSessionStatus(payload.sessionId, SessionStatus.FAILED, errorCode = e.message)
            Result.failure(e)
        }
    }
}
