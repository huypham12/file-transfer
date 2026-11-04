package com.teamshare.app.core.transfer.lan

import com.teamshare.app.core.network.tls.TlsSocketFactory
import com.teamshare.app.core.protocol.BinaryFrameParser
import com.teamshare.app.core.protocol.ChunkAckPayload
import com.teamshare.app.core.protocol.ChunkFrame
import com.teamshare.app.core.protocol.ControlSignalPayload
import com.teamshare.app.core.protocol.DataChunkPayload
import com.teamshare.app.core.protocol.FrameSerializer
import com.teamshare.app.core.protocol.HandshakeAuthPayload
import com.teamshare.app.core.protocol.ManifestResponsePayload
import com.teamshare.app.core.protocol.TransferManifestPayload
import com.teamshare.app.core.protocol.VerifyResultPayload
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.core.security.sanitizer.FilenameSanitizer
import com.teamshare.app.core.storage.saf.SafeFileDeleter
import com.teamshare.app.core.transfer.buffer.BufferPoolManager
import com.teamshare.app.core.transfer.checkpoint.CheckpointTracker
import com.teamshare.app.data.local.db.dao.TransferSessionDao
import com.teamshare.app.domain.repository.ITeamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * điểm kiểm tra Checkpoint 2MB (fsync SSD), và xác minh toàn vẹn SHA-256 theo chuẩn SDD Chương 6.2.
 */
class LanTransferEngine(
    private val bufferPool: BufferPoolManager = BufferPoolManager(),
    private val socketFactory: TlsSocketFactory = TlsSocketFactory(),
    private val serializer: FrameSerializer = FrameSerializer(),
    private val parser: BinaryFrameParser = BinaryFrameParser(),
    private val keyManager: IKeyManager? = null,
    private val teamRepository: ITeamRepository? = null,
    private val sessionDao: TransferSessionDao? = null
) {
    companion object {
        const val DEFAULT_SERVER_TIMEOUT_MS = 60_000 // 60 giây chờ kết nối
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
    // CHẾ ĐỘ MÁY CHỦ NHẬN DỮ LIỆU (RECEIVER / SERVER MODE)
    // =====================================================================================

    /**
     * Khởi chạy phiên nhận tệp từ mạng LAN.
     * @param port Cổng TCP lắng nghe (0 để tự cấp phát ngẫu nhiên trong dải private 49152..65535)
     * @param targetDirectory Thư mục lưu tệp nhận được
     * @param expectedTeamId Mã nhóm dự kiến để thẩm định tính hợp lệ
     * @param ownerPublicKey Khóa công khai của Owner dùng thẩm định chữ ký Credential
     * @param useTls Có sử dụng mã hóa TLS hay không
     * @param serverTimeoutMs Thời gian chờ kết nối tối đa từ máy gửi (mặc định 60s)
     * @param callback Lắng nghe sự kiện tiến trình
     * @return Cổng TCP thực tế mà máy chủ đang lắng nghe
     */
    suspend fun receiveFile(
        port: Int = 0,
        targetDirectory: File,
        expectedTeamId: String? = null,
        ownerPublicKey: PublicKey? = null,
        useTls: Boolean = false,
        serverTimeoutMs: Int = DEFAULT_SERVER_TIMEOUT_MS,
        callback: TransferProgressCallback? = null
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
            // Thiết lập timeout chờ kết nối
            if (serverTimeoutMs > 0) {
                serverSocket.soTimeout = serverTimeoutMs
            }

            // Chấp nhận 1 kết nối từ Sender
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

            // Giai đoạn 1: Bắt tay xác thực (HANDSHAKE_AUTH)
            currentState = TransferEngineState.HANDSHAKING
            callback?.onStateChanged(currentState)

            val handshakeFrame = parser.parseNextFrame(inputStream)
                ?: throw IOException("Kết nối bị đóng trước khi nhận HANDSHAKE_AUTH")

            if (handshakeFrame.msgType != ChunkFrame.TYPE_HANDSHAKE_AUTH) {
                throw IOException("Khung tin mong đợi HANDSHAKE_AUTH (0x01), nhận được: ${handshakeFrame.msgType}")
            }

            val authPayload = HandshakeAuthPayload.fromBytes(handshakeFrame.payload)

            // Thẩm định Team ID
            if (expectedTeamId != null && authPayload.teamId != expectedTeamId) {
                val err = "TeamId không khớp: nhận ${authPayload.teamId}, dự kiến $expectedTeamId"
                callback?.onError("TEAM_MISMATCH", err)
                throw SecurityException(err)
            }

            // Thẩm định thành viên chưa bị thu hồi quyền (CRL check)
            if (teamRepository != null) {
                val isActive = teamRepository.isMemberActive(authPayload.teamId, authPayload.memberId)
                if (!isActive) {
                    val err = "Thành viên ${authPayload.memberId} đã bị thu hồi quyền truy cập"
                    callback?.onError("MEMBER_REVOKED", err)
                    throw SecurityException(err)
                }
            }

            // Thẩm định chữ ký số của Owner trên Credential nếu có yêu cầu ownerPublicKey
            if (ownerPublicKey != null) {
                if (keyManager == null || authPayload.credentialData.isBlank()) {
                    val err = "Thiếu KeyManager hoặc Credential để thẩm định chữ ký Owner"
                    callback?.onError("INVALID_CREDENTIAL", err)
                    throw SecurityException(err)
                }
                val memberClaim = "${authPayload.teamId}:${authPayload.memberId}:${authPayload.deviceFingerprint}:${authPayload.role}".toByteArray(Charsets.UTF_8)
                val signatureBytes = parseHexOrBytes(authPayload.credentialData)
                val verified = keyManager.verifySignature(
                    publicKey = ownerPublicKey,
                    data = memberClaim,
                    signature = signatureBytes
                )
                if (!verified) {
                    val err = "Chữ ký số của Owner trên Credential không hợp lệ"
                    callback?.onError("SIGNATURE_VERIFICATION_FAILED", err)
                    throw SecurityException(err)
                }
            }

            // Giai đoạn 2: Nhận TRANSFER_MANIFEST (0x02)
            val manifestFrame = parser.parseNextFrame(inputStream)
                ?: throw IOException("Kết nối bị đóng trước khi nhận TRANSFER_MANIFEST")

            if (manifestFrame.msgType != ChunkFrame.TYPE_TRANSFER_MANIFEST) {
                throw IOException("Khung tin mong đợi TRANSFER_MANIFEST (0x02), nhận được: ${manifestFrame.msgType}")
            }

            val manifest = TransferManifestPayload.fromBytes(manifestFrame.payload)
            val sanitizedName = FilenameSanitizer.sanitize(manifest.fileName)
            val finalTargetFile = File(targetDirectory, sanitizedName)
            val tempTargetFile = File(targetDirectory, "$sanitizedName.tmp").also { tempFile = it }

            // Kiểm tra khả năng khôi phục (Resume) từ mốc checkpoint trước đó
            val startOffset = if (tempTargetFile.exists() && tempTargetFile.length() < manifest.fileSize) {
                tempTargetFile.length()
            } else {
                if (tempTargetFile.exists()) tempTargetFile.delete()
                0L
            }

            // Gửi MANIFEST_RESPONSE (0x03)
            val manifestResponse = ManifestResponsePayload(accepted = true, startOffset = startOffset)
            val responseFrame = serializer.buildManifestResponseFrame(manifestFrame.streamId, manifestResponse)
            serializer.serializeTo(responseFrame, outputStream)

            // Giai đoạn 3: Nhận luồng DATA_CHUNK (0x04) và ghi Checkpoint
            currentState = TransferEngineState.TRANSFERRING
            callback?.onStateChanged(currentState)

            val checkpointTracker = CheckpointTracker(
                sessionId = manifest.docId,
                sessionDao = sessionDao
            ).apply { initOffset(startOffset) }

            val raf = RandomAccessFile(tempTargetFile, "rw")
            raf.seek(startOffset)
            val fileChannel = raf.channel

            var receivedBytesTotal = startOffset
            var lastSpeedTimestamp = System.currentTimeMillis()
            var bytesSinceLastSpeed = 0L

            try {
                while (receivedBytesTotal < manifest.fileSize && !isCancelled.get()) {
                    while (isPaused.get() && !isCancelled.get()) {
                        delay(200)
                    }
                    if (isCancelled.get()) break

                    val frame = parser.parseNextFrame(inputStream)
                        ?: throw IOException("Luồng truyền bị ngắt khi tệp mới nhận được $receivedBytesTotal / ${manifest.fileSize} bytes")

                    when (frame.msgType) {
                        ChunkFrame.TYPE_DATA_CHUNK -> {
                            val chunk = DataChunkPayload.fromBytes(frame.payload)
                            val byteBuffer = ByteBuffer.wrap(chunk.chunkData)
                            fileChannel.write(byteBuffer)

                            val dataLen = chunk.chunkData.size
                            receivedBytesTotal += dataLen
                            bytesSinceLastSpeed += dataLen

                            // Ghi nhận checkpoint, nếu vượt 2MB sẽ flush đĩa và trả về mốc ACK
                            val ackOffset = checkpointTracker.recordBytesWritten(fileChannel, dataLen)
                            if (ackOffset != null) {
                                val ackFrame = serializer.buildChunkAckFrame(frame.streamId, ChunkAckPayload(ackOffset))
                                serializer.serializeTo(ackFrame, outputStream)
                            }

                            // Tính toán tốc độ truyền
                            val now = System.currentTimeMillis()
                            val timeDelta = now - lastSpeedTimestamp
                            if (timeDelta >= 500) {
                                val speed = (bytesSinceLastSpeed * 1000L) / maxOf(timeDelta, 1L)
                                callback?.onProgress(receivedBytesTotal, manifest.fileSize, speed)
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

                        else -> {
                            // Bỏ qua khung tin không mong muốn
                        }
                    }
                }

                if (isCancelled.get()) {
                    throw CancellationException("Phiên truyền đã bị người dùng hủy")
                }

                // Giai đoạn 4: Xác thực toàn vẹn tệp SHA-256
                currentState = TransferEngineState.VERIFYING
                callback?.onStateChanged(currentState)

                // Flush toàn bộ dữ liệu dở dang cuối cùng xuống SSD
                checkpointTracker.flushFinal(fileChannel)
                fileChannel.close()
                raf.close()

                val calculatedSha256 = Sha256StreamCalculator.calculateFileSha256(tempTargetFile)
                val isVerified = calculatedSha256.equals(manifest.expectedSha256, ignoreCase = true)

                if (isVerified) {
                    // Đổi tên tệp tạm thành tên chính thức
                    if (finalTargetFile.exists()) finalTargetFile.delete()
                    val renameSuccess = tempTargetFile.renameTo(finalTargetFile)
                    if (!renameSuccess) {
                        throw IOException("Không thể đổi tên tệp tạm thành: ${finalTargetFile.absolutePath}")
                    }

                    // Gửi thông báo thành công cho Sender
                    val verifyFrame = serializer.buildVerifyResultFrame(
                        streamId = manifestFrame.streamId,
                        payload = VerifyResultPayload(manifest.docId, "SUCCESS", calculatedSha256)
                    )
                    serializer.serializeTo(verifyFrame, outputStream)

                    currentState = TransferEngineState.COMPLETED
                    callback?.onStateChanged(currentState)
                    callback?.onCompleted(manifest.docId, calculatedSha256)

                    Result.success(finalTargetFile)
                } else {
                    // Tiêu hủy tệp tạm an toàn do hỏng băm
                    SafeFileDeleter.wipeAndSanitizeFile(tempTargetFile)

                    val verifyFrame = serializer.buildVerifyResultFrame(
                        streamId = manifestFrame.streamId,
                        payload = VerifyResultPayload(manifest.docId, "CHECKSUM_FAILED", calculatedSha256)
                    )
                    serializer.serializeTo(verifyFrame, outputStream)

                    currentState = TransferEngineState.FAILED
                    callback?.onStateChanged(currentState)
                    val errorMsg = "Sai lệch mã băm SHA-256: kỳ vọng ${manifest.expectedSha256}, thực tế $calculatedSha256"
                    callback?.onError("CHECKSUM_MISMATCH", errorMsg)
                    Result.failure(SecurityException(errorMsg))
                }
            } finally {
                try { fileChannel.close() } catch (_: Exception) {}
                try { raf.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            currentState = if (isCancelled.get()) TransferEngineState.CANCELLED else TransferEngineState.FAILED
            callback?.onStateChanged(currentState)
            if (tempFile != null && isCancelled.get()) {
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

    // =====================================================================================
    // CHẾ ĐỘ MÁY KHÁCH GỬI DỮ LIỆU (SENDER / CLIENT MODE)
    // =====================================================================================

    /**
     * Gửi tệp tin qua mạng LAN tới máy chủ đích.
     */
    suspend fun sendFile(
        host: String,
        port: Int,
        file: File,
        docId: String,
        teamId: String,
        memberId: String,
        deviceFingerprint: String,
        role: String,
        credentialData: String,
        useTls: Boolean = false,
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

            // Giai đoạn 1: Bắt tay xác thực (HANDSHAKE_AUTH)
            currentState = TransferEngineState.HANDSHAKING
            callback?.onStateChanged(currentState)

            val authPayload = HandshakeAuthPayload(
                teamId = teamId,
                memberId = memberId,
                deviceFingerprint = deviceFingerprint,
                role = role,
                credentialData = credentialData,
                clientNonce = System.currentTimeMillis().toString()
            )
            val authFrame = serializer.buildHandshakeAuthFrame(streamId, authPayload)
            serializer.serializeTo(authFrame, outputStream)

            // Giai đoạn 2: Tính băm và gửi TRANSFER_MANIFEST (0x02)
            currentState = TransferEngineState.VERIFYING
            callback?.onStateChanged(currentState)
            val calculatedSha256 = Sha256StreamCalculator.calculateFileSha256(file)

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

            // Đợi MANIFEST_RESPONSE (0x03)
            val responseFrame = parser.parseNextFrame(inputStream)
                ?: throw IOException("Kết nối bị ngắt trước khi nhận MANIFEST_RESPONSE")

            if (responseFrame.msgType != ChunkFrame.TYPE_MANIFEST_RESPONSE) {
                throw IOException("Khung tin mong đợi MANIFEST_RESPONSE (0x03), nhận được: ${responseFrame.msgType}")
            }

            val response = ManifestResponsePayload.fromBytes(responseFrame.payload)
            if (!response.accepted) {
                val reason = response.rejectionReason ?: "Bị từ chối bởi máy nhận"
                callback?.onError("TRANSFER_REJECTED", reason)
                throw IOException("Phiên truyền bị từ chối: $reason")
            }

            // Giai đoạn 3: Bơm dữ liệu DATA_CHUNK tuần hoàn qua BufferPool
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

            // Luồng phụ nhận ACK và VerifyResult từ Receiver
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

                    // Thu hồi buffer 64KB từ hồ (áp dụng Backpressure)
                    val buffer = bufferPool.acquire()
                    var dataToSend: ByteArray? = null
                    try {
                        val byteBuffer = ByteBuffer.wrap(buffer)
                        val bytesRead = fileChannel.read(byteBuffer)
                        if (bytesRead > 0) {
                            dataToSend = buffer.copyOfRange(0, bytesRead)
                        } else {
                            break
                        }
                    } finally {
                        // Luôn giải phóng và zeroize buffer về hồ dù read() thành công hay ném ngoại lệ
                        bufferPool.release(buffer)
                    }

                    if (dataToSend != null) {
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
                }

                if (isCancelled.get()) {
                    // Gửi tín hiệu CANCEL cho máy nhận
                    val cancelSignal = ControlSignalPayload(command = "CANCEL")
                    serializer.serializeTo(serializer.buildControlSignalFrame(streamId, cancelSignal), outputStream)
                    throw CancellationException("Phiên gửi đã bị hủy bởi người dùng")
                }

                // Chờ kết quả thẩm định toàn vẹn từ máy nhận
                currentState = TransferEngineState.VERIFYING
                callback?.onStateChanged(currentState)

                val verifyResult = verifyResultDeferred.await()
                if (verifyResult.status == "SUCCESS") {
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
                if (!verifyResultDeferred.isCompleted) {
                    verifyResultDeferred.completeExceptionally(
                        CancellationException("Phiên gửi tệp bị hủy hoặc kết thúc bất thường")
                    )
                }
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

