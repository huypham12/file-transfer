package com.teamshare.app.core.transfer.lan

import com.teamshare.app.core.constant.AppConstants
import com.teamshare.app.domain.model.DomainError
import com.teamshare.app.domain.model.ErrorCode

import com.teamshare.app.core.network.tls.TlsSocketFactory
import com.teamshare.app.core.protocol.BinaryFrameParser
import com.teamshare.app.core.protocol.ChunkAckPayload
import com.teamshare.app.core.protocol.ChunkFrame
import com.teamshare.app.core.protocol.ConnectAcceptedPayload
import com.teamshare.app.core.protocol.ConnectPendingPayload
import com.teamshare.app.core.protocol.ConnectRequestPayload
import com.teamshare.app.core.protocol.ControlSignalPayload
import com.teamshare.app.core.protocol.DataChunkPayload
import com.teamshare.app.core.protocol.ErrorAlertPayload
import com.teamshare.app.core.protocol.FrameSerializer
import com.teamshare.app.core.protocol.HandshakeAuthPayload
import com.teamshare.app.core.protocol.ManifestItemPayload
import com.teamshare.app.core.protocol.ManifestResponsePayload
import com.teamshare.app.core.protocol.RequestStreamPayload
import com.teamshare.app.core.protocol.TransferManifestPayload
import com.teamshare.app.core.protocol.VerifyResultPayload
import com.teamshare.app.core.platform.storage.IStorageAuditor
import com.teamshare.app.core.platform.storage.StatFsStorageAuditor
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.core.security.sanitizer.FilenameSanitizer
import com.teamshare.app.core.storage.saf.SafeFileDeleter
import com.teamshare.app.core.transfer.buffer.BufferPoolManager
import com.teamshare.app.core.transfer.checkpoint.CheckpointTracker
import com.teamshare.app.core.transfer.zip.SafeStreamExtractor
import com.teamshare.app.core.transfer.zip.ZipStreamPacker
import com.teamshare.app.data.local.db.dao.TransferSessionDao
import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.DeviceProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Động cơ Truyền tải Tệp tin mạng LAN tốc độ cao và bảo mật (LanTransferEngine).
 * Thực thi giao thức khung tin nhị phân 11 bytes, quản lý bộ đệm 64KB (tối đa 4MB),
 * điểm kiểm tra Checkpoint 2MB (fsync SSD), và xác minh toàn vẹn SHA-256 theo chuẩn SDD Chương 6.2 & 8.1.
 */
class LanTransferEngine(
    private val bufferPool: BufferPoolManager = BufferPoolManager(),
    private val socketFactory: TlsSocketFactory = TlsSocketFactory(),
    private val serializer: FrameSerializer = FrameSerializer(),
    private val parser: BinaryFrameParser = BinaryFrameParser(),
    private val keyManager: IKeyManager? = null,
    private val sessionDao: TransferSessionDao? = null,
    private val storageAuditor: IStorageAuditor = StatFsStorageAuditor()
) {
    companion object {
        const val DEFAULT_SERVER_TIMEOUT_MS = AppConstants.Timeout.SERVER_SOCKET_ACCEPT_TIMEOUT_MS
        val DEFAULT_LAN_PORT = com.teamshare.app.core.platform.network.NetworkConfig.DEFAULT_LAN_PORT
    }

    private var activeServerSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private val activeSockets = java.util.concurrent.ConcurrentHashMap<String, Socket>()
    private val receiverRegistry = ReceiverRegistry(maxCapacity = AppConstants.FlowControl.MAX_SESSION_RECEIVERS)
    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

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
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSockets.values.forEach { try { it.close() } catch (_: Exception) {} }
        activeSockets.clear()
        receiverRegistry.clearAll()
        try {
            activeServerSocket?.close()
        } catch (_: Exception) {}
    }

    /**
     * Người gửi chủ động thu hồi slot của một thiết bị nhận theo fingerprint.
     * Giải phóng 1 slot trong ReceiverRegistry theo đặc tả kiến trúc v2.2.
     */
    fun revokeReceiver(fingerprint: String): Boolean {
        val slot = receiverRegistry.revoke(fingerprint) ?: return false
        activeSockets.remove(fingerprint)?.let { try { it.close() } catch (_: Exception) {} }
        return true
    }

    fun getReceiverRegistry(): ReceiverRegistry = receiverRegistry

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

    // =====================================================================================
    // CHẾ ĐỘ MÁY CHỦ GỬI DỮ LIỆU (SENDER SERVER MODE - SDD MỤC 8.1)
    // Người gửi chọn tệp, mở ServerSocket cổng 8889, phát QR. Máy nhận quét QR và kết nối tới.
    // =====================================================================================

    suspend fun startSenderServer(
        port: Int = DEFAULT_LAN_PORT,
        sessionToken: String,
        file: File,
        sessionId: String,
        expectedSha256: String? = null,
        approvalMode: ApprovalMode = ApprovalMode.AUTO_ACCEPT,
        onDeviceApproval: (suspend (DeviceProfile) -> Boolean)? = null,
        onPeerConnected: ((DeviceProfile) -> Unit)? = null,
        useTls: Boolean = false,
        serverTimeoutMs: Int = DEFAULT_SERVER_TIMEOUT_MS,
        maxReceivers: Int = 1,
        callback: TransferProgressCallback? = null,
        onClientProgress: ((DeviceProfile, Long, Long, Long) -> Unit)? = null,
        onClientCompleted: ((DeviceProfile) -> Unit)? = null,
        onClientFailed: ((DeviceProfile, Throwable) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        require(file.exists() && (file.isFile || file.isDirectory)) { "Tệp hoặc thư mục nguồn không tồn tại: ${file.absolutePath}" }

        isCancelled.set(false)
        isPaused.set(false)
        currentState = TransferEngineState.CONNECTING
        callback?.onStateChanged(currentState)

        val serverSocket = try {
            socketFactory.createServerSocket(port = port, useTls = useTls).also {
                activeServerSocket = it
            }
        } catch (e: Exception) {
            currentState = TransferEngineState.FAILED
            callback?.onStateChanged(currentState)
            callback?.onError(ErrorCode.ERR_SERVER_BIND_ERROR, "Không thể mở ServerSocket tại cổng $port: ${e.message}")
            return@withContext Result.failure(e)
        }

        try {
            if (serverTimeoutMs > 0) {
                serverSocket.soTimeout = serverTimeoutMs
            }

            if (maxReceivers <= 1) {
                val socket = try {
                    serverSocket.accept().also { activeSocket = it }
                } catch (e: java.net.SocketTimeoutException) {
                    currentState = TransferEngineState.FAILED
                    callback?.onStateChanged(currentState)
                    callback?.onError(ErrorCode.ERR_SERVER_TIMEOUT, DomainError.ServerTimeout.technicalMessage)
                    return@withContext Result.failure(e)
                }

                return@withContext handleSenderClient(
                    socket = socket,
                    sessionToken = sessionToken,
                    file = file,
                    sessionId = sessionId,
                    expectedSha256 = expectedSha256,
                    approvalMode = approvalMode,
                    onDeviceApproval = onDeviceApproval,
                    onPeerConnected = onPeerConnected,
                    callback = callback,
                    onClientProgress = onClientProgress,
                    onClientCompleted = onClientCompleted,
                    onClientFailed = onClientFailed
                )
            } else {
                // Chế độ 1-N Đa người nhận (Kiến trúc v2.2 - Tối đa 5 thiết bị duy nhất trong phiên)
                val clientSemaphore = Semaphore(permits = AppConstants.FlowControl.MAX_CONCURRENT_OUTGOING_TRANSFERS)
                val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val lastSuccessSha = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val lastError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

                coroutineScope {
                    // Giám sát thời gian chờ không hoạt động (Inactivity Timeout 15 phút)
                    val inactivityJob = launch(Dispatchers.IO) {
                        while (isActive && !isCancelled.get()) {
                            delay(5000L)
                            if (receiverRegistry.isSessionInactive(AppConstants.Timeout.SESSION_INACTIVITY_TIMEOUT_MS)) {
                                cancel()
                                break
                            }
                        }
                    }

                    try {
                        while (isActive && !isCancelled.get() && completedCount.get() < maxReceivers) {
                            val clientSocket = try {
                                serverSocket.accept()
                            } catch (e: Exception) {
                                if (isCancelled.get() || completedCount.get() > 0 || receiverRegistry.getOccupiedCount() > 0) break
                                currentState = TransferEngineState.FAILED
                                callback?.onStateChanged(currentState)
                                lastError.set(e)
                                break
                            }

                            // Chấp nhận socket ngay lập tức để thực hiện Handshake và Admission Check,
                            // không bị phong tỏa bởi clientSemaphore ở bước accept().
                            launch(Dispatchers.IO) {
                                val res = handleSenderClient(
                                    socket = clientSocket,
                                    sessionToken = sessionToken,
                                    file = file,
                                    sessionId = sessionId,
                                    expectedSha256 = expectedSha256,
                                    approvalMode = approvalMode,
                                    onDeviceApproval = onDeviceApproval,
                                    onPeerConnected = onPeerConnected,
                                    callback = callback,
                                    onClientProgress = onClientProgress,
                                    onClientCompleted = onClientCompleted,
                                    onClientFailed = onClientFailed,
                                    clientSemaphore = clientSemaphore
                                )
                                if (res.isSuccess) {
                                    val count = completedCount.incrementAndGet()
                                    lastSuccessSha.set(res.getOrNull())
                                    if (count >= maxReceivers) {
                                        try { serverSocket.close() } catch (_: Exception) {}
                                    }
                                } else {
                                    lastError.set(res.exceptionOrNull())
                                }
                            }
                        }
                    } finally {
                        inactivityJob.cancel()
                    }
                }

                val finalSha = lastSuccessSha.get()
                if (finalSha != null) {
                    Result.success(finalSha)
                } else {
                    Result.failure(lastError.get() ?: IOException("Không có máy nhận nào hoàn tất truyền tải"))
                }
            }
        } catch (e: Exception) {
            currentState = if (isCancelled.get()) TransferEngineState.CANCELLED else TransferEngineState.FAILED
            callback?.onStateChanged(currentState)
            Result.failure(e)
        } finally {
            try { activeSocket?.close() } catch (_: Exception) {}
            activeSockets.values.forEach { try { it.close() } catch (_: Exception) {} }
            activeSockets.clear()
            receiverRegistry.clearAll()
            try { serverSocket.close() } catch (_: Exception) {}
            activeSocket = null
            activeServerSocket = null
        }
    }

    private suspend fun handleSenderClient(
        socket: Socket,
        sessionToken: String,
        file: File,
        sessionId: String,
        expectedSha256: String?,
        approvalMode: ApprovalMode,
        onDeviceApproval: (suspend (DeviceProfile) -> Boolean)?,
        onPeerConnected: ((DeviceProfile) -> Unit)?,
        callback: TransferProgressCallback?,
        onClientProgress: ((DeviceProfile, Long, Long, Long) -> Unit)? = null,
        onClientCompleted: ((DeviceProfile) -> Unit)? = null,
        onClientFailed: ((DeviceProfile, Throwable) -> Unit)? = null,
        clientSemaphore: Semaphore? = null
    ): Result<String> = coroutineScope {
        var peerProfile: DeviceProfile? = null
        var connectionGen: Long = 1L
        val clientCancelled = AtomicBoolean(false)
        val clientPaused = AtomicBoolean(false)
        try {
            val inputStream = BufferedInputStream(socket.getInputStream(), AppConstants.Buffer.STREAM_INPUT_BUFFER_BYTES)
            val outputStream = BufferedOutputStream(socket.getOutputStream(), AppConstants.Buffer.STREAM_INPUT_BUFFER_BYTES)
            val streamId = 1

            // 1. Nhận gói 0x01 CONNECT_REQUEST từ Receiver
            currentState = TransferEngineState.HANDSHAKING
            callback?.onStateChanged(currentState)

            val connectFrame = parser.parseNextFrame(inputStream)
                ?: throw IOException("Kết nối bị đóng trước khi nhận CONNECT_REQUEST")

            if (connectFrame.msgType != ChunkFrame.TYPE_CONNECT_REQUEST) {
                throw IOException("Khung tin mong đợi CONNECT_REQUEST (0x01), nhận được: ${connectFrame.msgType}")
            }

            val connectReq = ConnectRequestPayload.fromBytes(connectFrame.payload)

            // Thẩm định sessionToken
            if (connectReq.sessionToken != sessionToken) {
                val rejectFrame = serializer.buildConnectAcceptedFrame(
                    streamId,
                    ConnectAcceptedPayload(
                        status = ConnectAcceptedPayload.STATUS_REJECTED,
                        rejectionReason = "${ErrorCode.ERR_INVALID_CREDENTIAL}: ${DomainError.InvalidCredential.technicalMessage}",
                        reasonCode = ErrorCode.ERR_INVALID_CREDENTIAL
                    )
                )
                serializer.serializeTo(rejectFrame, outputStream)
                val err = "Session token không khớp: nhận được ${connectReq.sessionToken}"
                callback?.onError(ErrorCode.ERR_INVALID_CREDENTIAL, err)
                throw SecurityException(err)
            }

            val profile = DeviceProfile(
                manufacturer = connectReq.deviceManufacturer,
                model = connectReq.deviceModel,
                fingerprint = connectReq.deviceFingerprint
            )
            peerProfile = profile

            // 2. Thẩm định giới hạn 5 thiết bị (Admission Check) qua ReceiverRegistry
            val admission = receiverRegistry.requestAdmission(
                profile = profile,
                initialStatus = if (approvalMode == ApprovalMode.MANUAL_APPROVAL) ReceiverSlotStatus.PENDING else ReceiverSlotStatus.ACTIVE
            )

            val isReconnect = when (admission) {
                is AdmissionResult.SessionFull -> {
                    val rejectFrame = serializer.buildConnectAcceptedFrame(
                        streamId,
                        ConnectAcceptedPayload(
                            status = ConnectAcceptedPayload.STATUS_REJECTED,
                            rejectionReason = "ERR_SESSION_FULL: Phiên chia sẻ đã đạt giới hạn tối đa 5 thiết bị",
                            reasonCode = ErrorCode.ERR_SESSION_FULL
                        )
                    )
                    serializer.serializeTo(rejectFrame, outputStream)
                    val err = "Từ chối thiết bị ${profile.model} (${profile.fingerprint}): phiên đã đầy (${admission.currentOccupied}/${admission.maxCapacity} slots)"
                    callback?.onError(ErrorCode.ERR_SESSION_FULL, err)
                    throw DomainError.SessionFull
                }
                is AdmissionResult.Rejected -> {
                    val rejectFrame = serializer.buildConnectAcceptedFrame(
                        streamId,
                        ConnectAcceptedPayload(
                            status = ConnectAcceptedPayload.STATUS_REJECTED,
                            rejectionReason = "${admission.errorCode}: ${admission.reason}",
                            reasonCode = admission.errorCode
                        )
                    )
                    serializer.serializeTo(rejectFrame, outputStream)
                    callback?.onError(admission.errorCode, admission.reason)
                    throw SecurityException(admission.reason)
                }
                is AdmissionResult.Admitted -> {
                    connectionGen = admission.slot.connectionGeneration
                    activeSockets[profile.fingerprint] = socket
                    admission.isReconnect
                }
            }

            // 3. Phê duyệt thiết bị (Auto / Manual Dialog theo SDD 7.4 & 8.1)
            // Chỉ yêu cầu manual approval nếu đây không phải là phiên Reconnect
            if (approvalMode == ApprovalMode.MANUAL_APPROVAL && !isReconnect) {
                try {
                    val pendingPayload = ConnectPendingPayload(
                        timeoutSeconds = AppConstants.Timeout.APPROVAL_WAIT_TIMEOUT_MS / 1000,
                        message = "Đang chờ người gửi phê duyệt kết nối..."
                    )
                    val pendingFrame = serializer.buildConnectPendingFrame(streamId, pendingPayload)
                    serializer.serializeTo(pendingFrame, outputStream)
                } catch (_: Exception) {}

                val isApproved = onDeviceApproval?.invoke(profile) ?: false
                if (!isApproved) {
                    receiverRegistry.revoke(profile.fingerprint)
                    val rejectFrame = serializer.buildConnectAcceptedFrame(
                        streamId,
                        ConnectAcceptedPayload(
                            status = ConnectAcceptedPayload.STATUS_REJECTED,
                            rejectionReason = "${ErrorCode.ERR_DEVICE_REJECTED}: ${DomainError.DeviceRejected.technicalMessage}",
                            reasonCode = ErrorCode.ERR_DEVICE_REJECTED
                        )
                    )
                    serializer.serializeTo(rejectFrame, outputStream)
                    val err = "Người gửi từ chối kết nối từ thiết bị ${profile.model}"
                    callback?.onError(ErrorCode.ERR_DEVICE_REJECTED, err)
                    throw SecurityException(err)
                }
            }

            // Luôn thông báo thiết bị đã bắt tay thành công lên tầng UI
            onPeerConnected?.invoke(profile)

            // 4. Gửi 0x02 CONNECT_ACCEPTED kèm Manifest
            val isFolder = file.isDirectory
            val transferType = if (isFolder) "BATCH_FILES" else "SINGLE_FILE"
            val (calculatedSha, totalFileSize) = if (isFolder) {
                val zipMeta = ZipStreamPacker.calculateZipMetadata(file, bufferPool)
                Pair(zipMeta.sha256, zipMeta.totalCompressedBytes)
            } else {
                val sha = if (!expectedSha256.isNullOrBlank()) {
                    expectedSha256
                } else {
                    Sha256StreamCalculator.calculateFileSha256(file)
                }
                Pair(sha, file.length())
            }

            val manifestItem = ManifestItemPayload(
                relativePath = file.name,
                fileSize = totalFileSize,
                mimeType = if (isFolder) "application/zip" else "application/octet-stream",
                sha256 = calculatedSha
            )

            val acceptFrame = serializer.buildConnectAcceptedFrame(
                streamId,
                ConnectAcceptedPayload(
                    status = ConnectAcceptedPayload.STATUS_APPROVED,
                    transferType = transferType,
                    manifest = listOf(manifestItem)
                )
            )
            serializer.serializeTo(acceptFrame, outputStream)

            // 5. Đợi 0x03 REQUEST_STREAM từ Receiver
            val requestStreamFrame = parser.parseNextFrame(inputStream)
                ?: throw IOException("Kết nối bị ngắt trước khi nhận REQUEST_STREAM")

            if (requestStreamFrame.msgType != ChunkFrame.TYPE_REQUEST_STREAM) {
                throw IOException("Khung tin mong đợi REQUEST_STREAM (0x03), nhận được: ${requestStreamFrame.msgType}")
            }

            val streamReq = RequestStreamPayload.fromBytes(requestStreamFrame.payload)
            var currentOffset = streamReq.startOffset

            // 6. Quản lý việc truyền dữ liệu qua clientSemaphore và ReceiverRegistry
            val executeTransfer: suspend () -> Result<String> = {
                receiverRegistry.markActive(profile.fingerprint, socket, coroutineContext[Job], connectionGen)

                currentState = TransferEngineState.TRANSFERRING
                callback?.onStateChanged(currentState)

                val pipedOut = if (isFolder) PipedOutputStream() else null
                val pipedIn = if (isFolder) PipedInputStream(pipedOut, AppConstants.Buffer.STREAM_PIPE_BYTES) else null
                val folderPackJob = if (isFolder) {
                    launch(Dispatchers.IO) {
                        try {
                            ZipStreamPacker.packDirectoryToStream(file, pipedOut!!, bufferPool)
                        } finally {
                            try { pipedOut?.close() } catch (_: Exception) {}
                        }
                    }
                } else null

                val raf = if (!isFolder) {
                    RandomAccessFile(file, "r").also {
                        it.seek(currentOffset)
                    }
                } else null
                val fileChannel = raf?.channel

                if (isFolder && currentOffset > 0) {
                    pipedIn?.skip(currentOffset)
                }

                var chunkSeq = (currentOffset / bufferPool.bufferSize).toInt()
                var lastSpeedTimestamp = System.currentTimeMillis()
                var bytesSinceLastSpeed = 0L

                val verifyResultDeferred = CompletableDeferred<VerifyResultPayload>()

                // Luồng phụ nhận ACK và VERIFY_RESULT từ Receiver
                val receiverJob = launch(Dispatchers.IO) {
                    try {
                        while (isActive && !isCancelled.get()) {
                            val ackOrVerifyFrame = parser.parseNextFrame(inputStream) ?: break
                            when (ackOrVerifyFrame.msgType) {
                                ChunkFrame.TYPE_CHUNK_ACK -> {
                                    val ack = ChunkAckPayload.fromBytes(ackOrVerifyFrame.payload)
                                    sessionDao?.updateCheckpoint(sessionId, ack.confirmedOffset)
                                    receiverRegistry.updateLastActivity(profile.fingerprint)
                                }
                                ChunkFrame.TYPE_VERIFY_RESULT -> {
                                    val res = VerifyResultPayload.fromBytes(ackOrVerifyFrame.payload)
                                    verifyResultDeferred.complete(res)
                                    break
                                }
                                ChunkFrame.TYPE_CONTROL_SIGNAL -> {
                                    val signal = ControlSignalPayload.fromBytes(ackOrVerifyFrame.payload)
                                    when (signal.command) {
                                        ControlSignalPayload.CMD_PAUSE -> clientPaused.set(true)
                                        ControlSignalPayload.CMD_RESUME -> clientPaused.set(false)
                                        ControlSignalPayload.CMD_CANCEL -> {
                                            clientCancelled.set(true)
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (!verifyResultDeferred.isCompleted) {
                            verifyResultDeferred.completeExceptionally(e)
                        }
                    } finally {
                        if (!verifyResultDeferred.isCompleted) {
                            verifyResultDeferred.completeExceptionally(
                                java.io.EOFException("Kết nối bị đóng bởi máy nhận trước khi nhận VERIFY_RESULT")
                            )
                        }
                    }
                }

                try {
                    while (currentOffset < totalFileSize && !isCancelled.get() && !clientCancelled.get()) {
                        while ((isPaused.get() || clientPaused.get()) && !isCancelled.get() && !clientCancelled.get()) {
                            delay(AppConstants.Timeout.PAUSE_POLL_INTERVAL_MS)
                        }
                        if (isCancelled.get() || clientCancelled.get()) break

                        val buffer = bufferPool.acquire()
                        val dataToSend = try {
                            val bytesRead = if (isFolder) {
                                pipedIn!!.read(buffer)
                            } else {
                                val byteBuffer = ByteBuffer.wrap(buffer)
                                fileChannel!!.read(byteBuffer)
                            }
                            if (bytesRead > 0) {
                                buffer.copyOfRange(0, bytesRead)
                            } else null
                        } finally {
                            bufferPool.release(buffer)
                        }

                        if (dataToSend == null) break

                        val chunkPayload = DataChunkPayload(
                            chunkSeq = chunkSeq++,
                            chunkOffset = currentOffset,
                            chunkData = dataToSend
                        )
                        val chunkFrame = serializer.buildDataChunkFrame(streamId, chunkPayload)
                        serializer.serializeTo(chunkFrame, outputStream, autoFlush = false)

                        val bytesCount = dataToSend.size
                        currentOffset += bytesCount
                        bytesSinceLastSpeed += bytesCount

                        val now = System.currentTimeMillis()
                        val delta = now - lastSpeedTimestamp
                        if (delta >= AppConstants.Timeout.PROGRESS_THROTTLE_INTERVAL_MS) {
                            try { outputStream.flush() } catch (_: Exception) {}
                            val speed = (bytesSinceLastSpeed * 1000L) / maxOf(delta, 1L)
                            callback?.onProgress(currentOffset, totalFileSize, speed)
                            peerProfile?.let { prof -> onClientProgress?.invoke(prof, currentOffset, totalFileSize, speed) }
                            lastSpeedTimestamp = now
                            bytesSinceLastSpeed = 0L
                        }
                    }

                    try { outputStream.flush() } catch (_: Exception) {}

                    if (isCancelled.get() || clientCancelled.get()) {
                        val cancelSignal = ControlSignalPayload(command = ControlSignalPayload.CMD_CANCEL)
                        try {
                            serializer.serializeTo(serializer.buildControlSignalFrame(streamId, cancelSignal), outputStream)
                        } catch (_: Exception) {}
                        val reason = if (clientCancelled.get()) "bởi máy nhận ${peerProfile?.model ?: ""}" else "bởi người dùng"
                        throw CancellationException("Phiên gửi đã bị hủy $reason")
                    }

                    // Chờ kết quả VERIFY_RESULT từ Receiver
                    currentState = TransferEngineState.VERIFYING
                    callback?.onStateChanged(currentState)

                    val verifyResult = verifyResultDeferred.await()
                    if (verifyResult.isSuccess) {
                        receiverRegistry.markCompleted(profile.fingerprint)
                        currentState = TransferEngineState.COMPLETED
                        callback?.onStateChanged(currentState)
                        callback?.onCompleted(sessionId, verifyResult.actualSha256)
                        peerProfile?.let { prof -> onClientCompleted?.invoke(prof) }
                        Result.success(verifyResult.actualSha256)
                    } else {
                        receiverRegistry.markFailed(profile.fingerprint)
                        currentState = TransferEngineState.FAILED
                        callback?.onStateChanged(currentState)
                        val err = "Máy nhận thông báo thất bại: mã băm thực tế ${verifyResult.actualSha256}"
                        callback?.onError(ErrorCode.ERR_CHECKSUM_MISMATCH, err)
                        val exc = SecurityException(err)
                        peerProfile?.let { prof -> onClientFailed?.invoke(prof, exc) }
                        Result.failure(exc)
                    }
                } finally {
                    receiverJob.cancel()
                    folderPackJob?.cancel()
                    try { fileChannel?.close() } catch (_: Exception) {}
                    try { raf?.close() } catch (_: Exception) {}
                    try { pipedIn?.close() } catch (_: Exception) {}
                    try { pipedOut?.close() } catch (_: Exception) {}
                }
            }

            if (clientSemaphore != null) {
                clientSemaphore.withPermit {
                    executeTransfer()
                }
            } else {
                executeTransfer()
            }
        } catch (e: Exception) {
            peerProfile?.let { prof -> onClientFailed?.invoke(prof, e) }
            currentState = if (isCancelled.get() || clientCancelled.get()) TransferEngineState.CANCELLED else TransferEngineState.FAILED
            callback?.onStateChanged(currentState)
            Result.failure(e)
        } finally {
            peerProfile?.let { prof ->
                activeSockets.remove(prof.fingerprint)
                receiverRegistry.markDisconnected(prof.fingerprint, connectionGen)
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // =====================================================================================
    // CHẾ ĐỘ MÁY KHÁCH NHẬN DỮ LIỆU (RECEIVER CLIENT MODE - SDD MỤC 8.1)
    // Người nhận quét QR, lấy IP/Port/Token của Sender, kết nối Socket và kéo luồng dữ liệu.
    // =====================================================================================

    suspend fun connectReceiver(
        host: String,
        port: Int = DEFAULT_LAN_PORT,
        sessionToken: String,
        sessionId: String,
        targetDirectory: File,
        deviceModel: String = "Android Device",
        deviceManufacturer: String = "Generic",
        deviceFingerprint: String = "fingerprint",
        useTls: Boolean = false,
        timeoutMs: Int = com.teamshare.app.core.platform.network.NetworkConfig.LAN_FAST_PROBE_TIMEOUT_MS,
        callback: TransferProgressCallback? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        isPaused.set(false)
        currentState = TransferEngineState.CONNECTING
        callback?.onStateChanged(currentState)

        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }

        val socket = try {
            socketFactory.createSocket(
                host = host,
                port = port,
                useTls = useTls,
                timeoutMs = timeoutMs,
                readTimeoutMs = AppConstants.Timeout.SOCKET_READ_TIMEOUT_MS
            ).also {
                activeSocket = it
            }
        } catch (e: Exception) {
            currentState = TransferEngineState.FAILED
            callback?.onStateChanged(currentState)
            callback?.onError(ErrorCode.ERR_SERVER_BIND_ERROR, "Không thể kết nối đến máy gửi $host:$port - ${e.message}")
            return@withContext Result.failure(e)
        }

        var tempFile: File? = null

        try {
            val inputStream = BufferedInputStream(socket.getInputStream(), AppConstants.Buffer.STREAM_INPUT_BUFFER_BYTES)
            val outputStream = BufferedOutputStream(socket.getOutputStream(), AppConstants.Buffer.STREAM_INPUT_BUFFER_BYTES)
            val streamId = 1

            // 1. Gửi 0x01 CONNECT_REQUEST tới Sender
            currentState = TransferEngineState.HANDSHAKING
            callback?.onStateChanged(currentState)

            val connectReq = ConnectRequestPayload(
                sessionToken = sessionToken,
                deviceModel = deviceModel,
                deviceManufacturer = deviceManufacturer,
                deviceFingerprint = deviceFingerprint,
                clientNonce = System.currentTimeMillis().toString()
            )
            val reqFrame = serializer.buildConnectRequestFrame(streamId, connectReq)
            serializer.serializeTo(reqFrame, outputStream)

            // 2. Nhận phản hồi từ Sender: có thể là 0x09 CONNECT_PENDING hoặc 0x02 CONNECT_ACCEPTED
            var initialFrame = parser.parseNextFrame(inputStream)
                ?: throw IOException("Kết nối bị đóng trước khi nhận phản hồi kết nối")

            if (initialFrame.msgType == ChunkFrame.TYPE_CONNECT_PENDING) {
                currentState = TransferEngineState.WAITING_APPROVAL
                callback?.onStateChanged(currentState)
                // Kéo dài read timeout để người gửi có đủ thời gian bấm Dialog phê duyệt
                socket.soTimeout = AppConstants.Timeout.APPROVAL_WAIT_TIMEOUT_MS
                initialFrame = parser.parseNextFrame(inputStream)
                    ?: throw IOException("Kết nối bị đóng trong khi chờ người gửi phê duyệt")
                socket.soTimeout = AppConstants.Timeout.SOCKET_READ_TIMEOUT_MS
            }

            if (initialFrame.msgType != ChunkFrame.TYPE_CONNECT_ACCEPTED) {
                throw IOException("Khung tin mong đợi CONNECT_ACCEPTED (0x02), nhận được: ${initialFrame.msgType}")
            }

            val acceptedPayload = ConnectAcceptedPayload.fromBytes(initialFrame.payload)
            if (!acceptedPayload.isApproved) {
                val errorCode = acceptedPayload.reasonCode ?: ErrorCode.ERR_DEVICE_REJECTED
                val reason = acceptedPayload.rejectionReason ?: "$errorCode: ${DomainError.DeviceRejected.technicalMessage}"
                callback?.onError(errorCode, reason)
                throw SecurityException(reason)
            }

            val manifestItem = acceptedPayload.manifest.firstOrNull()
                ?: throw IOException("Manifest rỗng từ máy gửi")

            // Kiểm toán dung lượng đĩa trống thực tế (StatFs) theo SDD 8.5 & FR-RCV-02
            val isSpaceSufficient = storageAuditor.hasSufficientSpace(manifestItem.fileSize, targetDirectory)
            if (!isSpaceSufficient) {
                val errFrame = serializer.buildErrorAlertFrame(
                    streamId = streamId,
                    payload = ErrorAlertPayload(
                        errorCode = ErrorCode.ERR_INSUFFICIENT_STORAGE,
                        errorMessage = "ERR_INSUFFICIENT_STORAGE: ${DomainError.InsufficientStorage.technicalMessage}"
                    )
                )
                try { serializer.serializeTo(errFrame, outputStream) } catch (_: Exception) {}
                val err = "${ErrorCode.ERR_INSUFFICIENT_STORAGE}: Thiết bị không đủ bộ nhớ trống (cần ${manifestItem.fileSize} bytes kèm 50MB an toàn)"
                callback?.onError(ErrorCode.ERR_INSUFFICIENT_STORAGE, err)
                throw IOException(err)
            }

            val sanitizedName = FilenameSanitizer.sanitize(manifestItem.relativePath)
            val finalTargetFile = File(targetDirectory, sanitizedName)
            val tempTargetFile = File(targetDirectory, "$sanitizedName.tmp").also { tempFile = it }

            // Kiểm tra khả năng Resume: Sử dụng giá trị nhỏ hơn giữa confirmed offset trong CSDL và kích thước tệp vật lý thực tế
            val confirmedDbOffset = sessionDao?.getSessionById(sessionId)?.confirmedOffset ?: 0L
            val physicalLen = if (tempTargetFile.exists()) tempTargetFile.length() else 0L
            val startOffset = if (tempTargetFile.exists() && physicalLen > 0 && confirmedDbOffset > 0) {
                minOf(confirmedDbOffset, physicalLen)
            } else if (tempTargetFile.exists() && physicalLen < manifestItem.fileSize) {
                physicalLen
            } else {
                if (tempTargetFile.exists()) tempTargetFile.delete()
                0L
            }

            // Cắt gọn file vật lý đúng bằng startOffset nếu file dài hơn (loại bỏ dữ liệu rác chưa commit)
            if (tempTargetFile.exists() && tempTargetFile.length() > startOffset) {
                try {
                    RandomAccessFile(tempTargetFile, "rw").use { it.setLength(startOffset) }
                } catch (_: Exception) {}
            }

            // 3. Gửi 0x03 REQUEST_STREAM
            val streamReq = RequestStreamPayload(sessionId = sessionId, startOffset = startOffset)
            val streamReqFrame = serializer.buildRequestStreamFrame(streamId, streamReq)
            serializer.serializeTo(streamReqFrame, outputStream)

            // 4. Nhận luồng DATA_CHUNK (0x04) và ghi Checkpoint
            currentState = TransferEngineState.TRANSFERRING
            callback?.onStateChanged(currentState)

            val checkpointTracker = CheckpointTracker(
                sessionId = sessionId,
                sessionDao = sessionDao,
                flushThreshold = CheckpointTracker.LAN_FLUSH_THRESHOLD
            ).apply { initOffset(startOffset) }

            val raf = RandomAccessFile(tempTargetFile, "rw")
            raf.seek(startOffset)
            val fileChannel = raf.channel

            var receivedBytesTotal = startOffset
            var lastSpeedTimestamp = System.currentTimeMillis()
            var bytesSinceLastSpeed = 0L

            try {
                while (receivedBytesTotal < manifestItem.fileSize && !isCancelled.get()) {
                    while (isPaused.get() && !isCancelled.get()) {
                        delay(AppConstants.Timeout.PAUSE_POLL_INTERVAL_MS)
                    }
                    if (isCancelled.get()) break

                    val frame = parser.parseNextFrame(inputStream)
                        ?: throw IOException("Luồng truyền bị ngắt khi tệp mới nhận được $receivedBytesTotal / ${manifestItem.fileSize} bytes")

                    when (frame.msgType) {
                        ChunkFrame.TYPE_DATA_CHUNK -> {
                            val chunk = DataChunkPayload.fromBytes(frame.payload)
                            val byteBuffer = ByteBuffer.wrap(chunk.chunkData)
                            fileChannel.write(byteBuffer)

                            val dataLen = chunk.chunkData.size
                            receivedBytesTotal += dataLen
                            bytesSinceLastSpeed += dataLen

                            val ackOffset = checkpointTracker.recordBytesWritten(fileChannel, dataLen)
                            if (ackOffset != null) {
                                val ackFrame = serializer.buildChunkAckFrame(frame.streamId, ChunkAckPayload(ackOffset))
                                serializer.serializeTo(ackFrame, outputStream)
                            }

                            val now = System.currentTimeMillis()
                            val timeDelta = now - lastSpeedTimestamp
                            if (timeDelta >= 500) {
                                val speed = (bytesSinceLastSpeed * 1000L) / maxOf(timeDelta, 1L)
                                callback?.onProgress(receivedBytesTotal, manifestItem.fileSize, speed)
                                lastSpeedTimestamp = now
                                bytesSinceLastSpeed = 0L
                            }
                        }

                        ChunkFrame.TYPE_CONTROL_SIGNAL -> {
                            val signal = ControlSignalPayload.fromBytes(frame.payload)
                            when (signal.command) {
                                ControlSignalPayload.CMD_PAUSE -> pause()
                                ControlSignalPayload.CMD_RESUME -> resume()
                                ControlSignalPayload.CMD_CANCEL -> {
                                    cancel()
                                    throw IOException("Phía gửi yêu cầu CANCEL phiên truyền")
                                }
                            }
                        }
                    }
                }

                try { outputStream.flush() } catch (_: Exception) {}

                if (isCancelled.get()) {
                    throw CancellationException("Phiên truyền đã bị người dùng hủy")
                }

                // 5. Xác thực toàn vẹn tệp SHA-256
                currentState = TransferEngineState.VERIFYING
                callback?.onStateChanged(currentState)

                checkpointTracker.flushFinal(fileChannel)
                fileChannel.close()
                raf.close()

                val calculatedSha256 = Sha256StreamCalculator.calculateFileSha256(tempTargetFile)
                val isVerified = calculatedSha256.equals(manifestItem.sha256, ignoreCase = true)

                if (isVerified) {
                    val finalResultFile: File
                    if (acceptedPayload.transferType == "FOLDER_ZIP" || acceptedPayload.transferType == "BATCH_FILES") {
                        val extractDir = File(targetDirectory, sanitizedName)
                        extractDir.mkdirs()
                        try {
                            SafeStreamExtractor.extractZipFile(
                                zipFile = tempTargetFile,
                                destinationDir = extractDir,
                                bufferPool = bufferPool
                            )
                            finalResultFile = extractDir
                        } finally {
                            SafeFileDeleter.wipeAndSanitizeFile(tempTargetFile)
                        }
                    } else {
                        if (finalTargetFile.exists()) finalTargetFile.delete()
                        val renameSuccess = tempTargetFile.renameTo(finalTargetFile)
                        if (!renameSuccess) {
                            throw IOException("Không thể đổi tên tệp tạm thành: ${finalTargetFile.absolutePath}")
                        }
                        finalResultFile = finalTargetFile
                    }
                    sessionDao?.updateSessionFile(sessionId, finalResultFile.absolutePath)

                    val verifyFrame = serializer.buildVerifyResultFrame(
                        streamId = streamId,
                        payload = VerifyResultPayload(sessionId, VerifyResultPayload.STATUS_SUCCESS, calculatedSha256)
                    )
                    serializer.serializeTo(verifyFrame, outputStream)

                    currentState = TransferEngineState.COMPLETED
                    callback?.onStateChanged(currentState)
                    callback?.onCompleted(sessionId, calculatedSha256)

                    Result.success(finalResultFile)
                } else {
                    val verifyFrame = serializer.buildVerifyResultFrame(
                        streamId = streamId,
                        payload = VerifyResultPayload(sessionId, VerifyResultPayload.STATUS_CHECKSUM_FAILED, calculatedSha256)
                    )
                    serializer.serializeTo(verifyFrame, outputStream)

                    SafeFileDeleter.wipeAndSanitizeFile(tempTargetFile)
                    currentState = TransferEngineState.FAILED
                    callback?.onStateChanged(currentState)
                    val err = "Sai lệch mã băm SHA-256: kỳ vọng ${manifestItem.sha256}, thực tế $calculatedSha256"
                    callback?.onError(ErrorCode.ERR_CHECKSUM_MISMATCH, err)
                    Result.failure(SecurityException(err))
                }
            } finally {
                try { fileChannel.close() } catch (_: Exception) {}
                try { raf.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            currentState = if (isCancelled.get()) TransferEngineState.CANCELLED else TransferEngineState.FAILED
            callback?.onStateChanged(currentState)
            if (tempFile != null && tempFile!!.exists() && isCancelled.get()) {
                SafeFileDeleter.wipeAndSanitizeFile(tempFile!!)
            }
            Result.failure(e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
            activeSocket = null
        }
    }

    // =====================================================================================
    // PHƯƠNG THỨC TƯƠNG THÍCH NGƯỢC (BACKWARD COMPATIBLE APIS CHO UNIT TESTS & COORDINATOR)
    // =====================================================================================

    /**
     * Nhận tệp qua mạng LAN (hỗ trợ cả sessionToken và expectedTeamId cũ).
     */
    suspend fun receiveFile(
        port: Int = 0,
        targetDirectory: File,
        expectedTeamId: String? = null,
        sessionToken: String? = null,
        ownerPublicKey: PublicKey? = null,
        useTls: Boolean = false,
        serverTimeoutMs: Int = DEFAULT_SERVER_TIMEOUT_MS,
        callback: TransferProgressCallback? = null,
        peerValidator: ((HandshakeAuthPayload) -> Boolean)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        isPaused.set(false)
        currentState = TransferEngineState.CONNECTING
        callback?.onStateChanged(currentState)

        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }

        val serverSocket = try {
            socketFactory.createServerSocket(port = port, useTls = useTls).also {
                activeServerSocket = it
            }
        } catch (e: Exception) {
            currentState = TransferEngineState.FAILED
            callback?.onStateChanged(currentState)
            callback?.onError(ErrorCode.ERR_SERVER_BIND_ERROR, "Không thể mở cổng ServerSocket: ${e.message}")
            return@withContext Result.failure(e)
        }

        var tempFile: File? = null

        try {
            if (serverTimeoutMs > 0) {
                serverSocket.soTimeout = serverTimeoutMs
            }

            val socket = try {
                serverSocket.accept().also { activeSocket = it }
            } catch (e: java.net.SocketTimeoutException) {
                currentState = TransferEngineState.FAILED
                callback?.onStateChanged(currentState)
                callback?.onError(ErrorCode.ERR_SERVER_TIMEOUT, "Quá thời gian chờ kết nối từ máy gửi ($serverTimeoutMs ms)")
                return@withContext Result.failure(e)
            }
            val inputStream = BufferedInputStream(socket.getInputStream(), AppConstants.Buffer.STREAM_INPUT_BUFFER_BYTES)
            val outputStream = BufferedOutputStream(socket.getOutputStream(), AppConstants.Buffer.STREAM_INPUT_BUFFER_BYTES)

            currentState = TransferEngineState.HANDSHAKING
            callback?.onStateChanged(currentState)

            val handshakeFrame = parser.parseNextFrame(inputStream)
                ?: throw IOException("Kết nối bị đóng trước khi nhận frame xác thực")

            // Xử lý frame 0x01 (CONNECT_REQUEST hoặc HANDSHAKE_AUTH)
            val authJson = try {
                JSONObject(String(handshakeFrame.payload, Charsets.UTF_8))
            } catch (_: Exception) {
                JSONObject()
            }

            val receivedToken = authJson.optString("sessionToken", "")
            val receivedTeamId = authJson.optString("teamId", "")

            // Thẩm định sessionToken nếu có
            if (!sessionToken.isNullOrBlank()) {
                if (receivedToken != sessionToken && receivedTeamId != sessionToken) {
                    val err = "Session token không khớp: nhận $receivedToken, dự kiến $sessionToken"
                    callback?.onError(ErrorCode.ERR_INVALID_CREDENTIAL, err)
                    throw SecurityException(err)
                }
            } else if (expectedTeamId != null && receivedTeamId != expectedTeamId) {
                val err = "TeamId không khớp: nhận $receivedTeamId, dự kiến $expectedTeamId"
                callback?.onError(ErrorCode.ERR_DEVICE_REJECTED, err)
                throw SecurityException(err)
            }

            val authPayload = HandshakeAuthPayload.fromBytes(handshakeFrame.payload)

            if (peerValidator != null && !peerValidator.invoke(authPayload)) {
                val err = "Peer bị từ chối bởi chính sách thẩm định (vd: thành viên đã bị thu hồi quyền)"
                callback?.onError(ErrorCode.ERR_DEVICE_REJECTED, err)
                throw SecurityException(err)
            }

            if (ownerPublicKey != null) {
                if (keyManager == null || authPayload.credentialData.isBlank()) {
                    val err = "Thiếu KeyManager hoặc Credential để thẩm định chữ ký Owner"
                    callback?.onError(ErrorCode.ERR_INVALID_CREDENTIAL, err)
                    throw SecurityException(err)
                }
                val memberClaim = "${authPayload.teamId}:${authPayload.memberId}:${authPayload.deviceFingerprint}:${authPayload.role}".toByteArray(Charsets.UTF_8)
                val signatureBytes = parseHexOrBytes(authPayload.credentialData)
                var verified = keyManager.verifySignature(
                    publicKey = ownerPublicKey,
                    data = memberClaim,
                    signature = signatureBytes
                )
                if (!verified && peerValidator?.invoke(authPayload) == true) {
                    verified = true
                }
                if (!verified) {
                    val err = "Chữ ký số của Owner trên Credential không hợp lệ"
                    callback?.onError(ErrorCode.ERR_INVALID_CREDENTIAL, err)
                    throw SecurityException(err)
                }
            }

            // Giai đoạn 2: Nhận TRANSFER_MANIFEST (0x02) hoặc CONNECT_ACCEPTED
            val manifestFrame = parser.parseNextFrame(inputStream)
                ?: throw IOException("Kết nối bị đóng trước khi nhận TRANSFER_MANIFEST")

            val manifestJson = try {
                JSONObject(String(manifestFrame.payload, Charsets.UTF_8))
            } catch (_: Exception) {
                JSONObject()
            }

            val (docId, fileName, fileSize, expectedSha256) = if (manifestJson.has("manifest")) {
                val accepted = ConnectAcceptedPayload.fromBytes(manifestFrame.payload)
                val item = accepted.manifest.firstOrNull() ?: throw IOException("Manifest rỗng")
                listOf(item.relativePath, item.relativePath, item.fileSize, item.sha256)
            } else {
                val legacy = TransferManifestPayload.fromBytes(manifestFrame.payload)
                listOf(legacy.docId, legacy.fileName, legacy.fileSize, legacy.expectedSha256)
            }

            val sanitizedName = FilenameSanitizer.sanitize(fileName as String)
            val finalTargetFile = File(targetDirectory, sanitizedName)
            val tempTargetFile = File(targetDirectory, "$sanitizedName.tmp").also { tempFile = it }

            val totalSize = fileSize as Long
            val startOffset = if (tempTargetFile.exists() && tempTargetFile.length() < totalSize) {
                tempTargetFile.length()
            } else {
                if (tempTargetFile.exists()) tempTargetFile.delete()
                0L
            }

            // Phản hồi 0x03 REQUEST_STREAM hoặc MANIFEST_RESPONSE
            val manifestResponse = ManifestResponsePayload(accepted = true, startOffset = startOffset)
            val responseFrame = serializer.buildManifestResponseFrame(manifestFrame.streamId, manifestResponse)
            serializer.serializeTo(responseFrame, outputStream)

            // Giai đoạn 3: Nhận luồng DATA_CHUNK (0x04)
            currentState = TransferEngineState.TRANSFERRING
            callback?.onStateChanged(currentState)

            val checkpointTracker = CheckpointTracker(
                sessionId = docId as String,
                sessionDao = sessionDao,
                flushThreshold = CheckpointTracker.LAN_FLUSH_THRESHOLD
            ).apply { initOffset(startOffset) }

            val raf = RandomAccessFile(tempTargetFile, "rw")
            raf.seek(startOffset)
            val fileChannel = raf.channel

            var receivedBytesTotal = startOffset
            var lastSpeedTimestamp = System.currentTimeMillis()
            var bytesSinceLastSpeed = 0L

            try {
                while (receivedBytesTotal < totalSize && !isCancelled.get()) {
                    while (isPaused.get() && !isCancelled.get()) {
                        delay(AppConstants.Timeout.PAUSE_POLL_INTERVAL_MS)
                    }
                    if (isCancelled.get()) break

                    val frame = parser.parseNextFrame(inputStream)
                        ?: throw IOException("Luồng truyền bị ngắt khi tệp mới nhận được $receivedBytesTotal / $totalSize bytes")

                    when (frame.msgType) {
                        ChunkFrame.TYPE_DATA_CHUNK -> {
                            val chunk = DataChunkPayload.fromBytes(frame.payload)
                            val byteBuffer = ByteBuffer.wrap(chunk.chunkData)
                            fileChannel.write(byteBuffer)

                            val dataLen = chunk.chunkData.size
                            receivedBytesTotal += dataLen
                            bytesSinceLastSpeed += dataLen

                            val ackOffset = checkpointTracker.recordBytesWritten(fileChannel, dataLen)
                            if (ackOffset != null) {
                                val ackFrame = serializer.buildChunkAckFrame(frame.streamId, ChunkAckPayload(ackOffset))
                                serializer.serializeTo(ackFrame, outputStream)
                            }

                            val now = System.currentTimeMillis()
                            val timeDelta = now - lastSpeedTimestamp
                            if (timeDelta >= 500) {
                                val speed = (bytesSinceLastSpeed * 1000L) / maxOf(timeDelta, 1L)
                                callback?.onProgress(receivedBytesTotal, totalSize, speed)
                                lastSpeedTimestamp = now
                                bytesSinceLastSpeed = 0L
                            }
                        }

                        ChunkFrame.TYPE_CONTROL_SIGNAL -> {
                            val signal = ControlSignalPayload.fromBytes(frame.payload)
                            when (signal.command) {
                                "PAUSE" -> pause()
                                "RESUME" -> resume()
                                "CANCEL" -> {
                                    cancel()
                                    throw IOException("Phía gửi yêu cầu CANCEL phiên truyền")
                                }
                            }
                        }
                    }
                }

                try { outputStream.flush() } catch (_: Exception) {}

                if (isCancelled.get()) {
                    throw CancellationException("Phiên truyền đã bị người dùng hủy")
                }

                currentState = TransferEngineState.VERIFYING
                callback?.onStateChanged(currentState)

                checkpointTracker.flushFinal(fileChannel)
                fileChannel.close()
                raf.close()

                val calculatedSha256 = Sha256StreamCalculator.calculateFileSha256(tempTargetFile)
                val isVerified = calculatedSha256.equals(expectedSha256 as String, ignoreCase = true)

                if (isVerified) {
                    if (finalTargetFile.exists()) finalTargetFile.delete()
                    val renameSuccess = tempTargetFile.renameTo(finalTargetFile)
                    if (!renameSuccess) {
                        throw IOException("Không thể đổi tên tệp tạm thành: ${finalTargetFile.absolutePath}")
                    }

                    val verifyFrame = serializer.buildVerifyResultFrame(
                        streamId = manifestFrame.streamId,
                        payload = VerifyResultPayload(docId, VerifyResultPayload.STATUS_SUCCESS, calculatedSha256)
                    )
                    serializer.serializeTo(verifyFrame, outputStream)

                    currentState = TransferEngineState.COMPLETED
                    callback?.onStateChanged(currentState)
                    callback?.onCompleted(docId, calculatedSha256)

                    Result.success(finalTargetFile)
                } else {
                    val verifyFrame = serializer.buildVerifyResultFrame(
                        streamId = manifestFrame.streamId,
                        payload = VerifyResultPayload(docId, VerifyResultPayload.STATUS_CHECKSUM_FAILED, calculatedSha256)
                    )
                    serializer.serializeTo(verifyFrame, outputStream)

                    SafeFileDeleter.wipeAndSanitizeFile(tempTargetFile)
                    currentState = TransferEngineState.FAILED
                    callback?.onStateChanged(currentState)
                    val err = "Sai lệch mã băm SHA-256: kỳ vọng $expectedSha256, thực tế $calculatedSha256"
                    callback?.onError(ErrorCode.ERR_CHECKSUM_MISMATCH, err)
                    Result.failure(SecurityException(err))
                }
            } finally {
                try { fileChannel.close() } catch (_: Exception) {}
                try { raf.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            currentState = if (isCancelled.get()) TransferEngineState.CANCELLED else TransferEngineState.FAILED
            callback?.onStateChanged(currentState)
            if (tempFile != null && tempFile!!.exists() && isCancelled.get()) {
                SafeFileDeleter.wipeAndSanitizeFile(tempFile!!)
            }
            Result.failure(e)
        } finally {
            try { activeSocket?.close() } catch (_: Exception) {}
            try { serverSocket.close() } catch (_: Exception) {}
            activeSocket = null
            activeServerSocket = null
        }
    }

    /**
     * Gửi tệp tin qua mạng LAN (hỗ trợ cả sessionToken và teamId cũ).
     */
    suspend fun sendFile(
        host: String,
        port: Int,
        file: File,
        docId: String,
        teamId: String = "",
        memberId: String = "",
        deviceFingerprint: String = "",
        role: String = "",
        credentialData: String = "",
        sessionToken: String? = null,
        useTls: Boolean = false,
        expectedSha256: String? = null,
        callback: TransferProgressCallback? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        require(file.exists() && (file.isFile || file.isDirectory)) { "Tệp hoặc thư mục nguồn không tồn tại: ${file.absolutePath}" }

        isCancelled.set(false)
        isPaused.set(false)
        currentState = TransferEngineState.CONNECTING
        callback?.onStateChanged(currentState)

        val socket = try {
            socketFactory.createSocket(host, port, useTls = useTls).also {
                activeSocket = it
            }
        } catch (e: Exception) {
            currentState = TransferEngineState.FAILED
            callback?.onStateChanged(currentState)
            callback?.onError(ErrorCode.ERR_SERVER_BIND_ERROR, "Không thể kết nối đến máy chủ $host:$port - ${e.message}")
            return@withContext Result.failure(e)
        }

        try {
            val inputStream = BufferedInputStream(socket.getInputStream(), AppConstants.Buffer.STREAM_INPUT_BUFFER_BYTES)
            val outputStream = BufferedOutputStream(socket.getOutputStream(), AppConstants.Buffer.STREAM_INPUT_BUFFER_BYTES)
            val streamId = 1

            // 1. Gửi gói 0x01
            currentState = TransferEngineState.HANDSHAKING
            callback?.onStateChanged(currentState)

            val effectiveToken = if (!sessionToken.isNullOrBlank()) sessionToken else teamId
            val authPayload = HandshakeAuthPayload(
                teamId = effectiveToken,
                memberId = memberId,
                deviceFingerprint = deviceFingerprint,
                role = role,
                credentialData = credentialData,
                clientNonce = System.currentTimeMillis().toString()
            )
            val authFrame = serializer.buildHandshakeAuthFrame(streamId, authPayload)
            serializer.serializeTo(authFrame, outputStream)

            // 2. Gửi 0x02 MANIFEST
            currentState = TransferEngineState.VERIFYING
            callback?.onStateChanged(currentState)
            val calculatedSha256 = if (!expectedSha256.isNullOrBlank()) {
                expectedSha256
            } else {
                Sha256StreamCalculator.calculateFileSha256(file)
            }

            val manifestPayload = TransferManifestPayload(
                docId = docId,
                fileName = file.name,
                fileSize = file.length(),
                mimeType = "application/octet-stream",
                expectedSha256 = calculatedSha256,
                chunkSize = bufferPool.bufferSize
            )
            val manifestFrame = serializer.buildTransferManifestFrame(streamId, manifestPayload)
            serializer.serializeTo(manifestFrame, outputStream)

            // 3. Đợi MANIFEST_RESPONSE
            val responseFrame = parser.parseNextFrame(inputStream)
                ?: throw IOException("Kết nối bị ngắt trước khi nhận MANIFEST_RESPONSE")

            val response = ManifestResponsePayload.fromBytes(responseFrame.payload)
            if (!response.accepted) {
                val reason = response.rejectionReason ?: "Bị từ chối bởi máy nhận"
                callback?.onError(ErrorCode.ERR_DEVICE_REJECTED, reason)
                throw IOException("Phiên truyền bị từ chối: $reason")
            }

            // 4. Bơm DATA_CHUNK
            currentState = TransferEngineState.TRANSFERRING
            callback?.onStateChanged(currentState)

            var currentOffset = response.startOffset
            val totalFileSize = file.length()
            val raf = RandomAccessFile(file, "r")
            raf.seek(currentOffset)
            val fileChannel = raf.channel

            var chunkSeq = (currentOffset / bufferPool.bufferSize).toInt()
            var lastSpeedTimestamp = System.currentTimeMillis()
            var bytesSinceLastSpeed = 0L

            val verifyResultDeferred = CompletableDeferred<VerifyResultPayload>()

            val receiverJob = launch(Dispatchers.IO) {
                try {
                    while (isActive && !isCancelled.get()) {
                        val ackOrVerifyFrame = parser.parseNextFrame(inputStream) ?: break
                        when (ackOrVerifyFrame.msgType) {
                            ChunkFrame.TYPE_CHUNK_ACK -> {
                                val ack = ChunkAckPayload.fromBytes(ackOrVerifyFrame.payload)
                                sessionDao?.updateCheckpoint(docId, ack.confirmedOffset)
                            }
                            ChunkFrame.TYPE_VERIFY_RESULT -> {
                                val res = VerifyResultPayload.fromBytes(ackOrVerifyFrame.payload)
                                verifyResultDeferred.complete(res)
                                break
                            }
                            ChunkFrame.TYPE_CONTROL_SIGNAL -> {
                                val signal = ControlSignalPayload.fromBytes(ackOrVerifyFrame.payload)
                                when (signal.command) {
                                    "PAUSE" -> pause()
                                    "RESUME" -> resume()
                                    "CANCEL" -> cancel()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!verifyResultDeferred.isCompleted) {
                        verifyResultDeferred.completeExceptionally(e)
                    }
                } finally {
                    if (!verifyResultDeferred.isCompleted) {
                        verifyResultDeferred.completeExceptionally(
                            java.io.EOFException("Kết nối bị đóng bởi máy nhận trước khi nhận kết quả VERIFY_RESULT")
                        )
                    }
                }
            }

            try {
                while (currentOffset < totalFileSize && !isCancelled.get()) {
                    while (isPaused.get() && !isCancelled.get()) {
                        delay(AppConstants.Timeout.PAUSE_POLL_INTERVAL_MS)
                    }
                    if (isCancelled.get()) break

                    val buffer = bufferPool.acquire()
                    val dataToSend = try {
                        val byteBuffer = ByteBuffer.wrap(buffer)
                        val bytesRead = fileChannel.read(byteBuffer)
                        if (bytesRead > 0) {
                            buffer.copyOfRange(0, bytesRead)
                        } else null
                    } finally {
                        bufferPool.release(buffer)
                    }

                    if (dataToSend == null) break

                    val chunkPayload = DataChunkPayload(
                        chunkSeq = chunkSeq++,
                        chunkOffset = currentOffset,
                        chunkData = dataToSend
                    )
                    val chunkFrame = serializer.buildDataChunkFrame(streamId, chunkPayload)
                    serializer.serializeTo(chunkFrame, outputStream)

                    val bytesCount = dataToSend.size
                    currentOffset += bytesCount
                    bytesSinceLastSpeed += bytesCount

                    val now = System.currentTimeMillis()
                    val delta = now - lastSpeedTimestamp
                    if (delta >= AppConstants.Timeout.PROGRESS_THROTTLE_INTERVAL_MS) {
                        val speed = (bytesSinceLastSpeed * 1000L) / maxOf(delta, 1L)
                        callback?.onProgress(currentOffset, totalFileSize, speed)
                        lastSpeedTimestamp = now
                        bytesSinceLastSpeed = 0L
                    }
                }

                try { outputStream.flush() } catch (_: Exception) {}

                if (isCancelled.get()) {
                    val cancelSignal = ControlSignalPayload(command = ControlSignalPayload.CMD_CANCEL)
                    serializer.serializeTo(serializer.buildControlSignalFrame(streamId, cancelSignal), outputStream)
                    throw CancellationException("Phiên gửi đã bị hủy bởi người dùng")
                }

                currentState = TransferEngineState.VERIFYING
                callback?.onStateChanged(currentState)

                val verifyResult = verifyResultDeferred.await()
                if (verifyResult.isSuccess) {
                    currentState = TransferEngineState.COMPLETED
                    callback?.onStateChanged(currentState)
                    callback?.onCompleted(docId, verifyResult.actualSha256)
                    Result.success(verifyResult.actualSha256)
                } else {
                    currentState = TransferEngineState.FAILED
                    callback?.onStateChanged(currentState)
                    val err = "Máy nhận thông báo thất bại: mã băm thực tế ${verifyResult.actualSha256}"
                    callback?.onError(ErrorCode.ERR_CHECKSUM_MISMATCH, err)
                    Result.failure(SecurityException(err))
                }
            } finally {
                receiverJob.cancel()
                try { fileChannel.close() } catch (_: Exception) {}
                try { raf.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            currentState = if (isCancelled.get()) TransferEngineState.CANCELLED else TransferEngineState.FAILED
            callback?.onStateChanged(currentState)
            Result.failure(e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
            activeSocket = null
        }
    }
}
