package com.teamshare.app.core.transfer.lan

import com.teamshare.app.core.network.tls.TlsSocketFactory
import com.teamshare.app.core.protocol.BinaryFrameParser
import com.teamshare.app.core.protocol.ChunkAckPayload
import com.teamshare.app.core.protocol.ChunkFrame
import com.teamshare.app.core.protocol.ConnectAcceptedPayload
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
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.core.security.sanitizer.FilenameSanitizer
import com.teamshare.app.core.storage.saf.SafeFileDeleter
import com.teamshare.app.core.transfer.buffer.BufferPoolManager
import com.teamshare.app.core.transfer.checkpoint.CheckpointTracker
import com.teamshare.app.data.local.db.dao.TransferSessionDao
import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.DeviceProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
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
    private val sessionDao: TransferSessionDao? = null
) {
    companion object {
        const val DEFAULT_SERVER_TIMEOUT_MS = 60_000 // 60 giây chờ kết nối
        const val DEFAULT_LAN_PORT = 8889
    }

    private var activeServerSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
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
        try {
            activeServerSocket?.close()
        } catch (_: Exception) {}
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
        useTls: Boolean = false,
        serverTimeoutMs: Int = DEFAULT_SERVER_TIMEOUT_MS,
        callback: TransferProgressCallback? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        require(file.exists() && file.isFile) { "Tệp nguồn không tồn tại: ${file.absolutePath}" }

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
            callback?.onError("SERVER_BIND_ERROR", "Không thể mở ServerSocket tại cổng $port: ${e.message}")
            return@withContext Result.failure(e)
        }

        try {
            if (serverTimeoutMs > 0) {
                serverSocket.soTimeout = serverTimeoutMs
            }

            val socket = try {
                serverSocket.accept().also { activeSocket = it }
            } catch (e: java.net.SocketTimeoutException) {
                currentState = TransferEngineState.FAILED
                callback?.onStateChanged(currentState)
                callback?.onError("SERVER_TIMEOUT", "Hết thời gian chờ kết nối từ máy nhận")
                return@withContext Result.failure(e)
            }

            val inputStream = BufferedInputStream(socket.getInputStream(), 128 * 1024)
            val outputStream = BufferedOutputStream(socket.getOutputStream(), 128 * 1024)
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
                        rejectionReason = "0x101: Session token không hợp lệ"
                    )
                )
                serializer.serializeTo(rejectFrame, outputStream)
                val err = "Session token không khớp: nhận được ${connectReq.sessionToken}"
                callback?.onError("0x101", err)
                throw SecurityException(err)
            }

            // Phê duyệt thiết bị (Auto / Manual Dialog theo SDD 7.4 & 8.1)
            val peerProfile = DeviceProfile(
                manufacturer = connectReq.deviceManufacturer,
                model = connectReq.deviceModel,
                fingerprint = connectReq.deviceFingerprint
            )

            if (approvalMode == ApprovalMode.MANUAL_APPROVAL && onDeviceApproval != null) {
                val isApproved = onDeviceApproval.invoke(peerProfile)
                if (!isApproved) {
                    val rejectFrame = serializer.buildConnectAcceptedFrame(
                        streamId,
                        ConnectAcceptedPayload(
                            status = ConnectAcceptedPayload.STATUS_REJECTED,
                            rejectionReason = "0x102: Người gửi từ chối kết nối thiết bị"
                        )
                    )
                    serializer.serializeTo(rejectFrame, outputStream)
                    val err = "Người gửi từ chối kết nối từ thiết bị ${peerProfile.model}"
                    callback?.onError("0x102", err)
                    throw SecurityException(err)
                }
            }

            // 2. Gửi 0x02 CONNECT_ACCEPTED kèm Manifest
            val calculatedSha = if (!expectedSha256.isNullOrBlank()) {
                expectedSha256
            } else {
                Sha256StreamCalculator.calculateFileSha256(file)
            }

            val manifestItem = ManifestItemPayload(
                relativePath = file.name,
                fileSize = file.length(),
                mimeType = "application/octet-stream",
                sha256 = calculatedSha
            )

            val acceptFrame = serializer.buildConnectAcceptedFrame(
                streamId,
                ConnectAcceptedPayload(
                    status = ConnectAcceptedPayload.STATUS_APPROVED,
                    transferType = "SINGLE_FILE",
                    manifest = listOf(manifestItem)
                )
            )
            serializer.serializeTo(acceptFrame, outputStream)

            // 3. Đợi 0x03 REQUEST_STREAM từ Receiver
            val requestStreamFrame = parser.parseNextFrame(inputStream)
                ?: throw IOException("Kết nối bị ngắt trước khi nhận REQUEST_STREAM")

            if (requestStreamFrame.msgType != ChunkFrame.TYPE_REQUEST_STREAM) {
                throw IOException("Khung tin mong đợi REQUEST_STREAM (0x03), nhận được: ${requestStreamFrame.msgType}")
            }

            val streamReq = RequestStreamPayload.fromBytes(requestStreamFrame.payload)
            var currentOffset = streamReq.startOffset
            val totalFileSize = file.length()

            // 4. Bắt đầu truyền luồng DATA_CHUNK (0x04)
            currentState = TransferEngineState.TRANSFERRING
            callback?.onStateChanged(currentState)

            val raf = RandomAccessFile(file, "r")
            raf.seek(currentOffset)
            val fileChannel = raf.channel

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
                            }
                            ChunkFrame.TYPE_VERIFY_RESULT -> {
                                val res = VerifyResultPayload.fromBytes(ackOrVerifyFrame.payload)
                                verifyResultDeferred.complete(res)
                                break
                            }
                            ChunkFrame.TYPE_CONTROL_SIGNAL -> {
                                val signal = ControlSignalPayload.fromBytes(ackOrVerifyFrame.payload)
                                when (signal.command) {
                                    ControlSignalPayload.CMD_PAUSE -> pause()
                                    ControlSignalPayload.CMD_RESUME -> resume()
                                    ControlSignalPayload.CMD_CANCEL -> cancel()
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
                while (currentOffset < totalFileSize && !isCancelled.get()) {
                    while (isPaused.get() && !isCancelled.get()) {
                        delay(200)
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
                    if (delta >= 500) {
                        val speed = (bytesSinceLastSpeed * 1000L) / maxOf(delta, 1L)
                        callback?.onProgress(currentOffset, totalFileSize, speed)
                        lastSpeedTimestamp = now
                        bytesSinceLastSpeed = 0L
                    }
                }

                if (isCancelled.get()) {
                    val cancelSignal = ControlSignalPayload(command = ControlSignalPayload.CMD_CANCEL)
                    serializer.serializeTo(serializer.buildControlSignalFrame(streamId, cancelSignal), outputStream)
                    throw CancellationException("Phiên gửi đã bị hủy bởi người dùng")
                }

                // Chờ kết quả VERIFY_RESULT từ Receiver
                currentState = TransferEngineState.VERIFYING
                callback?.onStateChanged(currentState)

                val verifyResult = verifyResultDeferred.await()
                if (verifyResult.isSuccess) {
                    currentState = TransferEngineState.COMPLETED
                    callback?.onStateChanged(currentState)
                    callback?.onCompleted(sessionId, verifyResult.actualSha256)
                    Result.success(verifyResult.actualSha256)
                } else {
                    currentState = TransferEngineState.FAILED
                    callback?.onStateChanged(currentState)
                    val err = "Máy nhận thông báo thất bại: mã băm thực tế ${verifyResult.actualSha256}"
                    callback?.onError("0x301", err)
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
            try { activeSocket?.close() } catch (_: Exception) {}
            try { serverSocket.close() } catch (_: Exception) {}
            activeSocket = null
            activeServerSocket = null
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
            socketFactory.createSocket(host, port, useTls = useTls).also {
                activeSocket = it
            }
        } catch (e: Exception) {
            currentState = TransferEngineState.FAILED
            callback?.onStateChanged(currentState)
            callback?.onError("CONNECT_ERROR", "Không thể kết nối đến máy gửi $host:$port - ${e.message}")
            return@withContext Result.failure(e)
        }

        var tempFile: File? = null

        try {
            val inputStream = BufferedInputStream(socket.getInputStream(), 128 * 1024)
            val outputStream = BufferedOutputStream(socket.getOutputStream(), 128 * 1024)
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

            // 2. Nhận 0x02 CONNECT_ACCEPTED từ Sender
            val acceptedFrame = parser.parseNextFrame(inputStream)
                ?: throw IOException("Kết nối bị đóng trước khi nhận CONNECT_ACCEPTED")

            if (acceptedFrame.msgType != ChunkFrame.TYPE_CONNECT_ACCEPTED) {
                throw IOException("Khung tin mong đợi CONNECT_ACCEPTED (0x02), nhận được: ${acceptedFrame.msgType}")
            }

            val acceptedPayload = ConnectAcceptedPayload.fromBytes(acceptedFrame.payload)
            if (!acceptedPayload.isApproved) {
                val reason = acceptedPayload.rejectionReason ?: "0x102: Kết nối bị từ chối bởi máy gửi"
                callback?.onError("0x102", reason)
                throw SecurityException(reason)
            }

            val manifestItem = acceptedPayload.manifest.firstOrNull()
                ?: throw IOException("Manifest rỗng từ máy gửi")

            val sanitizedName = FilenameSanitizer.sanitize(manifestItem.relativePath)
            val finalTargetFile = File(targetDirectory, sanitizedName)
            val tempTargetFile = File(targetDirectory, "$sanitizedName.tmp").also { tempFile = it }

            // Kiểm tra khả năng Resume
            val startOffset = if (tempTargetFile.exists() && tempTargetFile.length() < manifestItem.fileSize) {
                tempTargetFile.length()
            } else {
                if (tempTargetFile.exists()) tempTargetFile.delete()
                0L
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
                        delay(200)
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
                    if (finalTargetFile.exists()) finalTargetFile.delete()
                    val renameSuccess = tempTargetFile.renameTo(finalTargetFile)
                    if (!renameSuccess) {
                        throw IOException("Không thể đổi tên tệp tạm thành: ${finalTargetFile.absolutePath}")
                    }

                    val verifyFrame = serializer.buildVerifyResultFrame(
                        streamId = streamId,
                        payload = VerifyResultPayload(sessionId, "SUCCESS", calculatedSha256)
                    )
                    serializer.serializeTo(verifyFrame, outputStream)

                    currentState = TransferEngineState.COMPLETED
                    callback?.onStateChanged(currentState)
                    callback?.onCompleted(sessionId, calculatedSha256)

                    Result.success(finalTargetFile)
                } else {
                    val verifyFrame = serializer.buildVerifyResultFrame(
                        streamId = streamId,
                        payload = VerifyResultPayload(sessionId, "CHECKSUM_FAILED", calculatedSha256)
                    )
                    serializer.serializeTo(verifyFrame, outputStream)

                    SafeFileDeleter.wipeAndSanitizeFile(tempTargetFile)
                    currentState = TransferEngineState.FAILED
                    callback?.onStateChanged(currentState)
                    val err = "Sai lệch mã băm SHA-256: kỳ vọng ${manifestItem.sha256}, thực tế $calculatedSha256"
                    callback?.onError("0x301", err)
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
            callback?.onError("SERVER_BIND_ERROR", "Không thể mở cổng ServerSocket: ${e.message}")
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
                callback?.onError("SERVER_TIMEOUT", "Quá thời gian chờ kết nối từ máy gửi ($serverTimeoutMs ms)")
                return@withContext Result.failure(e)
            }
            val inputStream = BufferedInputStream(socket.getInputStream(), 128 * 1024)
            val outputStream = BufferedOutputStream(socket.getOutputStream(), 128 * 1024)

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
                    callback?.onError("0x101", err)
                    throw SecurityException(err)
                }
            } else if (expectedTeamId != null && receivedTeamId != expectedTeamId) {
                val err = "TeamId không khớp: nhận $receivedTeamId, dự kiến $expectedTeamId"
                callback?.onError("TEAM_MISMATCH", err)
                throw SecurityException(err)
            }

            val authPayload = HandshakeAuthPayload.fromBytes(handshakeFrame.payload)

            if (peerValidator != null && !peerValidator.invoke(authPayload)) {
                val err = "Peer bị từ chối bởi chính sách thẩm định (vd: thành viên đã bị thu hồi quyền)"
                callback?.onError("PEER_VALIDATION_FAILED", err)
                throw SecurityException(err)
            }

            if (ownerPublicKey != null) {
                if (keyManager == null || authPayload.credentialData.isBlank()) {
                    val err = "Thiếu KeyManager hoặc Credential để thẩm định chữ ký Owner"
                    callback?.onError("INVALID_CREDENTIAL", err)
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
                    callback?.onError("SIGNATURE_VERIFICATION_FAILED", err)
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
                        delay(200)
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
                        payload = VerifyResultPayload(docId, "SUCCESS", calculatedSha256)
                    )
                    serializer.serializeTo(verifyFrame, outputStream)

                    currentState = TransferEngineState.COMPLETED
                    callback?.onStateChanged(currentState)
                    callback?.onCompleted(docId, calculatedSha256)

                    Result.success(finalTargetFile)
                } else {
                    val verifyFrame = serializer.buildVerifyResultFrame(
                        streamId = manifestFrame.streamId,
                        payload = VerifyResultPayload(docId, "CHECKSUM_FAILED", calculatedSha256)
                    )
                    serializer.serializeTo(verifyFrame, outputStream)

                    SafeFileDeleter.wipeAndSanitizeFile(tempTargetFile)
                    currentState = TransferEngineState.FAILED
                    callback?.onStateChanged(currentState)
                    val err = "Sai lệch mã băm SHA-256: kỳ vọng $expectedSha256, thực tế $calculatedSha256"
                    callback?.onError("CHECKSUM_MISMATCH", err)
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
        require(file.exists() && file.isFile) { "Tệp nguồn không tồn tại: ${file.absolutePath}" }

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
            callback?.onError("CONNECT_ERROR", "Không thể kết nối đến máy chủ $host:$port - ${e.message}")
            return@withContext Result.failure(e)
        }

        try {
            val inputStream = BufferedInputStream(socket.getInputStream(), 128 * 1024)
            val outputStream = BufferedOutputStream(socket.getOutputStream(), 128 * 1024)
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
                callback?.onError("TRANSFER_REJECTED", reason)
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
                        delay(200)
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
                    if (delta >= 500) {
                        val speed = (bytesSinceLastSpeed * 1000L) / maxOf(delta, 1L)
                        callback?.onProgress(currentOffset, totalFileSize, speed)
                        lastSpeedTimestamp = now
                        bytesSinceLastSpeed = 0L
                    }
                }

                if (isCancelled.get()) {
                    val cancelSignal = ControlSignalPayload(command = "CANCEL")
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
                    callback?.onError("CHECKSUM_FAILED", err)
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
