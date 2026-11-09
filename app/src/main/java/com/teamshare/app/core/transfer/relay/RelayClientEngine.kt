package com.teamshare.app.core.transfer.relay

import com.teamshare.app.core.constant.AppConstants
import com.teamshare.app.core.network.relay.AttachStreamRequest
import com.teamshare.app.core.network.relay.AttachStreamResponse
import com.teamshare.app.core.network.relay.RelayWebSocketConnection
import com.teamshare.app.core.protocol.BinaryFrameParser
import com.teamshare.app.core.protocol.ChunkAckPayload
import com.teamshare.app.core.protocol.ChunkFrame
import com.teamshare.app.core.protocol.ControlSignalPayload
import com.teamshare.app.core.protocol.DataChunkPayload
import com.teamshare.app.core.protocol.ErrorAlertPayload
import com.teamshare.app.core.protocol.FrameSerializer
import com.teamshare.app.core.protocol.HandshakeAuthPayload
import com.teamshare.app.core.protocol.ManifestResponsePayload
import com.teamshare.app.core.protocol.TransferManifestPayload
import com.teamshare.app.core.protocol.VerifyResultPayload
import com.teamshare.app.core.security.crypto.CryptoEngine
import com.teamshare.app.core.security.crypto.E2EEncryptionManager
import com.teamshare.app.core.security.crypto.E2EKeyExchangePayload
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.sanitizer.FilenameSanitizer
import com.teamshare.app.core.storage.saf.SafeFileDeleter
import com.teamshare.app.core.transfer.buffer.BufferPoolManager
import com.teamshare.app.core.transfer.checkpoint.CheckpointTracker
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.core.transfer.lan.TransferProgressCallback
import com.teamshare.app.data.local.db.dao.TransferSessionDao
import com.teamshare.app.domain.model.DeviceProfile
import com.teamshare.app.domain.model.DomainError
import com.teamshare.app.domain.model.ErrorCode
import com.teamshare.app.domain.model.TeamMembershipCredential
import com.teamshare.app.domain.model.TransferSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Động cơ Truyền tải Chuyển tiếp Internet E2EE qua Zero-Knowledge Relay (RelayClientEngine).
 * Tuân thủ:
 * 1. SRS FR-TRF-03: Kênh truyền chuyển tiếp Internet Zero-Knowledge không lưu đĩa.
 * 2. SAD ADR-01 & ADR-05: WebSocket WSS chuyển tiếp khung tin nhị phân.
 * 3. SDD Mục 6.3 & 8.3: Trao đổi khóa Ephemeral ECDH, dẫn xuất HKDF, mã hóa khối AES-256-GCM.
 * 4. Tự động phục hồi đứt mạng với Exponential Backoff (1s, 2s, 4s).
 */
class RelayClientEngine(
    private val bufferPool: BufferPoolManager = BufferPoolManager(),
    private val sessionDao: TransferSessionDao? = null,
    private val checkpointThresholdBytes: Long = AppConstants.FlowControl.CHECKPOINT_THRESHOLD_RELAY,
    private val e2eManager: E2EEncryptionManager = E2EEncryptionManager(),
    private val okHttpClient: OkHttpClient = RelayWebSocketConnection.defaultClient
) {
    private val serializer = FrameSerializer()
    private val parser = BinaryFrameParser()

    private val isCancelled = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var activeConnection: RelayWebSocketConnection? = null
    private var activePartFile: File? = null

    @Volatile
    private var currentState: TransferEngineState = TransferEngineState.IDLE

    fun getState(): TransferEngineState = currentState

    fun pause() {
        isPaused.set(true)
        currentState = TransferEngineState.PAUSED
    }

    fun resume() {
        isPaused.set(false)
        currentState = TransferEngineState.TRANSFERRING
    }

    fun cancel() {
        isCancelled.set(true)
        currentState = TransferEngineState.CANCELLED
        activeConnection?.close(1000, "Người dùng đã hủy phiên")
        activePartFile?.let {
            try {
                SafeFileDeleter.wipeAndSanitizeFile(it)
            } catch (_: Exception) {}
        }
    }

    private fun serializeFrame(frame: ChunkFrame): ByteArray {
        val baos = ByteArrayOutputStream()
        serializer.serializeTo(frame, baos)
        return baos.toByteArray()
    }

    private fun parseFrame(bytes: ByteArray): ChunkFrame {
        val bais = ByteArrayInputStream(bytes)
        return parser.parseNextFrame(bais) ?: throw IOException("Khung tin nhị phân không hợp lệ hoặc bị cắt cụt")
    }

    private fun parseHexOrBytes(hexOrStr: String): ByteArray {
        return try {
            val clean = hexOrStr.trim()
            if (clean.length % 2 == 0 && clean.matches(Regex("^[0-9a-fA-F]+$"))) {
                ByteArray(clean.length / 2) { i ->
                    clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } else {
                clean.toByteArray(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            hexOrStr.toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * Gửi tệp tin qua Relay Server (Sender Flow) với cơ chế tự động thử lại khi mất kết nối.
     */
    suspend fun sendFileViaRelayWithRetry(
        relayWsUrl: String,
        session: TransferSession,
        fileChannel: FileChannel,
        expectedSha256: String,
        localCredential: TeamMembershipCredential? = null,
        ownerPublicKey: PublicKey? = null,
        callback: TransferProgressCallback? = null,
        peerValidator: (suspend (HandshakeAuthPayload) -> Boolean)? = null,
        onPeerConnected: ((DeviceProfile) -> Unit)? = null,
        ttlSeconds: Int = 0,
        maxRetries: Int = AppConstants.Retry.MAX_RELAY_RETRIES
    ): Boolean {
        var attempt = 0
        val backoffDelays = AppConstants.Retry.BACKOFF_DELAYS_MS

        while (attempt <= maxRetries && !isCancelled.get()) {
            try {
                return sendFileViaRelay(
                    relayWsUrl = relayWsUrl,
                    session = session,
                    fileChannel = fileChannel,
                    expectedSha256 = expectedSha256,
                    localCredential = localCredential,
                    ownerPublicKey = ownerPublicKey,
                    callback = callback,
                    peerValidator = peerValidator,
                    onPeerConnected = onPeerConnected,
                    ttlSeconds = ttlSeconds
                )
            } catch (e: Exception) {
                if (isCancelled.get()) return false
                val isRejection = e.message?.contains(ErrorCode.ERR_DEVICE_REJECTED) == true ||
                        e.message?.contains("0x102") == true ||
                        e.message?.contains("từ chối", ignoreCase = true) == true
                if (isRejection) {
                    currentState = TransferEngineState.FAILED
                    callback?.onStateChanged(currentState)
                    throw e
                }
                attempt++
                if (attempt > maxRetries) {
                    currentState = TransferEngineState.FAILED
                    callback?.onError(ErrorCode.ERR_RELAY_UNAVAILABLE, "ERR_RELAY_UNAVAILABLE: Không thể kết nối tới Relay sau $maxRetries lần thử lại")
                    callback?.onStateChanged(currentState)
                    throw e
                }
                val delayMs = backoffDelays.getOrElse(attempt - 1) { 4000L }
                callback?.onError(ErrorCode.ERR_RELAY_UNAVAILABLE, "Mất kết nối tới Relay. Thử lại sau ${delayMs / 1000} giây (Lần $attempt/$maxRetries)...")
                delay(delayMs)
            }
        }
        return false
    }

    /**
     * Gửi tệp tin qua Relay Server (Sender Flow - Single Attempt).
     */
    suspend fun sendFileViaRelay(
        relayWsUrl: String,
        session: TransferSession,
        fileChannel: FileChannel,
        expectedSha256: String,
        localCredential: TeamMembershipCredential? = null,
        ownerPublicKey: PublicKey? = null,
        callback: TransferProgressCallback? = null,
        peerValidator: (suspend (HandshakeAuthPayload) -> Boolean)? = null,
        onPeerConnected: ((DeviceProfile) -> Unit)? = null,
        ttlSeconds: Int = 0
    ): Boolean = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        isPaused.set(false)
        currentState = TransferEngineState.CONNECTING
        callback?.onStateChanged(currentState)

        val connection = RelayWebSocketConnection(okHttpClient)
        activeConnection = connection

        val initialOffset = sessionDao?.getSessionById(session.sessionId)?.confirmedOffset ?: session.confirmedOffset
        val confirmedOffset = AtomicLong(initialOffset)
        val verifyResultDeferred = CompletableDeferred<VerifyResultPayload>()
        var readerJob: Job? = null
        val streamIdInt = 1

        try {
            connection.connect(relayWsUrl)

            // 1. Gửi bản tin ATTACH_STREAM
            val attachReq = AttachStreamRequest(
                streamId = session.sessionId,
                role = "SENDER",
                authTicket = session.sessionToken,
                ttlSeconds = ttlSeconds
            )
            connection.sendText(attachReq.toJson())

            val attachRespStr = connection.receiveText()
            val attachResp = AttachStreamResponse.fromJson(attachRespStr)
            if (attachResp.isBusy) {
                val busyMsg = attachResp.message ?: "Kênh chuyển tiếp Relay đang bận phục vụ thiết bị khác (chế độ 1-1)."
                throw IOException("${ErrorCode.ERR_RELAY_CHANNEL_BUSY}: $busyMsg")
            }
            if (!attachResp.isSuccess) {
                throw IOException("Relay từ chối gắn kết nối: ${attachResp.message}")
            }

            currentState = TransferEngineState.HANDSHAKING
            callback?.onStateChanged(currentState)

            // 2. Trao đổi khóa phiên E2EE (Ephemeral ECDH)
            val localE2ePayload = e2eManager.initSession(session.sessionId)
            connection.sendText(localE2ePayload.toJson())

            val peerE2eJson = connection.receiveText()
            val peerE2ePayload = E2EKeyExchangePayload.fromJson(peerE2eJson)
            val sas = e2eManager.completeKeyExchange(peerE2ePayload)
            callback?.onSasGenerated(sas)

            // 3. Trao đổi xác thực hai chiều qua Binary Frames
            val effectiveTeamId = localCredential?.teamId?.ifBlank { session.sessionToken } ?: session.sessionToken
            val effectiveMemberId = localCredential?.memberId ?: "member_${session.sessionId.take(8)}"
            val effectiveFingerprint = localCredential?.deviceFingerprint ?: session.peerFingerprint
            val effectiveRole = localCredential?.role?.name ?: "MEMBER"
            val effectiveSignature = localCredential?.ownerSignature ?: ""

            val myManufacturer = try {
                android.os.Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: "Android"
            } catch (_: Throwable) {
                "Android"
            }
            val myModel = try {
                android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Generic Device"
            } catch (_: Throwable) {
                "Generic Device"
            }

            val authPayload = HandshakeAuthPayload(
                teamId = effectiveTeamId,
                memberId = effectiveMemberId,
                deviceFingerprint = effectiveFingerprint,
                role = effectiveRole,
                credentialData = effectiveSignature,
                clientNonce = System.currentTimeMillis().toString(),
                deviceManufacturer = myManufacturer,
                deviceModel = myModel
            )
            val authFrame = serializer.buildHandshakeAuthFrame(streamIdInt, authPayload)
            connection.sendBinary(serializeFrame(authFrame))

            val peerAuthBytes = connection.receiveBinary()
            val peerAuthFrame = parseFrame(peerAuthBytes)
            val peerAuthPayload = HandshakeAuthPayload.fromBytes(peerAuthFrame.payload)

            // Thẩm định bên nhận: nếu có ownerPublicKey và localCredential thì kiểm tra chữ ký số, ngược lại kiểm tra sessionToken
            if (ownerPublicKey != null && localCredential != null && localCredential.teamId.isNotBlank()) {
                val claims = listOf(
                    "${peerAuthPayload.teamId}:${peerAuthPayload.memberId}:${peerAuthPayload.deviceFingerprint}:${peerAuthPayload.role}",
                    "${peerAuthPayload.teamId}|${peerAuthPayload.memberId}|${peerAuthPayload.role}",
                    "${peerAuthPayload.teamId}|${peerAuthPayload.deviceFingerprint}|${peerAuthPayload.role}"
                )
                val peerSignatureBytes = parseHexOrBytes(peerAuthPayload.credentialData)
                var isValidPeer = false
                for (claim in claims) {
                    if (CryptoEngine.verifySignature(ownerPublicKey, claim.toByteArray(Charsets.UTF_8), peerSignatureBytes)) {
                        isValidPeer = true
                        break
                    }
                }
                if (!isValidPeer && peerAuthPayload.role == "OWNER" && peerAuthPayload.teamId == localCredential.teamId) {
                    val ownerClaim = "${peerAuthPayload.teamId}:${peerAuthPayload.memberId}:${peerAuthPayload.deviceFingerprint}:OWNER"
                    isValidPeer = CryptoEngine.verifySignature(ownerPublicKey, ownerClaim.toByteArray(Charsets.UTF_8), peerSignatureBytes)
                }
                if (!isValidPeer && peerValidator?.invoke(peerAuthPayload) == true) {
                    isValidPeer = true
                }
                if (!isValidPeer) {
                    callback?.onError(ErrorCode.ERR_INVALID_CREDENTIAL, "Chứng thư thành viên của bên nhận không hợp lệ (sai chữ ký Owner)")
                    throw SecurityException("Bên nhận mang chứng thư giả mạo hoặc sai chữ ký số của Owner")
                }
            } else {
                // Thẩm định sessionToken của bên nhận (Stage 3)
                if (session.sessionToken.isNotBlank() && peerAuthPayload.teamId.isNotBlank() && peerAuthPayload.teamId != session.sessionToken) {
                    callback?.onError(ErrorCode.ERR_INVALID_CREDENTIAL, "Session token của bên nhận không khớp")
                    throw SecurityException("Bên nhận mang session token không hợp lệ")
                }
                if (peerValidator != null) {
                    val isApproved = peerValidator.invoke(peerAuthPayload)
                    if (!isApproved) {
                        try {
                            val alertPayload = ErrorAlertPayload(
                                errorCode = ErrorCode.ERR_DEVICE_REJECTED,
                                errorMessage = "Yêu cầu kết nối của bạn đã bị người gửi từ chối."
                            )
                            val alertFrame = serializer.buildErrorAlertFrame(streamIdInt, alertPayload)
                            connection.sendBinary(serializeFrame(alertFrame))
                        } catch (_: Exception) {}
                        throw SecurityException("${ErrorCode.ERR_DEVICE_REJECTED}: Người gửi đã từ chối yêu cầu kết nối từ thiết bị ${peerAuthPayload.memberId}")
                    }
                }
            }

            val peerProfile = DeviceProfile(
                manufacturer = peerAuthPayload.deviceManufacturer.ifBlank { "Android" },
                model = peerAuthPayload.deviceModel.ifBlank { peerAuthPayload.memberId },
                fingerprint = peerAuthPayload.deviceFingerprint
            )
            onPeerConnected?.invoke(peerProfile)

            // 4. Gửi TRANSFER_MANIFEST (0x02) sang Receiver (SDD Mục 6.2)
            val effectiveDocId = session.docId.ifBlank { "file_${session.sessionId.take(8)}" }
            val manifestPayload = TransferManifestPayload(
                docId = effectiveDocId,
                fileName = effectiveDocId,
                fileSize = session.totalBytes,
                mimeType = "application/octet-stream",
                expectedSha256 = expectedSha256,
                chunkSize = bufferPool.bufferSize
            )
            val manifestFrame = serializer.buildTransferManifestFrame(streamIdInt, manifestPayload)
            connection.sendBinary(serializeFrame(manifestFrame))

            // Đợi phản hồi MANIFEST_RESPONSE (0x03) từ Receiver
            val responseBytes = connection.receiveBinary()
            val responseFrame = parseFrame(responseBytes)
            if (responseFrame.msgType != ChunkFrame.TYPE_MANIFEST_RESPONSE) {
                throw IOException("Khung tin mong đợi MANIFEST_RESPONSE (0x03), nhận được: ${responseFrame.msgType}")
            }
            val manifestResp = ManifestResponsePayload.fromBytes(responseFrame.payload)
            if (!manifestResp.accepted) {
                val reason = manifestResp.rejectionReason ?: "Bên nhận từ chối yêu cầu truyền tệp"
                callback?.onError(ErrorCode.ERR_DEVICE_REJECTED, reason)
                throw IOException(reason)
            }

            val startOffset = manifestResp.startOffset
            confirmedOffset.set(startOffset)
            sessionDao?.updateCheckpoint(session.sessionId, startOffset)
            fileChannel.position(startOffset)

            // 5. Khởi chạy Coroutine lắng nghe các gói tin điều khiển từ Receiver (ACK, VERIFY_RESULT)
            val coroutineScope = CoroutineScope(Dispatchers.IO)
            readerJob = coroutineScope.launch {
                try {
                    while (isActive && connection.isOpen()) {
                        val frameBytes = try {
                            connection.receiveBinary()
                        } catch (e: Exception) {
                            break
                        }
                        val frame = parseFrame(frameBytes)
                        when (frame.msgType) {
                            ChunkFrame.TYPE_CHUNK_ACK -> {
                                val ack = ChunkAckPayload.fromBytes(frame.payload)
                                confirmedOffset.set(ack.confirmedOffset)
                                sessionDao?.updateCheckpoint(session.sessionId, ack.confirmedOffset)
                            }
                            ChunkFrame.TYPE_VERIFY_RESULT -> {
                                verifyResultDeferred.complete(VerifyResultPayload.fromBytes(frame.payload))
                                break
                            }
                            ChunkFrame.TYPE_CONTROL_SIGNAL -> {
                                val sig = ControlSignalPayload.fromBytes(frame.payload)
                                if (sig.command == "CANCEL") {
                                    verifyResultDeferred.completeExceptionally(IOException("Phía nhận đã hủy"))
                                    break
                                }
                            }
                            else -> {}
                        }
                    }
                } finally {
                    if (!verifyResultDeferred.isCompleted) {
                        verifyResultDeferred.completeExceptionally(
                            EOFException("Kết nối Relay bị đóng trước khi nhận kết quả thẩm định")
                        )
                    }
                }
            }

            currentState = TransferEngineState.TRANSFERRING
            callback?.onStateChanged(currentState)

            var currentOffset = startOffset
            val totalBytes = session.totalBytes
            var chunkSeq = (startOffset / bufferPool.bufferSize).toInt()
            val startTime = System.currentTimeMillis()
            val maxInflightBytes = AppConstants.FlowControl.MAX_INFLIGHT_BYTES // 4MB Cửa sổ trượt (Sliding Window Flow Control)

            // 6. Vòng lặp truyền khối dữ liệu
            while (currentOffset < totalBytes && !isCancelled.get()) {
                while (isPaused.get() && !isCancelled.get()) {
                    delay(AppConstants.Timeout.PAUSE_POLL_INTERVAL_MS)
                }
                if (isCancelled.get()) break

                // Backpressure: Nếu lượng dữ liệu bay trước vượt quá 4MB so với mốc ACK đã xác nhận, tạm dừng nhường luồng chờ ACK
                while (currentOffset - confirmedOffset.get() >= maxInflightBytes && !isCancelled.get()) {
                    delay(AppConstants.Timeout.BACKPRESSURE_POLL_INTERVAL_MS)
                }
                if (isCancelled.get()) break

                val buffer = bufferPool.acquire()
                val bytesRead = try {
                    fileChannel.read(ByteBuffer.wrap(buffer))
                } catch (e: Exception) {
                    bufferPool.release(buffer)
                    throw e
                }

                if (bytesRead == -1) {
                    bufferPool.release(buffer)
                    break
                }

                try {
                    // Mã hóa E2EE cho payload
                    val encryptedChunk = e2eManager.encryptChunk(
                        chunkIndex = chunkSeq.toLong(),
                        plaintext = buffer,
                        offset = 0,
                        length = bytesRead
                    )

                    // Đóng gói Binary Frame DATA_CHUNK
                    val dataChunkPayload = DataChunkPayload(
                        chunkSeq = chunkSeq,
                        chunkOffset = currentOffset,
                        chunkData = encryptedChunk
                    )
                    val frame = serializer.buildDataChunkFrame(streamIdInt, dataChunkPayload)
                    val frameBytes = serializeFrame(frame)

                    val sent = connection.sendBinary(frameBytes)
                    if (!sent) {
                        throw IOException("Gửi binary frame qua WebSocket thất bại")
                    }

                    currentOffset += bytesRead
                    chunkSeq++

                    val elapsed = maxOf(1L, System.currentTimeMillis() - startTime)
                    val speed = (currentOffset - startOffset) * 1000L / elapsed
                    callback?.onProgress(currentOffset, totalBytes, speed)
                } finally {
                    bufferPool.release(buffer)
                }
            }

            if (isCancelled.get()) {
                currentState = TransferEngineState.CANCELLED
                callback?.onStateChanged(currentState)
                return@withContext false
            }

            // 6. Đợi VERIFY_RESULT từ Receiver
            currentState = TransferEngineState.VERIFYING
            callback?.onStateChanged(currentState)
            val verifyResult = verifyResultDeferred.await()

            if (verifyResult.status == "SUCCESS") {
                currentState = TransferEngineState.COMPLETED
                callback?.onStateChanged(currentState)
                callback?.onCompleted(session.docId, verifyResult.actualSha256)
                true
            } else {
                currentState = TransferEngineState.FAILED
                callback?.onStateChanged(currentState)
                callback?.onError(ErrorCode.ERR_CHECKSUM_MISMATCH, "Sai lệch mã băm SHA-256 từ phía nhận")
                false
            }

        } finally {
            readerJob?.cancel()
            connection.close()
            activeConnection = null
            e2eManager.clear()
        }
    }

    /**
     * Tiếp nhận tệp tin qua Relay Server (Receiver Flow) với cơ chế tự động thử lại khi mất kết nối.
     */
    suspend fun receiveFileViaRelayWithRetry(
        relayWsUrl: String,
        session: TransferSession,
        targetFile: File,
        expectedSha256: String,
        localCredential: TeamMembershipCredential? = null,
        ownerPublicKey: PublicKey? = null,
        callback: TransferProgressCallback? = null,
        peerValidator: ((HandshakeAuthPayload) -> Boolean)? = null,
        maxRetries: Int = AppConstants.Retry.MAX_RELAY_RETRIES
    ): File? {
        var attempt = 0
        val backoffDelays = AppConstants.Retry.BACKOFF_DELAYS_MS

        while (attempt <= maxRetries && !isCancelled.get()) {
            try {
                return receiveFileViaRelay(
                    relayWsUrl = relayWsUrl,
                    session = session,
                    targetFile = targetFile,
                    expectedSha256 = expectedSha256,
                    localCredential = localCredential,
                    ownerPublicKey = ownerPublicKey,
                    callback = callback,
                    peerValidator = peerValidator
                )
            } catch (e: Exception) {
                if (isCancelled.get()) return null
                val isBusy = e.message?.contains(ErrorCode.ERR_RELAY_CHANNEL_BUSY) == true ||
                        e.message?.contains("0x303") == true ||
                        e.message?.contains("đang bận", ignoreCase = true) == true
                if (isBusy) {
                    currentState = TransferEngineState.FAILED
                    callback?.onStateChanged(currentState)
                    callback?.onError(ErrorCode.ERR_RELAY_CHANNEL_BUSY, e.message ?: "Kênh chuyển tiếp Relay hiện đang bận.")
                    throw e
                }
                val isRejection = e.message?.contains(ErrorCode.ERR_DEVICE_REJECTED) == true ||
                        e.message?.contains("0x102") == true ||
                        e.message?.contains("từ chối", ignoreCase = true) == true
                if (isRejection) {
                    currentState = TransferEngineState.FAILED
                    callback?.onStateChanged(currentState)
                    throw e
                }
                attempt++
                if (attempt > maxRetries) {
                    currentState = TransferEngineState.FAILED
                    callback?.onError(ErrorCode.ERR_RELAY_UNAVAILABLE, "ERR_RELAY_UNAVAILABLE: Không thể kết nối tới Relay sau $maxRetries lần thử lại")
                    callback?.onStateChanged(currentState)
                    throw e
                }
                val delayMs = backoffDelays.getOrElse(attempt - 1) { 4000L }
                callback?.onError(ErrorCode.ERR_RELAY_UNAVAILABLE, "Mất kết nối tới Relay. Thử lại sau ${delayMs / 1000} giây (Lần $attempt/$maxRetries)...")
                delay(delayMs)
            }
        }
        return null
    }

    /**
     * Tiếp nhận tệp tin qua Relay Server (Receiver Flow - Single Attempt).
     */
    suspend fun receiveFileViaRelay(
        relayWsUrl: String,
        session: TransferSession,
        targetFile: File,
        expectedSha256: String,
        localCredential: TeamMembershipCredential? = null,
        ownerPublicKey: PublicKey? = null,
        callback: TransferProgressCallback? = null,
        peerValidator: ((HandshakeAuthPayload) -> Boolean)? = null
    ): File? = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        isPaused.set(false)
        currentState = TransferEngineState.CONNECTING
        callback?.onStateChanged(currentState)

        val connection = RelayWebSocketConnection(okHttpClient)
        activeConnection = connection

        val partFile = File(targetFile.parentFile, "${targetFile.name}.part").also { activePartFile = it }
        var raf: RandomAccessFile? = null
        var fileChannel: FileChannel? = null
        val streamIdInt = 1

        try {
            connection.connect(relayWsUrl)

            // 1. Gửi bản tin ATTACH_STREAM với vai trò RECEIVER
            val attachReq = AttachStreamRequest(
                streamId = session.sessionId,
                role = "RECEIVER",
                authTicket = session.sessionToken
            )
            connection.sendText(attachReq.toJson())

            val attachRespStr = connection.receiveText()
            val attachResp = AttachStreamResponse.fromJson(attachRespStr)
            if (attachResp.isBusy) {
                val busyMsg = attachResp.message ?: "Kênh chuyển tiếp Relay đang bận phục vụ thiết bị khác (chế độ 1-1)."
                throw IOException("${ErrorCode.ERR_RELAY_CHANNEL_BUSY}: $busyMsg")
            }
            if (!attachResp.isSuccess) {
                throw IOException("Relay từ chối gắn kết nối: ${attachResp.message}")
            }

            currentState = TransferEngineState.HANDSHAKING
            callback?.onStateChanged(currentState)

            // 2. Trao đổi khóa phiên E2EE (Ephemeral ECDH)
            val localE2ePayload = e2eManager.initSession(session.sessionId)
            connection.sendText(localE2ePayload.toJson())

            val peerE2eJson = connection.receiveText()
            val peerE2ePayload = E2EKeyExchangePayload.fromJson(peerE2eJson)
            val sas = e2eManager.completeKeyExchange(peerE2ePayload)
            callback?.onSasGenerated(sas)

            // 3. Trao đổi xác thực hai chiều Handshake Auth
            val effectiveTeamId = localCredential?.teamId?.ifBlank { session.sessionToken } ?: session.sessionToken
            val effectiveMemberId = localCredential?.memberId ?: "member_${session.sessionId.take(8)}"
            val effectiveFingerprint = localCredential?.deviceFingerprint ?: session.peerFingerprint
            val effectiveRole = localCredential?.role?.name ?: "MEMBER"
            val effectiveSignature = localCredential?.ownerSignature ?: ""

            val myManufacturer = try {
                android.os.Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: "Android"
            } catch (_: Throwable) {
                "Android"
            }
            val myModel = try {
                android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Generic Device"
            } catch (_: Throwable) {
                "Generic Device"
            }

            val authPayload = HandshakeAuthPayload(
                teamId = effectiveTeamId,
                memberId = effectiveMemberId,
                deviceFingerprint = effectiveFingerprint,
                role = effectiveRole,
                credentialData = effectiveSignature,
                clientNonce = System.currentTimeMillis().toString(),
                deviceManufacturer = myManufacturer,
                deviceModel = myModel
            )
            val authFrame = serializer.buildHandshakeAuthFrame(streamIdInt, authPayload)
            connection.sendBinary(serializeFrame(authFrame))

            val peerAuthBytes = connection.receiveBinary()
            val peerAuthFrame = parseFrame(peerAuthBytes)
            val peerAuthPayload = HandshakeAuthPayload.fromBytes(peerAuthFrame.payload)

            // Thẩm định bên gửi: nếu có ownerPublicKey và localCredential thì kiểm tra chữ ký số, ngược lại kiểm tra sessionToken
            if (ownerPublicKey != null && localCredential != null && localCredential.teamId.isNotBlank()) {
                val claims = listOf(
                    "${peerAuthPayload.teamId}:${peerAuthPayload.memberId}:${peerAuthPayload.deviceFingerprint}:${peerAuthPayload.role}",
                    "${peerAuthPayload.teamId}|${peerAuthPayload.memberId}|${peerAuthPayload.role}",
                    "${peerAuthPayload.teamId}|${peerAuthPayload.deviceFingerprint}|${peerAuthPayload.role}"
                )
                val peerSignatureBytes = parseHexOrBytes(peerAuthPayload.credentialData)
                var isValidPeer = false
                for (claim in claims) {
                    if (CryptoEngine.verifySignature(ownerPublicKey, claim.toByteArray(Charsets.UTF_8), peerSignatureBytes)) {
                        isValidPeer = true
                        break
                    }
                }
                if (!isValidPeer && peerAuthPayload.role == "OWNER" && peerAuthPayload.teamId == localCredential.teamId) {
                    val ownerClaim = "${peerAuthPayload.teamId}:${peerAuthPayload.memberId}:${peerAuthPayload.deviceFingerprint}:OWNER"
                    isValidPeer = CryptoEngine.verifySignature(ownerPublicKey, ownerClaim.toByteArray(Charsets.UTF_8), peerSignatureBytes)
                }
                if (!isValidPeer && peerValidator?.invoke(peerAuthPayload) == true) {
                    isValidPeer = true
                }
                if (!isValidPeer) {
                    callback?.onError(ErrorCode.ERR_INVALID_CREDENTIAL, "Chứng thư thành viên của bên gửi không hợp lệ (sai chữ ký Owner)")
                    throw SecurityException("Bên gửi mang chứng thư giả mạo hoặc sai chữ ký số của Owner")
                }
            } else {
                if (peerAuthPayload.teamId != session.sessionToken && peerValidator?.invoke(peerAuthPayload) != true) {
                    if (session.sessionToken.isNotBlank() && peerAuthPayload.teamId.isNotBlank() && peerAuthPayload.teamId != session.sessionToken) {
                        callback?.onError(ErrorCode.ERR_INVALID_CREDENTIAL, "Session token của bên gửi không khớp")
                        throw SecurityException("Bên gửi mang session token không hợp lệ")
                    }
                }
            }

            // 4. Nhận TRANSFER_MANIFEST (0x02) từ Sender (SDD Mục 6.2)
            val manifestBytes = connection.receiveBinary()
            val manifestFrame = parseFrame(manifestBytes)
            if (manifestFrame.msgType == ChunkFrame.TYPE_ERROR_ALERT) {
                val errPayload = ErrorAlertPayload.fromBytes(manifestFrame.payload)
                callback?.onError(errPayload.errorCode, errPayload.errorMessage)
                throw SecurityException("${errPayload.errorCode}: ${errPayload.errorMessage}")
            }
            if (manifestFrame.msgType != ChunkFrame.TYPE_TRANSFER_MANIFEST) {
                throw IOException("Khung tin mong đợi TRANSFER_MANIFEST (0x02), nhận được: ${manifestFrame.msgType}")
            }
            val manifest = TransferManifestPayload.fromBytes(manifestFrame.payload)

            // Kiểm tra mốc Resume từ tệp tạm .part hiện có và checkpoint DB
            val dbOffset = sessionDao?.getSessionById(session.sessionId)?.confirmedOffset ?: 0L
            val startOffset = if (partFile.exists() && partFile.length() < manifest.fileSize) {
                if (dbOffset > 0 && dbOffset <= partFile.length()) dbOffset else partFile.length()
            } else {
                if (partFile.exists() && partFile.length() >= manifest.fileSize) {
                    partFile.delete()
                }
                0L
            }

            // Phản hồi MANIFEST_RESPONSE (0x03) về cho Sender mang startOffset
            val manifestResp = ManifestResponsePayload(
                accepted = true,
                startOffset = startOffset,
                rejectionReason = null
            )
            val responseFrame = serializer.buildManifestResponseFrame(streamIdInt, manifestResp)
            connection.sendBinary(serializeFrame(responseFrame))

            // 5. Chuẩn bị file `.part` và vị trí ghi
            raf = RandomAccessFile(partFile, "rw")
            raf.seek(startOffset)
            fileChannel = raf.channel

            val checkpointTracker = CheckpointTracker(
                sessionId = session.sessionId,
                sessionDao = sessionDao,
                flushThreshold = checkpointThresholdBytes
            ).apply { initOffset(startOffset) }

            currentState = TransferEngineState.TRANSFERRING
            callback?.onStateChanged(currentState)
            var totalBytesReceived = startOffset
            val totalBytes = manifest.fileSize
            val startTime = System.currentTimeMillis()
            var connectionFailed = false
            var connectionError: Exception? = null

            // 6. Nhận các khối dữ liệu DATA_CHUNK
            while (totalBytesReceived < totalBytes && !isCancelled.get()) {
                val frameBytes = try {
                    connection.receiveBinary()
                } catch (e: Exception) {
                    connectionFailed = true
                    connectionError = e
                    break
                }

                val frame = parseFrame(frameBytes)
                if (frame.msgType != ChunkFrame.TYPE_DATA_CHUNK) {
                    continue
                }

                val dataChunk = DataChunkPayload.fromBytes(frame.payload)

                // Giải mã E2EE cho payload
                val decryptedChunk = e2eManager.decryptChunk(
                    chunkIndex = dataChunk.chunkSeq.toLong(),
                    encryptedPacket = dataChunk.chunkData
                )

                fileChannel.write(ByteBuffer.wrap(decryptedChunk))
                totalBytesReceived += decryptedChunk.size

                val ackOffset = checkpointTracker.recordBytesWritten(fileChannel, decryptedChunk.size)
                if (ackOffset != null) {
                    val ackFrame = serializer.buildChunkAckFrame(
                        streamIdInt,
                        ChunkAckPayload(confirmedOffset = ackOffset)
                    )
                    connection.sendBinary(serializeFrame(ackFrame))
                }

                val elapsed = maxOf(1L, System.currentTimeMillis() - startTime)
                val speed = (totalBytesReceived - startOffset) * 1000L / elapsed
                callback?.onProgress(totalBytesReceived, totalBytes, speed)
            }

            if (isCancelled.get()) {
                SafeFileDeleter.wipeAndSanitizeFile(partFile)
                currentState = TransferEngineState.CANCELLED
                callback?.onStateChanged(currentState)
                return@withContext null
            }

            // Xử lý khi bị mất kết nối tới Relay Server giữa chừng (Bảo toàn tệp .part để Resume)
            if (connectionFailed || totalBytesReceived < totalBytes) {
                checkpointTracker.flushFinal(fileChannel)
                currentState = TransferEngineState.FAILED
                callback?.onStateChanged(currentState)
                callback?.onError(
                    ErrorCode.ERR_RELAY_UNAVAILABLE,
                    "Mất kết nối tới Relay Server khi đang truyền dữ liệu ($totalBytesReceived / $totalBytes bytes)"
                )
                throw (connectionError ?: IOException("Mất kết nối với máy chủ chuyển tiếp Relay"))
            }

            // 7. Flush dữ liệu cuối cùng và thẩm định SHA-256 (chỉ khi đã nhận đủ 100% dữ liệu)
            checkpointTracker.flushFinal(fileChannel)
            fileChannel.close()
            fileChannel = null
            raf.close()
            raf = null

            currentState = TransferEngineState.VERIFYING
            callback?.onStateChanged(currentState)
            val actualSha256 = Sha256StreamCalculator.calculateFileSha256(partFile)

            val targetExpectedSha = if (expectedSha256.isNotBlank()) {
                expectedSha256
            } else {
                manifest.expectedSha256
            }

            val isMatch = targetExpectedSha.isNotBlank() && actualSha256.equals(targetExpectedSha, ignoreCase = true)
            val verifyPayload = VerifyResultPayload(
                sessionId = session.sessionId,
                status = if (isMatch) VerifyResultPayload.STATUS_SUCCESS else VerifyResultPayload.STATUS_CHECKSUM_FAILED,
                actualSha256 = actualSha256
            )
            val verifyFrame = serializer.buildVerifyResultFrame(streamIdInt, verifyPayload)
            connection.sendBinary(serializeFrame(verifyFrame))
            delay(AppConstants.Timeout.VERIFY_FLUSH_DELAY_MS) // Đảm bảo frame VERIFY_RESULT được truyền đi qua mạng trước khi đóng kết nối

            if (isMatch) {
                val finalTargetFile = if (targetFile.name.startsWith(AppConstants.StorageDirectory.FILE_PREFIX) && manifest.fileName.isNotBlank() && manifest.fileName != targetFile.name) {
                    File(targetFile.parentFile, FilenameSanitizer.sanitize(manifest.fileName))
                } else {
                    targetFile
                }
                if (finalTargetFile.exists()) {
                    finalTargetFile.delete()
                }
                val renamed = partFile.renameTo(finalTargetFile)
                if (!renamed) {
                    throw IOException("Không thể đổi tên tệp tạm ${partFile.name} thành ${finalTargetFile.name}")
                }
                sessionDao?.updateSessionFile(session.sessionId, finalTargetFile.absolutePath)
                currentState = TransferEngineState.COMPLETED
                callback?.onStateChanged(currentState)
                callback?.onCompleted(finalTargetFile.name, actualSha256)
                finalTargetFile
            } else {
                SafeFileDeleter.wipeAndSanitizeFile(partFile)
                currentState = TransferEngineState.FAILED
                callback?.onStateChanged(currentState)
                callback?.onError(ErrorCode.ERR_CHECKSUM_MISMATCH, "Sai lệch mã băm SHA-256 (Kỳ vọng: $targetExpectedSha, Thực tế: $actualSha256)")
                null
            }

        } finally {
            activePartFile = null
            try { fileChannel?.close() } catch (_: Exception) {}
            try { raf?.close() } catch (_: Exception) {}
            connection.close()
            activeConnection = null
            e2eManager.clear()
        }
    }
}
