package com.teamshare.app.core.transfer

import com.teamshare.app.core.platform.network.INetworkMonitor
import com.teamshare.app.core.platform.network.NetworkAddressHelper
import com.teamshare.app.core.platform.network.NetworkType
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
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TeamMembershipCredential
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferProgress
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.IDocumentRepository
import com.teamshare.app.domain.repository.ITeamRepository
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
    private val directoryManager: StorageDirectoryManager? = null,
    private val auditRepository: IAuditRepository? = null,
    private val keyManager: IKeyManager? = null,
    private val documentRepository: IDocumentRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val teamRepository: ITeamRepository? = null
) {

    private val activeSessions = ConcurrentHashMap<String, TransferSession>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val isPausedMap = ConcurrentHashMap<String, AtomicBoolean>()
    private val sessionTeamMap = ConcurrentHashMap<String, String>()
    private val outgoingTransferSemaphore = kotlinx.coroutines.sync.Semaphore(permits = 3)

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
                    socket.connect(java.net.InetSocketAddress(peerIp, port), 1500)
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
        if (teamId.isNotBlank()) {
            sessionTeamMap[session.sessionId] = teamId
        }

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

        // 3. Callback thông báo tiến trình
        val callback = object : TransferProgressCallback {
            override fun onProgress(bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long) {
                serviceController.notifyProgress(bytesTransferred, totalBytes, speedBytesPerSec)
                
                // Điều tiết tần suất ghi Checkpoint CSDL (ít nhất 2MB hoặc hoàn tất)
                val previous = lastCheckpointBytes.get()
                val delta = bytesTransferred - previous
                if (delta >= 2 * 1024 * 1024L || (totalBytes > 0L && bytesTransferred >= totalBytes)) {
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
                    if (session.direction == TransferDirection.INCOMING) {
                        val targetDir = targetDirectory ?: (directoryManager?.getTeamCompletedDir(teamId) ?: directoryManager?.completedRootDir)
                        val completedFile = if (targetDir != null) File(targetDir, documentName) else null
                        if (completedFile != null && completedFile.exists()) {
                            documentRepository?.updateDownloadStatus(session.docId, DownloadStatus.COMPLETED, completedFile.absolutePath)
                        } else {
                            documentRepository?.updateDownloadStatus(session.docId, DownloadStatus.COMPLETED, null)
                        }
                    }
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
                    val members = teamRepository?.getMembersByTeam(teamId)?.firstOrNull() ?: emptyList()
                    val effectiveCred: TeamMembershipCredential
                    val effectivePubKey: PublicKey

                    if (localCredential != null && ownerPublicKey != null) {
                        effectiveCred = localCredential
                        effectivePubKey = ownerPublicKey
                    } else {
                        val masterKey = keyManager?.getPublicKey(IKeyManager.MASTER_KEY_PREFIX + teamId)

                        if (masterKey != null) {
                            // Thiết bị hiện tại là TRƯỞNG NHÓM (OWNER)
                            val ownerMember = members.find { it.role == MemberRole.OWNER }
                            val fprint = ownerMember?.deviceFingerprint ?: Sha256StreamCalculator.calculateBytesSha256(masterKey.encoded)
                            val claim = "$teamId:$fprint:$fprint:OWNER".toByteArray(Charsets.UTF_8)
                            val sigBytes = try { keyManager.signData(IKeyManager.MASTER_KEY_PREFIX + teamId, claim) } catch (_: Exception) { ByteArray(0) }
                            val sigHex = sigBytes.joinToString("") { "%02x".format(it) }

                            effectiveCred = localCredential ?: TeamMembershipCredential(
                                teamId = teamId,
                                memberId = ownerMember?.memberId ?: fprint,
                                deviceFingerprint = fprint,
                                role = MemberRole.OWNER,
                                issuedAt = System.currentTimeMillis(),
                                ownerSignature = if (ownerMember?.credentialData?.isNotBlank() == true) ownerMember.credentialData else sigHex
                            )
                            effectivePubKey = ownerPublicKey ?: masterKey
                        } else {
                            // Thiết bị hiện tại là THÀNH VIÊN (MEMBER)
                            val memberPub = keyManager?.getPublicKey(IKeyManager.MEMBER_IDENTITY_KEY_ALIAS)
                                ?: keyManager?.generateMemberIdentityKey()
                            val myFprint = memberPub?.encoded?.let { Sha256StreamCalculator.calculateBytesSha256(it) } ?: ""
                            val myMember = members.find { it.deviceFingerprint == myFprint }
                                ?: members.find { it.memberId == memberId }
                                ?: members.find { it.role == MemberRole.MEMBER }
                            val ownerMember = members.find { it.role == MemberRole.OWNER }

                            val resolvedOwnerKey = try {
                                if (ownerMember != null && ownerMember.credentialData.isNotBlank()) {
                                    val keyBytes = parseHexOrBytes(ownerMember.credentialData)
                                    CryptoEngine.decodeEcPublicKey(keyBytes)
                                } else null
                            } catch (_: Exception) { null }

                            effectivePubKey = ownerPublicKey ?: resolvedOwnerKey ?: memberPub ?: keyManager?.generateMemberIdentityKey()!!
                            effectiveCred = localCredential ?: TeamMembershipCredential(
                                teamId = teamId,
                                memberId = myMember?.memberId ?: memberId.ifBlank { "member_$teamId" },
                                deviceFingerprint = myMember?.deviceFingerprint ?: myFprint,
                                role = MemberRole.MEMBER,
                                issuedAt = System.currentTimeMillis(),
                                ownerSignature = myMember?.credentialData ?: credentialData
                            )
                        }
                    }

                    val peerValidator: (HandshakeAuthPayload) -> Boolean = { peerAuth ->
                        if (peerAuth.teamId != teamId) {
                            false
                        } else {
                            val found = members.find {
                                (it.memberId == peerAuth.memberId || it.deviceFingerprint == peerAuth.deviceFingerprint) && it.isActive
                            }
                            if (found != null) {
                                val storedSig = parseHexOrBytes(found.credentialData)
                                val peerSig = parseHexOrBytes(peerAuth.credentialData)
                                val claim = "${peerAuth.teamId}:${peerAuth.memberId}:${peerAuth.deviceFingerprint}:${peerAuth.role}"
                                val matchesStored = peerAuth.credentialData.equals(found.credentialData, ignoreCase = true) || peerSig.contentEquals(storedSig)
                                val storedValid = CryptoEngine.verifySignature(effectivePubKey, claim.toByteArray(Charsets.UTF_8), storedSig)
                                matchesStored || storedValid || found.isActive
                            } else {
                                false
                            }
                        }
                    }

                    val resolvedSha = if (!expectedSha256.isNullOrBlank()) {
                        expectedSha256
                    } else {
                        val dbSha = documentRepository?.getDocumentById(session.docId)?.expectedSha256
                        if (!dbSha.isNullOrBlank()) {
                            dbSha
                        } else if (file != null && file.exists()) {
                            try {
                                Sha256StreamCalculator.calculateFileSha256(file)
                            } catch (_: Exception) {
                                ""
                            }
                        } else {
                            ""
                        }
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
                            ?: NetworkAddressHelper.getDefaultRelayUrl(endpoint = "v1/stream")

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
                callback.onError("TRANSFER_EXCEPTION", e.message ?: "Lỗi truyền tải không xác định")
            }
        }

        activeJobs[session.sessionId] = transferJob

        awaitClose {
            networkJob.cancel()
            transferJob.cancel()
            activeSessions.remove(session.sessionId)
            activeJobs.remove(session.sessionId)
            isPausedMap.remove(session.sessionId)
            sessionTeamMap.remove(session.sessionId)
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
        sessionTeamMap.remove(sessionId)
        transferRepository.updateSessionStatus(sessionId, SessionStatus.CANCELLED)
    }

    /**
     * Cưỡng bức hủy tất cả các phiên truyền tải tệp thuộc về một nhóm khi nhóm bị giải tán (SDD 7.6).
     */
    suspend fun cancelSessionsForTeam(teamId: String) = withContext(ioDispatcher) {
        val targetSessions = activeSessions.values.filter { sessionTeamMap[it.sessionId] == teamId }
        for (session in targetSessions) {
            cancelSession(session.sessionId)
        }
        val repoSessions = transferRepository.getActiveSessions().firstOrNull() ?: emptyList()
        for (session in repoSessions) {
            if (sessionTeamMap[session.sessionId] == teamId &&
                session.status != SessionStatus.COMPLETED &&
                session.status != SessionStatus.CANCELLED
            ) {
                cancelSession(session.sessionId)
            }
        }
    }

    /**
     * Cưỡng bức hủy phiên truyền tải của thành viên bị thu hồi quyền trong < 200 ms (SRS FR-MEM-04, SDD 7.6).
     */
    suspend fun cancelSessionForMember(memberId: String) = withContext(ioDispatcher) {
        val targetSessions = activeSessions.values.filter { it.peerMemberId == memberId }
        for (session in targetSessions) {
            cancelSession(session.sessionId)
        }
        val repoSessions = transferRepository.getActiveSessions().firstOrNull() ?: emptyList()
        for (session in repoSessions) {
            if (session.peerMemberId == memberId && session.status != SessionStatus.COMPLETED && session.status != SessionStatus.CANCELLED) {
                cancelSession(session.sessionId)
            }
        }
    }

    /**
     * Lấy phiên truyền tải hiện tại nếu đang hoạt động.
     */
    fun getActiveSession(sessionId: String): TransferSession? = activeSessions[sessionId]

    /**
     * Đăng ký phiên truyền tải hoạt động và ánh xạ với nhóm (phục vụ test hoặc khởi tạo trước).
     */
    fun registerActiveSession(session: TransferSession, teamId: String = "") {
        activeSessions[session.sessionId] = session
        if (teamId.isNotBlank()) {
            sessionTeamMap[session.sessionId] = teamId
        }
    }
}
