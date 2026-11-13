package com.filetransfer.app.domain.usecase.transfer

import com.filetransfer.app.core.constant.AppConstants
import com.filetransfer.app.core.platform.device.IDeviceIdentityManager
import com.filetransfer.app.core.platform.network.NetworkConfig
import com.filetransfer.app.core.security.crypto.Sha256StreamCalculator
import com.filetransfer.app.domain.model.ApprovalMode
import com.filetransfer.app.domain.model.AuditEventType
import com.filetransfer.app.domain.model.AuditLog
import com.filetransfer.app.domain.model.AuditSeverity
import com.filetransfer.app.domain.model.ManifestItem
import com.filetransfer.app.domain.model.SessionQrPayload
import com.filetransfer.app.domain.model.SessionStatus
import com.filetransfer.app.domain.model.TransferChannel
import com.filetransfer.app.domain.model.TransferDirection
import com.filetransfer.app.domain.model.TransferSession
import com.filetransfer.app.domain.model.TransferType
import com.filetransfer.app.domain.repository.IAuditRepository
import com.filetransfer.app.domain.repository.ITransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * Kết quả khởi tạo phiên phát 1-N.
 */
data class CreateSenderSessionResult(
    val session: TransferSession,
    val qrPayload: SessionQrPayload
)

/**
 * FR-SND-03: Khởi tạo phiên chia sẻ & Sinh Dynamic QR Payload.
 * Tuân thủ SDD Mục 8.1 và Bảng 12.1.
 */
class CreateSenderSessionUseCase(
    private val transferRepository: ITransferRepository,
    private val deviceIdentityManager: IDeviceIdentityManager,
    private val auditRepository: IAuditRepository? = null
) {
    private val secureRandom = SecureRandom()

    /**
     * Khởi tạo phiên chia sẻ cho danh sách tệp tin.
     * Chạy toàn bộ I/O trên Dispatchers.IO để chống chặn Main Thread.
     */
    suspend fun execute(
        files: List<File>,
        senderIp: String,
        senderPort: Int = NetworkConfig.DEFAULT_LAN_PORT,
        approvalMode: ApprovalMode = ApprovalMode.AUTO_ACCEPT,
        channel: TransferChannel = TransferChannel.DIRECT_LAN,
        relayUrl: String = "",
        streamId: String = "",
        ttlSeconds: Long = com.filetransfer.app.core.constant.AppConstants.Timeout.QR_TTL_SECONDS
    ): Result<CreateSenderSessionResult> = withContext(Dispatchers.IO) {
        if (files.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Danh sách tệp tin chia sẻ không được rỗng"))
        }

        try {
            val sessionId = UUID.randomUUID().toString()
            val sessionToken = generateSecureToken()

            val transferType = if (files.size > 1) TransferType.BATCH_FILES else TransferType.SINGLE_FILE

            var totalBytes = 0L
            val manifestArray = JSONArray()

            for (file in files) {
                val size = file.length()
                totalBytes += size
                val sha = Sha256StreamCalculator.calculateFileSha256(file)
                val itemJson = JSONObject().apply {
                    put("relativePath", file.name)
                    put("fileSize", size)
                    put("mimeType", "application/octet-stream")
                    put("sha256", sha)
                }
                manifestArray.put(itemJson)
            }

            val deviceProfile = deviceIdentityManager.getDeviceProfile()
            val expiresAt = System.currentTimeMillis() + (ttlSeconds * 1000L)
            val primaryFileName = if (files.isNotEmpty()) files.first().name else ""

            val qrPayload = SessionQrPayload(
                sessionId = sessionId,
                sessionToken = sessionToken,
                senderIp = senderIp,
                senderPort = senderPort,
                channel = channel,
                transferType = transferType,
                totalBytes = totalBytes,
                itemCount = files.size,
                senderDeviceModel = deviceProfile.model,
                relayUrl = relayUrl,
                streamId = streamId,
                fileName = primaryFileName,
                expiresAt = expiresAt
            )

            val session = TransferSession(
                sessionId = sessionId,
                sessionToken = sessionToken,
                direction = TransferDirection.SENDER,
                channel = channel,
                peerDeviceModel = "",
                peerFingerprint = "",
                approvalMode = approvalMode,
                transferType = transferType,
                manifestJson = manifestArray.toString(),
                totalBytes = totalBytes,
                confirmedOffset = 0L,
                tempFilePath = "",
                expectedSha256 = "",
                status = SessionStatus.PENDING,
                startedAt = System.currentTimeMillis()
            )

            transferRepository.createSession(session)

            auditRepository?.logEvent(
                AuditLog(
                    sessionId = sessionId,
                    actorId = deviceProfile.model,
                    eventType = AuditEventType.SESSION_CREATED,
                    severity = AuditSeverity.INFO,
                    details = "Đã khởi tạo phiên chia sẻ 1-N (Token: $sessionToken, Tổng dung lượng: $totalBytes bytes, Chế độ: ${approvalMode.name})"
                )
            )

            Result.success(CreateSenderSessionResult(session, qrPayload))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(AppConstants.Crypto.SESSION_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

