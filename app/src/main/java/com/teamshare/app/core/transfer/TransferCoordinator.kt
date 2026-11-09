package com.teamshare.app.core.transfer

import com.teamshare.app.core.constant.AppConstants
import com.teamshare.app.core.platform.network.INetworkMonitor
import com.teamshare.app.core.platform.network.NetworkAddressHelper
import com.teamshare.app.core.platform.network.NetworkConfig
import com.teamshare.app.core.platform.network.NetworkType
import com.teamshare.app.core.platform.service.ITransferForegroundServiceLauncher
import com.teamshare.app.core.platform.service.ITransferServiceController
import com.teamshare.app.core.platform.service.TransferServiceController
import com.teamshare.app.core.protocol.HandshakeAuthPayload
import com.teamshare.app.core.security.crypto.CryptoEngine
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.core.storage.saf.SafeFileDeleter
import com.teamshare.app.core.storage.saf.StorageDirectoryManager
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.core.transfer.lan.TransferProgressCallback
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.DomainError
import com.teamshare.app.domain.model.ErrorCode
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TeamMembershipCredential
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferProgress
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITransferRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Nhạc trưởng Điều phối Truyền tải Đa môi trường (TransferCoordinator).
 * Đảm nhiệm:
 * 1. Định tuyến tự động kênh truyền: Ưu tiên Direct LAN khi cùng Wi-Fi; chuyển sang Zero-Knowledge Relay khi khác mạng.
 * 2. Giám sát mạng thích ứng: Phát hiện mất kết nối Wi-Fi trong < 500 ms và tạm dừng phiên an toàn.
 * 3. Hỗ trợ khôi phục truyền tiếp theo Checkpoint confirmedOffset.
 * 4. Tước quyền thành viên cưỡng bức: Ngắt toàn bộ phiên truyền của thành viên bị loại trong < 200 ms.
 * 5. Cập nhật đồng bộ dịch vụ Android Foreground Service (TransferServiceController).
 */
class TransferCoordinator(
    private val lanEngine: LanTransferEngine,
    private val relayEngine: RelayClientEngine,
    private val networkMonitor: INetworkMonitor,
    private val transferRepository: ITransferRepository,
    private val serviceController: ITransferServiceController = TransferServiceController,
    private val serviceLauncher: ITransferForegroundServiceLauncher? = null,
    private val directoryManager: StorageDirectoryManager? = null,
    private val auditRepository: IAuditRepository? = null,
    private val keyManager: IKeyManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val activeSessions = ConcurrentHashMap<String, TransferSession>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val isPausedMap = ConcurrentHashMap<String, AtomicBoolean>()
    private val outgoingTransferSemaphore = kotlinx.coroutines.sync.Semaphore(permits = AppConstants.FlowControl.MAX_CONCURRENT_OUTGOING_TRANSFERS)

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
     * Xác định kênh truyền phù hợp dựa trên trạng thái mạng và thông tin kết nối.
     * Hỗ trợ thăm dò kết nối LAN (Pre-flight Probe TCP) khi có cổng kết nối cụ thể.
     */
    fun selectTransferChannel(peerIp: String?, port: Int = 0): TransferChannel {
        val currentNetwork = networkMonitor.getCurrentNetworkType()
        if (currentNetwork != NetworkType.WIFI_SAME_LAN || peerIp.isNullOrBlank()) {
            return TransferChannel.ZERO_KNOWLEDGE_RELAY
        }

        // Nếu thiết bị này là Android Emulator và peer là thiết bị LAN vật lý bên ngoài (NAT máy ảo không cho phép kết nối ngược vào 10.0.2.16)
        if (com.teamshare.app.core.platform.network.NetworkAddressHelper.isEmulator() &&
            !peerIp.startsWith("10.0.2.") && !peerIp.startsWith("127.0.0.1") && peerIp != "localhost"
        ) {
            return TransferChannel.ZERO_KNOWLEDGE_RELAY
        }

        if (port > 0) {
            val isReachable = try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(peerIp, port), AppConstants.Timeout.LAN_FAST_PROBE_MS.toInt())
                    true
                }
            } catch (_: Exception) {
                false
            }
            if (!isReachable) {
                return TransferChannel.ZERO_KNOWLEDGE_RELAY
            }
        }

        return TransferChannel.DIRECT_LAN
    }

    /**
     * Bắt đầu phiên truyền tải và phát luồng tiến trình phản ứng (Flow<TransferProgress>).
     */
    fun startTransfer(
        session: TransferSession,
        file: File? = null,
        targetDirectory: File? = null,
        peerIp: String? = null,
        port: Int = 0,
        relayUrl: String? = null,
        streamId: String? = null,
        documentName: String = file?.name ?: "document_${session.docId}",
        teamId: String = "",
        memberId: String = "",
        deviceFingerprint: String = "",
        role: String = "MEMBER",
        credentialData: String = "",
        localCredential: TeamMembershipCredential? = null,
        ownerPublicKey: PublicKey? = null,
        peerFingerprint: String = "",
        peerEphemeralPublicKey: ByteArray? = null,
        expectedSha256: String? = null
    ): Flow<TransferProgress> {
        activeSessions[session.sessionId] = session

        return callbackFlow {
            val isPaused = AtomicBoolean(false)
            isPausedMap[session.sessionId] = isPaused
            val lastCheckpointBytes = AtomicLong(session.confirmedOffset)

        // 1. Định tuyến kênh truyền phù hợp (Thử LAN Probe nếu có endpoint)
        val channel = if (session.channel == TransferChannel.ZERO_KNOWLEDGE_RELAY && peerIp.isNullOrBlank()) {
            TransferChannel.ZERO_KNOWLEDGE_RELAY
        } else {
            selectTransferChannel(peerIp, port)
        }

        // 2. Bắt đầu thông báo Foreground Service
        serviceController.startSession(
            sessionId = session.sessionId,
            documentName = documentName,
            totalBytes = session.totalBytes,
            isLan = (channel == TransferChannel.DIRECT_LAN)
        )
        serviceLauncher?.startService(
            sessionId = session.sessionId,
            documentName = documentName,
            totalBytes = session.totalBytes,
            isLan = (channel == TransferChannel.DIRECT_LAN)
        )

        // 3. Callback thông báo tiến trình
        val callback = object : TransferProgressCallback {
            override fun onProgress(bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long) {
                serviceController.notifyProgress(bytesTransferred, totalBytes, speedBytesPerSec)
                
                // Điều tiết tần suất ghi Checkpoint CSDL (ít nhất 2MB hoặc hoàn tất)
                val previous = lastCheckpointBytes.get()
                val delta = bytesTransferred - previous
                if (delta >= AppConstants.FlowControl.CHECKPOINT_THRESHOLD_RELAY || (totalBytes > 0L && bytesTransferred >= totalBytes)) {
                    if (lastCheckpointBytes.compareAndSet(previous, bytesTransferred)) {
                        CoroutineScope(ioDispatcher).launch {
                            transferRepository.updateCheckpoint(session.sessionId, bytesTransferred)
                        }
                    }
                }

                trySend(
                    TransferProgress(
                        sessionId = session.sessionId,
                        bytesTransferred = bytesTransferred,
                        totalBytes = totalBytes,
                        speedBytesPerSec = speedBytesPerSec,
                        channel = channel,
                        state = TransferEngineState.TRANSFERRING
                    )
                )
            }

            override fun onStateChanged(newState: TransferEngineState) {
                serviceController.notifyStateChanged(newState)
                if (newState == TransferEngineState.COMPLETED ||
                    newState == TransferEngineState.FAILED ||
                    newState == TransferEngineState.CANCELLED
                ) {
                    serviceLauncher?.stopService()
                }
                val mappedStatus = when (newState) {
                    TransferEngineState.TRANSFERRING -> SessionStatus.TRANSFERRING
                    TransferEngineState.PAUSED -> SessionStatus.PAUSED
                    TransferEngineState.COMPLETED -> SessionStatus.COMPLETED
                    TransferEngineState.FAILED -> SessionStatus.FAILED
                    TransferEngineState.CANCELLED -> SessionStatus.CANCELLED
                    TransferEngineState.VERIFYING -> SessionStatus.VERIFYING
                    else -> SessionStatus.IDLE
                }
                CoroutineScope(ioDispatcher).launch {
                    transferRepository.updateSessionStatus(session.sessionId, mappedStatus)
                }
                trySend(
                    TransferProgress(
                        sessionId = session.sessionId,
                        totalBytes = session.totalBytes,
                        channel = channel,
                        state = newState
                    )
                )
            }

            override fun onSasGenerated(sasCode: String) {
                trySend(
                    TransferProgress(
                        sessionId = session.sessionId,
                        totalBytes = session.totalBytes,
                        channel = channel,
                        state = TransferEngineState.HANDSHAKING,
                        sasCode = sasCode
                    )
                )
            }

            override fun onCompleted(docId: String, finalSha256: String) {
                serviceController.notifyCompleted(docId, finalSha256)
                CoroutineScope(ioDispatcher).launch {
                    transferRepository.updateSessionStatus(session.sessionId, SessionStatus.COMPLETED)
                    auditRepository?.logEvent(
                        AuditLog(
                            teamId = teamId,
                            actorId = memberId,
                            eventType = AuditEventType.TRANSFER_COMPLETED,
                            severity = AuditSeverity.INFO,
                            details = "Hoàn tất truyền tài liệu $docId với mã băm $finalSha256"
                        )
                    )
                }
                trySend(
                    TransferProgress(
                        sessionId = session.sessionId,
                        bytesTransferred = session.totalBytes,
                        totalBytes = session.totalBytes,
                        channel = channel,
                        state = TransferEngineState.COMPLETED
                    )
                )
                close()
            }

            override fun onError(errorCode: String, message: String) {
                serviceController.notifyError(errorCode, message)
                CoroutineScope(ioDispatcher).launch {
                    transferRepository.updateSessionStatus(session.sessionId, SessionStatus.FAILED, errorCode)
                }
                trySend(
                    TransferProgress(
                        sessionId = session.sessionId,
                        bytesTransferred = lastCheckpointBytes.get(),
                        totalBytes = session.totalBytes,
                        channel = channel,
                        state = TransferEngineState.FAILED,
                        errorCode = errorCode,
                        errorMessage = message
                    )
                )
                close()
            }
        }

        // 4. Giám sát mạng thích ứng trong < 500 ms
        val networkJob = launch(ioDispatcher) {
            networkMonitor.networkState.collect { netState ->
                if (!netState.isConnected || netState.type == NetworkType.DISCONNECTED) {
                    if (channel == TransferChannel.DIRECT_LAN && !isPaused.get()) {
                        isPaused.set(true)
                        lanEngine.pause()
                        callback.onStateChanged(TransferEngineState.PAUSED)
                    }
                }
            }
        }

        // 5. Thực thi truyền tải trên luồng IO (Điều tiết Semaphore tối đa 3 phiên tải ra đồng thời)
        val transferJob = launch(ioDispatcher) {
            try {
                val executeBlock: suspend () -> Unit = {
                    val memberPub = keyManager?.getPublicKey(IKeyManager.MEMBER_IDENTITY_KEY_ALIAS)
                        ?: keyManager?.generateMemberIdentityKey()
                    val myFprint = memberPub?.encoded?.let { Sha256StreamCalculator.calculateBytesSha256(it) } ?: ""

                    val effectiveCred: TeamMembershipCredential = localCredential ?: TeamMembershipCredential(
                        teamId = teamId,
                        memberId = memberId.ifBlank { "member_$teamId" },
                        deviceFingerprint = myFprint,
                        role = MemberRole.MEMBER,
                        issuedAt = System.currentTimeMillis()
                    )
                    val effectivePubKey: PublicKey = ownerPublicKey ?: memberPub ?: keyManager?.generateMemberIdentityKey()!!

                    val peerValidator: (HandshakeAuthPayload) -> Boolean = { true }

                    val resolvedSha = if (!expectedSha256.isNullOrBlank()) {
                        expectedSha256
                    } else if (file != null && file.exists()) {
                        try {
                            Sha256StreamCalculator.calculateFileSha256(file)
                        } catch (_: Exception) {
                            ""
                        }
                    } else {
                        ""
                    }

                    if (channel == TransferChannel.DIRECT_LAN) {
                        if (session.direction == TransferDirection.OUTGOING) {
                            requireNotNull(file) { "File nguồn không được null cho phiên gửi" }
                            requireNotNull(peerIp) { "IP đích không được null cho phiên gửi LAN" }
                            lanEngine.sendFile(
                                host = peerIp,
                                port = port,
                                file = file,
                                docId = session.docId,
                                teamId = teamId,
                                memberId = effectiveCred.memberId,
                                deviceFingerprint = effectiveCred.deviceFingerprint,
                                role = effectiveCred.role.name,
                                credentialData = effectiveCred.ownerSignature,
                                useTls = true,
                                expectedSha256 = resolvedSha,
                                callback = callback
                            )
                        } else {
                            val targetDir = targetDirectory
                                ?: (directoryManager?.getTeamCompletedDir(teamId) ?: directoryManager?.completedRootDir)
                                ?: File(System.getProperty("java.io.tmpdir"), "teamshare_recv")
                            lanEngine.receiveFile(
                                port = port,
                                targetDirectory = targetDir,
                                expectedTeamId = teamId,
                                ownerPublicKey = effectivePubKey,
                                useTls = true,
                                callback = callback,
                                peerValidator = peerValidator
                            )
                        }
                    } else {
                        val actualRelayUrl = relayUrl
                            ?: NetworkAddressHelper.getDefaultRelayUrl(endpoint = NetworkConfig.RELAY_STREAM_ENDPOINT)

                        if (session.direction == TransferDirection.OUTGOING) {
                            requireNotNull(file) { "File nguồn không được null cho phiên gửi Relay" }
                            val raf = RandomAccessFile(file, "r")
                            try {
                                relayEngine.sendFileViaRelay(
                                    relayWsUrl = actualRelayUrl,
                                    session = session,
                                    fileChannel = raf.channel,
                                    expectedSha256 = resolvedSha,
                                    localCredential = effectiveCred,
                                    ownerPublicKey = effectivePubKey,
                                    callback = callback,
                                    peerValidator = peerValidator
                                )
                            } finally {
                                try { raf.close() } catch (_: Exception) {}
                            }
                        } else {
                            val targetDir = targetDirectory
                                ?: (directoryManager?.getTeamCompletedDir(teamId) ?: directoryManager?.completedRootDir)
                                ?: File(System.getProperty("java.io.tmpdir"), "teamshare_recv")
                            val targetFile = File(targetDir, documentName)
                            relayEngine.receiveFileViaRelay(
                                relayWsUrl = actualRelayUrl,
                                session = session,
                                targetFile = targetFile,
                                expectedSha256 = resolvedSha,
                                localCredential = effectiveCred,
                                ownerPublicKey = effectivePubKey,
                                callback = callback,
                                peerValidator = peerValidator
                            )
                        }
                    }
                }

                if (session.direction == TransferDirection.OUTGOING) {
                    outgoingTransferSemaphore.withPermit {
                        executeBlock()
                    }
                } else {
                    executeBlock()
                }
            } catch (e: Exception) {
                callback.onError(ErrorCode.ERR_SYSTEM_EXCEPTION, e.message ?: DomainError.GeneralException("").technicalMessage)
            }
        }

        activeJobs[session.sessionId] = transferJob

        awaitClose {
            networkJob.cancel()
            transferJob.cancel()
            activeSessions.remove(session.sessionId)
            activeJobs.remove(session.sessionId)
            isPausedMap.remove(session.sessionId)
        }
    }
}

    /**
     * Tạm dừng phiên truyền tải đang hoạt động.
     */
    suspend fun pauseSession(sessionId: String) = withContext(ioDispatcher) {
        isPausedMap[sessionId]?.set(true)
        lanEngine.pause()
        relayEngine.pause()
        serviceController.requestPause()
        transferRepository.updateSessionStatus(sessionId, SessionStatus.PAUSED)
    }

    /**
     * Tiếp tục phiên truyền tải đã tạm dừng.
     */
    suspend fun resumeSession(sessionId: String) = withContext(ioDispatcher) {
        isPausedMap[sessionId]?.set(false)
        lanEngine.resume()
        relayEngine.resume()
        serviceController.requestResume()
        transferRepository.updateSessionStatus(sessionId, SessionStatus.TRANSFERRING)
    }

    /**
     * Hủy phiên truyền tải và dọn dẹp tài nguyên.
     */
    suspend fun cancelSession(sessionId: String) = withContext(ioDispatcher) {
        lanEngine.cancel()
        relayEngine.cancel()
        serviceController.requestCancel()
        activeJobs[sessionId]?.cancel()
        activeSessions.remove(sessionId)
        activeJobs.remove(sessionId)
        isPausedMap.remove(sessionId)
        transferRepository.updateSessionStatus(sessionId, SessionStatus.CANCELLED)
    }

    /**
     * Cưỡng bức hủy tất cả các phiên truyền tải tệp.
     */
    suspend fun cancelSessionsForTeam(teamId: String = "") = withContext(ioDispatcher) {
        for (session in activeSessions.values.toList()) {
            cancelSession(session.sessionId)
        }
    }

    /**
     * Cưỡng bức hủy phiên truyền tải của thành viên cụ thể.
     */
    suspend fun cancelSessionForMember(memberId: String) = withContext(ioDispatcher) {
        val targetSessions = activeSessions.values.filter { it.peerMemberId == memberId }
        for (session in targetSessions) {
            cancelSession(session.sessionId)
        }
    }

    /**
     * Lấy phiên truyền tải hiện tại nếu đang hoạt động.
     */
    fun getActiveSession(sessionId: String): TransferSession? = activeSessions[sessionId]

    /**
     * Đăng ký phiên truyền tải hoạt động (phục vụ test hoặc khởi tạo trước).
     */
    fun registerActiveSession(session: TransferSession, teamId: String = "") {
        activeSessions[session.sessionId] = session
    }
}
