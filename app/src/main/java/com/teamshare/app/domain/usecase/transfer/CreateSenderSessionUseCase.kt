package com.teamshare.app.domain.usecase.transfer

import com.teamshare.app.core.platform.device.IDeviceIdentityManager
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.transfer.zip.ZipStreamPacker
import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.ManifestItem
import com.teamshare.app.domain.model.SessionQrPayload
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.model.TransferType
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITransferRepository
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
     * Khởi tạo phiên chia sẻ cho danh sách tệp tin hoặc thư mục.
     */
    suspend fun execute(
        files: List<File>,
        senderIp: String,
        senderPort: Int = 8889,
        approvalMode: ApprovalMode = ApprovalMode.AUTO_ACCEPT,
        channel: TransferChannel = TransferChannel.DIRECT_LAN,
        relayUrl: String = "",
        streamId: String = ""
    ): Result<CreateSenderSessionResult> {
        if (files.isEmpty()) {
            return Result.failure(IllegalArgumentException("Danh sách tệp tin chia sẻ không được rỗng"))
        }

        return try {
            val sessionId = UUID.randomUUID().toString()
            val sessionToken = generateSecureToken()

            val isSingleFolder = files.size == 1 && files[0].isDirectory
            val transferType = when {
                isSingleFolder -> TransferType.FOLDER_ZIP
                files.size > 1 -> TransferType.BATCH_FILES
                else -> TransferType.SINGLE_FILE
            }

            var totalBytes = 0L
            val manifestArray = JSONArray()

            if (isSingleFolder) {
                val folder = files[0]
                val zipMeta = ZipStreamPacker.calculateZipMetadata(folder)
                totalBytes = zipMeta.totalCompressedBytes

                val itemJson = JSONObject().apply {
                    put("relativePath", folder.name)
                    put("fileSize", totalBytes)
                    put("mimeType", "application/zip")
                    put("sha256", zipMeta.sha256)
                }
                manifestArray.put(itemJson)
            } else {
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
            }

            val deviceProfile = deviceIdentityManager.getDeviceProfile()

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
                streamId = streamId
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
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

