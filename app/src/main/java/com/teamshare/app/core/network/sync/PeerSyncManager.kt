package com.teamshare.app.core.network.sync

import android.content.Context
import android.net.Uri
import com.teamshare.app.core.network.relay.RelayWebSocketConnection
import com.teamshare.app.core.platform.network.NetworkAddressHelper
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.QrInvitePayload
import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.IDocumentRepository
import com.teamshare.app.domain.repository.IPeerSyncGateway
import com.teamshare.app.domain.repository.ITeamRepository
import com.teamshare.app.domain.repository.JoinSyncResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Trợ thủ ghi log an toàn, tự động tương thích môi trường Android Runtime và JVM Unit Test.
 */
private object SyncLog {
    fun d(tag: String, msg: String) {
        try {
            android.util.Log.d(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] $msg")
        }
    }

    fun w(tag: String, msg: String) {
        try {
            android.util.Log.w(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] WARNING: $msg")
        }
    }
}

/**
 * Quản lý kênh đồng bộ trạng thái P2P (Peer-to-Peer State Synchronization).
 * Hỗ trợ đồng bộ hai chiều (Dual-Channel):
 * 1. Direct LAN Socket: Khi các thiết bị cùng nằm trong một mạng Wi-Fi LAN.
 * 2. Zero-Knowledge Relay WebSocket: Khi một trong hai thiết bị là Android Emulator (NAT) hoặc khác mạng.
 */
class PeerSyncManager(
    private val teamRepository: ITeamRepository,
    private val documentRepository: IDocumentRepository,
    private val auditRepository: IAuditRepository? = null,
    private val keyManager: IKeyManager? = null,
    private val context: Context? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IPeerSyncGateway {

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var relayJob: Job? = null
    private var activeRelayConnection: RelayWebSocketConnection? = null

    @Volatile
    var currentListeningPort: Int = 0
        private set

    @Volatile
    var activeTeamId: String? = null
        private set

    private val knownPeers = ConcurrentHashMap<String, Pair<String, Int>>() // memberId -> (host, port)
    private val processedFileRequests = ConcurrentHashMap<String, Long>() // streamId -> timestamp
    private val teamRelayUrls = ConcurrentHashMap<String, String>() // teamId -> relayWsUrl

    var onFileRequestedListener: ((req: FileRequestMessage, doc: SharedDocument, file: java.io.File) -> Unit)? = null

    /**
     * Ghi nhớ Relay URL cho từng nhóm và đồng bộ vào cấu hình mạng chung.
     */
    fun rememberTeamRelayUrl(teamId: String, relayUrl: String) {
        teamRelayUrls[teamId] = relayUrl
        val host = relayUrl.removePrefix("ws://").removePrefix("wss://")
            .substringBefore("/").substringBefore(":")
        if (host.isNotBlank() && host != "10.0.2.2" && host != "127.0.0.1") {
            context?.let { NetworkAddressHelper.saveConfiguredRelayHost(it, host) }
                ?: run { NetworkAddressHelper.configuredRelayHost = host }
        }
    }

    /**
     * Lấy Relay URL đã được xác thực của nhóm.
     */
    fun getRelayUrlForTeam(teamId: String): String? = teamRelayUrls[teamId]

    /**
     * Chủ động phát yêu cầu CATALOG_QUERY tới các peer và Relay Room để kéo danh mục tài liệu mới nhất.
     */
    suspend fun queryCatalog(teamId: String) = withContext(ioDispatcher) {
        val queryMsg = CatalogQueryMessage(teamId = teamId).toJson()
        val activeConn = activeRelayConnection
        if (activeConn != null && activeConn.isOpen()) {
            activeConn.sendText(queryMsg)
            SyncLog.d("TeamShareSync", "Đã gửi CATALOG_QUERY qua activeRelayConnection cho nhóm $teamId")
        }
        knownPeers.values.forEach { (host, port) ->
            launch(ioDispatcher) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 2000)
                        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                        writer.write(queryMsg)
                        writer.newLine()
                        writer.flush()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Khởi động máy chủ lắng nghe đồng bộ cục bộ và đăng ký kênh Relay nếu có relayUrl.
     * @return Cổng TCP thực tế đang lắng nghe
     */
    fun startSyncServer(
        teamId: String,
        preferredPort: Int = NetworkAddressHelper.DEFAULT_SYNC_PORT,
        relayUrl: String? = null,
        scope: CoroutineScope
    ): Int {
        if (isRunning.get() && activeTeamId == teamId && currentListeningPort > 0) {
            return currentListeningPort
        }

        stopSyncServer()
        activeTeamId = teamId
        isRunning.set(true)

        // 1. Mở ServerSocket LAN
        var portToTry = preferredPort
        var boundSocket: ServerSocket? = null
        for (attempt in 0..10) {
            try {
                boundSocket = ServerSocket(portToTry)
                break
            } catch (_: Exception) {
                portToTry++
            }
        }

        if (boundSocket == null) {
            boundSocket = ServerSocket(0) // Tự cấp phát cổng ngẫu nhiên nếu các cổng ưu tiên bị chiếm
        }

        serverSocket = boundSocket
        currentListeningPort = boundSocket.localPort

        serverJob = scope.launch(ioDispatcher) {
            runLanServerLoop(boundSocket, teamId)
        }

        // 2. Mở kết nối nền tới Relay nếu có relayUrl (hoặc lấy từ teamRelayUrls)
        val effectiveRelayUrl = relayUrl?.takeIf { it.isNotBlank() } ?: teamRelayUrls[teamId]
        if (!effectiveRelayUrl.isNullOrBlank()) {
            relayJob = scope.launch(ioDispatcher) {
                runRelaySyncLoop(effectiveRelayUrl, teamId)
            }
        }

        return currentListeningPort
    }

    /**
     * Dừng toàn bộ máy chủ lắng nghe và đóng các kết nối.
     */
    fun stopSyncServer() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null

        relayJob?.cancel()
        relayJob = null
        try {
            activeRelayConnection?.close()
        } catch (_: Exception) {}
        activeRelayConnection = null

        currentListeningPort = 0
        activeTeamId = null
    }

    /**
     * Vòng lặp nhận kết nối LAN Socket từ các peer.
     */
    private suspend fun runLanServerLoop(server: ServerSocket, teamId: String) = withContext(ioDispatcher) {
        while (isActive && isRunning.get()) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }

            launch(ioDispatcher) {
                handleLanSocketConnection(socket, teamId)
            }
        }
    }

    private suspend fun handleLanSocketConnection(socket: Socket, teamId: String) {
        try {
            socket.soTimeout = 10_000 // 10 giây
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            val line = reader.readLine() ?: return
            val responseJson = processIncomingMessage(line, teamId, socket.inetAddress?.hostAddress)

            if (responseJson != null) {
                writer.write(responseJson)
                writer.newLine()
                writer.flush()
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Vòng lặp lắng nghe bản tin đồng bộ chuyển tiếp qua WebSocket Relay.
     */
    private suspend fun runRelaySyncLoop(relayUrl: String, teamId: String) {
        val wsUrl = if (relayUrl.contains("?teamId=")) relayUrl else {
            val sep = if (relayUrl.contains("?")) "&" else "?"
            "$relayUrl${sep}teamId=$teamId"
        }

        while (isRunning.get()) {
            var conn: RelayWebSocketConnection? = null
            try {
                conn = RelayWebSocketConnection()
                activeRelayConnection = conn
                SyncLog.d("TeamShareSync", "Đang kết nối tới Relay Sync Room: $wsUrl")
                conn.connect(wsUrl, timeoutMs = 10_000L)
                SyncLog.d("TeamShareSync", "Đã kết nối Relay Sync Room thành công cho nhóm $teamId")

                // Vừa kết nối thành công -> gửi yêu cầu CATALOG_QUERY để đồng bộ ngay danh mục tệp nhóm
                val queryMsg = CatalogQueryMessage(teamId = teamId).toJson()
                conn.sendText(queryMsg)

                while (isRunning.get() && conn.isOpen()) {
                    val incoming = try {
                        conn.receiveText(timeoutMs = 30_000L)
                    } catch (e: Exception) {
                        if (!conn.isOpen() || !isRunning.get()) {
                            SyncLog.w("TeamShareSync", "Kênh Relay Sync bị ngắt kết nối (${e.message}), chuẩn bị tái kết nối...")
                            break
                        }
                        null
                    } ?: continue

                    val response = processIncomingMessage(incoming, teamId, null)
                    if (response != null && conn.isOpen()) {
                        conn.sendText(response)
                    }
                }
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                SyncLog.w("TeamShareSync", "Lỗi trong Relay Sync Loop: ${e.message}. Tự động tái thử sau 2.5s...")
                delay(2500L)
            } finally {
                try { conn?.close() } catch (_: Exception) {}
                if (activeRelayConnection === conn) {
                    activeRelayConnection = null
                }
            }
        }
    }

    /**
     * Xử lý bản tin đến: JOIN_REQUEST, CATALOG_ANNOUNCE, CATALOG_QUERY, CATALOG_SYNC_RESPONSE, FILE_REQUEST.
     */
    private suspend fun processIncomingMessage(jsonStr: String, currentTeamId: String, remoteHost: String?): String? {
        val root = try {
            JSONObject(jsonStr)
        } catch (_: Exception) {
            return null
        }

        return when (root.optString("action")) {
            JoinRequestMessage.ACTION -> {
                val req = JoinRequestMessage.fromJson(jsonStr) ?: return null
                if (req.teamId != currentTeamId) {
                    SyncLog.w("TeamShareSync", "Bỏ qua JOIN_REQUEST do teamId không khớp (${req.teamId} != $currentTeamId)")
                    return null
                }

                SyncLog.d("TeamShareSync", "Trưởng nhóm nhận được JOIN_REQUEST từ thành viên ${req.memberName} (${req.memberId})")

                // 1. Lưu thành viên mới vào CSDL của máy này (ký cấp chứng thư hợp chuẩn E2EE nếu có Master Key)
                val masterKeyAlias = IKeyManager.MASTER_KEY_PREFIX + currentTeamId
                val memberClaim = "${req.teamId}:${req.memberId}:${req.deviceFingerprint}:MEMBER".toByteArray(Charsets.UTF_8)
                val signedCred = try {
                    keyManager?.signData(masterKeyAlias, memberClaim)?.joinToString("") { "%02x".format(it) }
                } catch (_: Exception) { null }
                val credToUse = signedCred ?: req.signature

                val member = TeamMember(
                    memberId = req.memberId,
                    teamId = req.teamId,
                    memberName = req.memberName,
                    deviceFingerprint = req.deviceFingerprint,
                    role = MemberRole.MEMBER,
                    credentialData = credToUse,
                    isActive = true
                )
                teamRepository.addMember(member)
                SyncLog.d("TeamShareSync", "Đã lưu thành viên ${req.memberName} vào CSDL Room của Trưởng nhóm thành công!")

                // Ghi nhớ địa chỉ của peer để broadcast
                val host = req.clientHost ?: remoteHost
                if (!host.isNullOrBlank() && req.clientPort > 0) {
                    knownPeers[req.memberId] = host to req.clientPort
                }

                // 2. Ghi nhật ký kiểm toán MEMBER_JOINED
                auditRepository?.logEvent(
                    AuditLog(
                        teamId = req.teamId,
                        actorId = req.memberId,
                        eventType = AuditEventType.MEMBER_JOINED,
                        severity = AuditSeverity.INFO,
                        details = "Thành viên ${req.memberName} (Fingerprint: ${req.deviceFingerprint}) đã gia nhập nhóm thành công"
                    )
                )

                // 3. Chuẩn bị danh sách thành viên và tài liệu hiện tại để trả về
                val allMembers = teamRepository.getMembersByTeam(currentTeamId).firstOrNull() ?: emptyList()
                val allDocs = documentRepository.getDocumentsByTeam(currentTeamId).firstOrNull() ?: emptyList()

                val memberDtos = allMembers.map {
                    MemberSyncDto(it.memberId, it.memberName, it.deviceFingerprint, it.role.name, it.credentialData)
                }
                val docDtos = allDocs.map {
                    DocumentSyncDto(
                        docId = it.docId,
                        fileName = it.fileName,
                        fileSize = it.fileSize,
                        mimeType = it.mimeType,
                        expectedSha256 = it.expectedSha256,
                        publisherId = it.publisherId,
                        requireBiometric = it.requireBiometric,
                        publisherHost = remoteHost,
                        publisherPort = currentListeningPort
                    )
                }

                JoinResponseMessage(
                    status = "OK",
                    message = "Gia nhập nhóm thành công",
                    members = memberDtos,
                    documents = docDtos
                ).toJson()
            }

            CatalogQueryMessage.ACTION -> {
                val query = CatalogQueryMessage.fromJson(jsonStr) ?: return null
                if (query.teamId != currentTeamId) return null
                SyncLog.d("TeamShareSync", "Nhận CATALOG_QUERY từ peer. Phản hồi danh mục tài liệu & danh bạ thành viên hiện tại...")
                val allDocs = documentRepository.getDocumentsByTeam(currentTeamId).firstOrNull() ?: emptyList()
                val allMembers = teamRepository.getMembersByTeam(currentTeamId).firstOrNull() ?: emptyList()
                val docDtos = allDocs.map {
                    DocumentSyncDto(
                        docId = it.docId,
                        fileName = it.fileName,
                        fileSize = it.fileSize,
                        mimeType = it.mimeType,
                        expectedSha256 = it.expectedSha256,
                        publisherId = it.publisherId,
                        requireBiometric = it.requireBiometric,
                        publisherHost = remoteHost,
                        publisherPort = currentListeningPort
                    )
                }
                val memberDtos = allMembers.map {
                    MemberSyncDto(it.memberId, it.memberName, it.deviceFingerprint, it.role.name, it.credentialData)
                }
                CatalogSyncResponseMessage(
                    teamId = currentTeamId,
                    documents = docDtos,
                    members = memberDtos
                ).toJson()
            }

            CatalogSyncResponseMessage.ACTION -> {
                val syncResp = CatalogSyncResponseMessage.fromJson(jsonStr) ?: return null
                if (syncResp.teamId != currentTeamId) return null
                SyncLog.d("TeamShareSync", "Nhận CATALOG_SYNC_RESPONSE chứa ${syncResp.documents.size} tài liệu, ${syncResp.members.size} thành viên từ peer")

                // Tự động đồng bộ và chữa lành chứng thư thành viên nếu nhận được chứng thư mới từ Owner
                val currentMembers = teamRepository.getMembersByTeam(syncResp.teamId).firstOrNull() ?: emptyList()
                val currentMemberMap = currentMembers.associateBy { it.memberId }

                syncResp.members.forEach { m ->
                    val existing = currentMemberMap[m.memberId]
                    val role = try { MemberRole.valueOf(m.role) } catch (_: Exception) { MemberRole.MEMBER }
                    if (existing == null || (m.credentialData.isNotBlank() && existing.credentialData != m.credentialData)) {
                        val member = TeamMember(
                            memberId = m.memberId,
                            teamId = syncResp.teamId,
                            memberName = m.memberName,
                            deviceFingerprint = m.deviceFingerprint,
                            role = role,
                            credentialData = m.credentialData,
                            isActive = true
                        )
                        teamRepository.addMember(member)
                        SyncLog.d("TeamShareSync", "Đã cập nhật thông tin/chứng thư cho thành viên ${m.memberName} (${m.memberId})")
                    }
                }

                syncResp.documents.forEach { d ->
                    val existing = documentRepository.getDocumentById(d.docId)
                    if (existing == null) {
                        val newDoc = SharedDocument(
                            docId = d.docId,
                            teamId = syncResp.teamId,
                            publisherId = d.publisherId,
                            fileName = d.fileName,
                            fileSize = d.fileSize,
                            mimeType = d.mimeType,
                            expectedSha256 = d.expectedSha256,
                            requireBiometric = d.requireBiometric,
                            localUri = null,
                            downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                            publisherHost = d.publisherHost ?: remoteHost,
                            publisherPort = d.publisherPort
                        )
                        documentRepository.publishDocument(newDoc)
                        auditRepository?.logEvent(
                            AuditLog(
                                teamId = syncResp.teamId,
                                actorId = d.publisherId,
                                eventType = AuditEventType.DOCUMENT_PUBLISHED,
                                severity = AuditSeverity.INFO,
                                details = "Công bố tài liệu ${d.fileName} (${d.fileSize} bytes, SHA-256: ${d.expectedSha256}) bởi ${d.publisherId.take(8)}"
                            )
                        )
                    }
                }
                null
            }

            CatalogAnnounceMessage.ACTION -> {
                val announce = CatalogAnnounceMessage.fromJson(jsonStr) ?: return null
                if (announce.teamId != currentTeamId) {
                    SyncLog.w("TeamShareSync", "Bỏ qua CATALOG_ANNOUNCE do teamId không khớp (${announce.teamId} != $currentTeamId)")
                    return null
                }

                SyncLog.d("TeamShareSync", "Nhận CATALOG_ANNOUNCE: ${announce.fileName} (${announce.fileSize} bytes) từ ${announce.publisherName}")

                if (!announce.publisherHost.isNullOrBlank() && announce.publisherPort > 0) {
                    knownPeers[announce.publisherId] = announce.publisherHost to announce.publisherPort
                }

                // Kiểm tra xem tài liệu đã có trong CSDL chưa
                val existing = documentRepository.getDocumentById(announce.docId)
                if (existing == null) {
                    val newDoc = SharedDocument(
                        docId = announce.docId,
                        teamId = announce.teamId,
                        publisherId = announce.publisherId,
                        fileName = announce.fileName,
                        fileSize = announce.fileSize,
                        mimeType = announce.mimeType,
                        expectedSha256 = announce.expectedSha256,
                        requireBiometric = announce.requireBiometric,
                        localUri = null,
                        downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                        publisherHost = announce.publisherHost ?: remoteHost,
                        publisherPort = announce.publisherPort
                    )
                    documentRepository.publishDocument(newDoc)
                    SyncLog.d("TeamShareSync", "Đã lưu tài liệu mới ${announce.fileName} vào CSDL Room của peer!")

                    val publisherLabel = if (announce.publisherName.isNotBlank()) announce.publisherName else announce.publisherId.take(8)
                    auditRepository?.logEvent(
                        AuditLog(
                            teamId = announce.teamId,
                            actorId = announce.publisherId,
                            eventType = AuditEventType.DOCUMENT_PUBLISHED,
                            severity = AuditSeverity.INFO,
                            details = "Công bố tài liệu ${announce.fileName} (${announce.fileSize} bytes, SHA-256: ${announce.expectedSha256}) bởi $publisherLabel"
                        )
                    )
                }
                null
            }

            FileRequestMessage.ACTION -> {
                val req = FileRequestMessage.fromJson(jsonStr) ?: return null
                if (req.teamId != currentTeamId) return null

                val lastProcessed = processedFileRequests[req.streamId] ?: 0L
                val now = System.currentTimeMillis()
                if (now - lastProcessed < 5000L) {
                    SyncLog.d("TeamShareSync", "Bỏ qua FILE_REQUEST trùng lặp trong 5s cho streamId=${req.streamId}")
                    return null
                }
                processedFileRequests[req.streamId] = now

                SyncLog.d("TeamShareSync", "Nhận FILE_REQUEST cho docId=${req.docId} từ ${req.requesterName} (streamId=${req.streamId})")
                val doc = documentRepository.getDocumentById(req.docId)
                if (doc != null && !doc.localUri.isNullOrBlank()) {
                    var file = java.io.File(doc.localUri)
                    if (!file.exists() && doc.localUri.startsWith("content://")) {
                        try {
                            val uri = Uri.parse(doc.localUri)
                            val cacheDir = context?.cacheDir ?: java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp")
                            val tempFile = java.io.File(cacheDir, "${doc.docId}_${doc.fileName}")
                            context?.contentResolver?.openInputStream(uri)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            if (tempFile.exists()) {
                                file = tempFile
                            }
                        } catch (e: Exception) {
                            SyncLog.w("TeamShareSync", "Không thể trích xuất tệp từ content URI: ${e.message}")
                        }
                    }

                    if (file.exists()) {
                        SyncLog.d("TeamShareSync", "Tìm thấy file nguồn ${file.absolutePath}, kích hoạt truyền tải cho peer...")
                        onFileRequestedListener?.invoke(req, doc, file)
                    } else {
                        SyncLog.w("TeamShareSync", "Không tìm thấy file nguồn tại đường dẫn: ${doc.localUri}")
                    }
                } else {
                    SyncLog.w("TeamShareSync", "Bản ghi tài liệu ${req.docId} không tồn tại hoặc localUri null")
                }
                null
            }

            else -> null
        }
    }

    /**
     * Gửi yêu cầu bắt tay JOIN_REQUEST từ Member sang Owner sau khi quét mã QR (IPeerSyncGateway).
     */
    override suspend fun sendJoinRequest(
        qrPayload: QrInvitePayload,
        member: TeamMember
    ): Result<JoinSyncResponse> = withContext(ioDispatcher) {
        val myIp = NetworkAddressHelper.getLocalIpv4Address()
        val request = JoinRequestMessage(
            teamId = qrPayload.teamId,
            invitationToken = qrPayload.invitationToken,
            memberId = member.memberId,
            memberName = member.memberName,
            deviceFingerprint = member.deviceFingerprint,
            memberPublicKey = "",
            signature = qrPayload.signature,
            clientHost = myIp,
            clientPort = currentListeningPort
        )

        val ownerHost = qrPayload.ownerHost
        val ownerPort = qrPayload.ownerPort
        val relayUrl = qrPayload.relayUrl ?: NetworkAddressHelper.getDefaultRelayUrl(ownerHost)

        SyncLog.d("TeamShareSync", "Bắt đầu bắt tay JOIN_REQUEST với Trưởng nhóm (LAN: $ownerHost:$ownerPort | Relay: $relayUrl)")

        // 1. Thử gửi trực tiếp qua LAN Socket tới máy Owner
        if (!ownerHost.isNullOrBlank() && ownerPort > 0) {
            try {
                SyncLog.d("TeamShareSync", "Thử kết nối LAN Socket tới $ownerHost:$ownerPort...")
                val response = sendJoinRequestViaSocket(ownerHost, ownerPort, request)
                if (response != null && response.status == "OK") {
                    SyncLog.d("TeamShareSync", "Kết nối LAN Socket thành công! Đã nhận phản hồi từ Trưởng nhóm.")
                    if (!qrPayload.relayUrl.isNullOrBlank()) {
                        rememberTeamRelayUrl(qrPayload.teamId, qrPayload.relayUrl)
                    }
                    applySyncResponse(qrPayload.teamId, response)
                    return@withContext Result.success(response.toJoinSyncResponse(qrPayload.teamId))
                }
            } catch (e: Exception) {
                SyncLog.w("TeamShareSync", "Kết nối LAN Socket thất bại (${e.message}), chuyển sang dự phòng Relay Server...")
            }
        }

        // 2. Thử gửi qua Relay Server nếu có relayUrl
        try {
            SyncLog.d("TeamShareSync", "Thử kết nối qua Zero-Knowledge Relay: $relayUrl...")
            val response = sendJoinRequestViaRelay(relayUrl, qrPayload.teamId, request)
            if (response != null && response.status == "OK") {
                SyncLog.d("TeamShareSync", "Kết nối Relay thành công! Đã nhận phản hồi từ Trưởng nhóm.")
                rememberTeamRelayUrl(qrPayload.teamId, relayUrl)
                applySyncResponse(qrPayload.teamId, response)
                return@withContext Result.success(response.toJoinSyncResponse(qrPayload.teamId))
            } else if (response != null) {
                return@withContext Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            SyncLog.w("TeamShareSync", "Kết nối Relay thất bại: ${e.message}")
        }

        Result.failure(
            Exception("Không thể kết nối đồng bộ tới Trưởng nhóm qua LAN ($ownerHost:$ownerPort) hoặc Relay Server ($relayUrl). Vui lòng đảm bảo hai máy cùng mạng Wi-Fi hoặc Relay Server đang hoạt động.")
        )
    }

    private fun JoinResponseMessage.toJoinSyncResponse(teamId: String): JoinSyncResponse {
        val memberList = members.map { m ->
            val role = try { MemberRole.valueOf(m.role) } catch (_: Exception) { MemberRole.MEMBER }
            TeamMember(
                memberId = m.memberId,
                teamId = teamId,
                memberName = m.memberName,
                deviceFingerprint = m.deviceFingerprint,
                role = role,
                credentialData = m.credentialData,
                isActive = true
            )
        }
        val docList = documents.map { d ->
            SharedDocument(
                docId = d.docId,
                teamId = teamId,
                publisherId = d.publisherId,
                fileName = d.fileName,
                fileSize = d.fileSize,
                mimeType = d.mimeType,
                expectedSha256 = d.expectedSha256,
                requireBiometric = d.requireBiometric,
                localUri = null,
                downloadStatus = DownloadStatus.NOT_DOWNLOADED
            )
        }
        return JoinSyncResponse(
            status = status,
            message = message,
            members = memberList,
            documents = docList
        )
    }

    private fun sendJoinRequestViaSocket(host: String, port: Int, request: JoinRequestMessage): JoinResponseMessage? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2500) // 2.5s timeout
            socket.soTimeout = 5000

            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

            writer.write(request.toJson())
            writer.newLine()
            writer.flush()

            val line = reader.readLine() ?: return null
            return JoinResponseMessage.fromJson(line)
        }
    }

    private suspend fun sendJoinRequestViaRelay(relayUrl: String, teamId: String, request: JoinRequestMessage): JoinResponseMessage? {
        val wsUrl = if (relayUrl.contains("?teamId=")) relayUrl else {
            val sep = if (relayUrl.contains("?")) "&" else "?"
            "$relayUrl${sep}teamId=$teamId"
        }
        val conn = RelayWebSocketConnection()
        try {
            conn.connect(wsUrl, timeoutMs = 8000L)
            conn.sendText(request.toJson())

            val reply = try {
                conn.receiveText(timeoutMs = 8000L)
            } catch (_: Exception) {
                null
            } ?: return null

            return JoinResponseMessage.fromJson(reply)
        } finally {
            try {
                conn.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Cập nhật danh bạ thành viên và danh mục tài liệu nhận được từ phản hồi đồng bộ vào CSDL cục bộ.
     */
    private suspend fun applySyncResponse(teamId: String, response: JoinResponseMessage) {
        // Cập nhật các thành viên khác trong nhóm
        response.members.forEach { m ->
            val role = try { MemberRole.valueOf(m.role) } catch (_: Exception) { MemberRole.MEMBER }
            val member = TeamMember(
                memberId = m.memberId,
                teamId = teamId,
                memberName = m.memberName,
                deviceFingerprint = m.deviceFingerprint,
                role = role,
                credentialData = m.credentialData,
                isActive = true
            )
            teamRepository.addMember(member)
        }

        // Cập nhật các tài liệu hiện có trong nhóm
        response.documents.forEach { d ->
            val existing = documentRepository.getDocumentById(d.docId)
            if (existing == null) {
                val doc = SharedDocument(
                    docId = d.docId,
                    teamId = teamId,
                    publisherId = d.publisherId,
                    fileName = d.fileName,
                    fileSize = d.fileSize,
                    mimeType = d.mimeType,
                    expectedSha256 = d.expectedSha256,
                    requireBiometric = d.requireBiometric,
                    localUri = null,
                    downloadStatus = DownloadStatus.NOT_DOWNLOADED
                )
                documentRepository.publishDocument(doc)
            }
        }
    }

    /**
     * Phát sóng thông báo tài liệu mới tới các peer trong nhóm.
     */
    suspend fun broadcastCatalogAnnounce(
        teamId: String,
        document: SharedDocument,
        publisherName: String,
        relayUrl: String? = null
    ) = withContext(ioDispatcher) {
        val myIp = NetworkAddressHelper.getLocalIpv4Address()
        val message = CatalogAnnounceMessage(
            teamId = teamId,
            docId = document.docId,
            publisherId = document.publisherId,
            publisherName = publisherName,
            fileName = document.fileName,
            fileSize = document.fileSize,
            mimeType = document.mimeType,
            expectedSha256 = document.expectedSha256,
            requireBiometric = document.requireBiometric,
            publisherHost = myIp,
            publisherPort = currentListeningPort
        )

        val jsonStr = message.toJson()

        // 1. Gửi qua socket trực tiếp tới tất cả known peers
        knownPeers.values.forEach { (host, port) ->
            launch(ioDispatcher) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 2000)
                        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                        writer.write(jsonStr)
                        writer.newLine()
                        writer.flush()
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Gửi qua activeRelayConnection hoặc kết nối tới Relay Room
        val activeConn = activeRelayConnection
        var sentOverActive = false
        if (activeConn != null && activeConn.isOpen()) {
            sentOverActive = activeConn.sendText(jsonStr)
            SyncLog.d("TeamShareSync", "Gửi CATALOG_ANNOUNCE qua activeRelayConnection: $sentOverActive")
        }

        if (!sentOverActive) {
            val targetRelayUrl = relayUrl ?: NetworkAddressHelper.getDefaultRelayUrl(endpoint = "v1/sync")
            val wsUrl = if (targetRelayUrl.contains("?teamId=")) targetRelayUrl else "$targetRelayUrl?teamId=$teamId"
            launch(ioDispatcher) {
                try {
                    SyncLog.d("TeamShareSync", "Dự phòng: Mở kết nối tạm tới $wsUrl để gửi CATALOG_ANNOUNCE...")
                    val conn = RelayWebSocketConnection()
                    conn.connect(wsUrl, timeoutMs = 5000L)
                    conn.sendText(jsonStr)
                    delay(500L)
                    conn.close()
                    SyncLog.d("TeamShareSync", "Đã gửi CATALOG_ANNOUNCE qua kết nối tạm thành công!")
                } catch (e: Exception) {
                    SyncLog.w("TeamShareSync", "Không thể gửi CATALOG_ANNOUNCE qua kết nối tạm: ${e.message}")
                }
            }
        }
    }

    /**
     * Gửi yêu cầu tải tệp (FILE_REQUEST) tới người công bố qua Relay Room và LAN.
     */
    suspend fun requestFileDownload(
        teamId: String,
        docId: String,
        streamId: String,
        requesterId: String,
        requesterName: String,
        relayUrl: String? = null
    ) = withContext(ioDispatcher) {
        val message = FileRequestMessage(
            teamId = teamId,
            docId = docId,
            requesterId = requesterId,
            requesterName = requesterName,
            streamId = streamId
        )
        val jsonStr = message.toJson()
        SyncLog.d("TeamShareSync", "Phát FILE_REQUEST cho docId=$docId (streamId=$streamId) từ $requesterName...")

        // 1. Gửi qua LAN tới các peer đã biết
        knownPeers.values.forEach { (host, port) ->
            launch(ioDispatcher) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 2000)
                        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                        writer.write(jsonStr)
                        writer.newLine()
                        writer.flush()
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Gửi qua Relay Sync Room
        val activeConn = activeRelayConnection
        var sent = false
        if (activeConn != null && activeConn.isOpen()) {
            sent = activeConn.sendText(jsonStr)
            SyncLog.d("TeamShareSync", "Gửi FILE_REQUEST qua activeRelayConnection: $sent")
        }

        if (!sent) {
            val targetRelayUrl = relayUrl ?: NetworkAddressHelper.getDefaultRelayUrl(endpoint = "v1/sync")
            val wsUrl = if (targetRelayUrl.contains("?teamId=")) targetRelayUrl else "$targetRelayUrl?teamId=$teamId"
            launch(ioDispatcher) {
                try {
                    val conn = RelayWebSocketConnection()
                    conn.connect(wsUrl, timeoutMs = 5000L)
                    conn.sendText(jsonStr)
                    delay(500L)
                    conn.close()
                    SyncLog.d("TeamShareSync", "Đã gửi FILE_REQUEST qua kết nối tạm thành công!")
                } catch (e: Exception) {
                    SyncLog.w("TeamShareSync", "Lỗi gửi FILE_REQUEST qua Relay tạm: ${e.message}")
                }
            }
        }
    }
}
