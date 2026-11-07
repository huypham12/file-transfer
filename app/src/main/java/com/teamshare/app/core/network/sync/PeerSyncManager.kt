package com.teamshare.app.core.network.sync

import android.content.Context
import android.net.Uri
import com.teamshare.app.core.network.relay.RelayWebSocketConnection
import com.teamshare.app.core.platform.network.NetworkAddressHelper
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.data.local.db.entity.RevocationEntity
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.QrInvitePayload
import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.domain.model.TeamFolder
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.IDocumentRepository
import com.teamshare.app.domain.repository.IFolderRepository
import com.teamshare.app.domain.repository.IPeerSyncGateway
import com.teamshare.app.domain.repository.ITeamRepository
import com.teamshare.app.domain.repository.JoinSyncResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thông tin trạng thái hiện diện (Presence) thời gian thực của một peer trong nhóm.
 */
data class PeerPresenceInfo(
    val memberId: String,
    val memberName: String,
    val lastSeenTimestamp: Long,
    val isOnline: Boolean
)

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val folderRepository: IFolderRepository? = null
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

    private val _peerPresenceFlow = MutableStateFlow<Map<String, PeerPresenceInfo>>(emptyMap())
    val peerPresenceFlow: StateFlow<Map<String, PeerPresenceInfo>> = _peerPresenceFlow.asStateFlow()

    private val peerPresenceMap = ConcurrentHashMap<String, PeerPresenceInfo>()
    private val pendingProbes = ConcurrentHashMap<String, CompletableDeferred<FileProbeResponseMessage>>()
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<FileRequestAckMessage>>()
    private var heartbeatJob: Job? = null

    @Volatile
    var myMemberId: String? = null
        private set

    @Volatile
    var myMemberName: String? = null
        private set

    var onFileRequestedListener: ((req: FileRequestMessage, doc: SharedDocument, file: java.io.File) -> Unit)? = null
    var onSessionAbortRequested: ((memberId: String?, teamId: String?) -> Unit)? = null

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
     * Cập nhật danh tính thành viên cục bộ và thông báo ngay tới Relay Server.
     */
    fun updateMemberIdentity(memberId: String, memberName: String, teamId: String) {
        myMemberId = memberId
        myMemberName = memberName
        activeTeamId = teamId
        val conn = activeRelayConnection
        if (conn != null && conn.isOpen()) {
            val myIp = NetworkAddressHelper.getLocalIpv4Address()
            val hbMsg = PeerHeartbeatMessage(
                teamId = teamId,
                memberId = memberId,
                memberName = memberName,
                clientHost = myIp,
                clientPort = currentListeningPort
            ).toJson()
            conn.sendText(hbMsg)
            SyncLog.d("TeamShareSync", "Đã gửi cập nhật danh tính thành viên lên Relay: $memberId ($memberName)")
        }
    }

    /**
     * Lấy địa chỉ IP và cổng LAN đã biết của một peer trong nhóm.
     */
    fun getPeerHostAndPort(peerId: String): Pair<String, Int>? = knownPeers[peerId]

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
        scope: CoroutineScope,
        memberId: String? = null,
        memberName: String? = null
    ): Int {
        if (memberId != null) myMemberId = memberId
        if (memberName != null) myMemberName = memberName

        if (isRunning.get() && activeTeamId == teamId && currentListeningPort > 0) {
            return currentListeningPort
        }

        stopSyncServer()
        activeTeamId = teamId
        myMemberId = memberId
        myMemberName = memberName
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

        // 3. Vòng lặp phát nhịp tim định kỳ (Heartbeat - mỗi 15 giây)
        heartbeatJob = scope.launch(ioDispatcher) {
            while (isActive && isRunning.get()) {
                delay(15_000L)
                val currentTeam = activeTeamId ?: continue
                val currentMem = myMemberId ?: continue
                val currentName = myMemberName ?: "Thành viên"
                val myIp = NetworkAddressHelper.getLocalIpv4Address()
                val hbMsg = PeerHeartbeatMessage(
                    teamId = currentTeam,
                    memberId = currentMem,
                    memberName = currentName,
                    clientHost = myIp,
                    clientPort = currentListeningPort
                ).toJson()

                activeRelayConnection?.let { conn ->
                    if (conn.isOpen()) {
                        conn.sendText(hbMsg)
                    }
                }

                // Dọn dẹp trạng thái presence: nếu quá 40 giây không có heartbeat, đánh dấu ngoại tuyến
                val now = System.currentTimeMillis()
                var changed = false
                peerPresenceMap.forEach { (id, presence) ->
                    if (presence.isOnline && (now - presence.lastSeenTimestamp > 40_000L)) {
                        peerPresenceMap[id] = presence.copy(isOnline = false)
                        changed = true
                    }
                }
                if (changed) {
                    _peerPresenceFlow.value = peerPresenceMap.toMap()
                }
            }
        }

        return currentListeningPort
    }

    /**
     * Dừng toàn bộ máy chủ lắng nghe và đóng các kết nối.
     */
    fun stopSyncServer() {
        isRunning.set(false)
        heartbeatJob?.cancel()
        heartbeatJob = null
        peerPresenceMap.clear()
        _peerPresenceFlow.value = emptyMap()
        pendingProbes.values.forEach { it.cancel() }
        pendingProbes.clear()
        pendingAcks.values.forEach { it.cancel() }
        pendingAcks.clear()

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
        val queryParam = StringBuilder("teamId=$teamId")
        myMemberId?.takeIf { it.isNotBlank() }?.let {
            queryParam.append("&peerId=$it")
        }
        val wsUrl = if (relayUrl.contains("?teamId=")) relayUrl else {
            val sep = if (relayUrl.contains("?")) "&" else "?"
            "$relayUrl$sep$queryParam"
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

                // 1. Kiểm tra trùng lặp tên hiển thị trong nhóm (Owner Uniqueness Control)
                val existingMembers = teamRepository.getMembersByTeam(currentTeamId).firstOrNull() ?: emptyList()
                val isNameTaken = existingMembers.any {
                    it.isActive && it.memberName.equals(req.memberName.trim(), ignoreCase = true) && it.memberId != req.memberId
                }
                if (isNameTaken) {
                    SyncLog.w("TeamShareSync", "Từ chối JOIN_REQUEST: Tên '${req.memberName}' đã tồn tại trong nhóm!")
                    return JoinResponseMessage(
                        status = "ERROR",
                        message = "Tên hiển thị '${req.memberName}' đã tồn tại trong nhóm. Vui lòng chọn một tên khác."
                    ).toJson()
                }

                // 2. Kiểm tra nếu thành viên đã bị thu hồi quyền (CRL / Tombstone)
                val isRevoked = existingMembers.any {
                    it.memberId == req.memberId && !it.isActive
                }
                if (isRevoked) {
                    SyncLog.w("TeamShareSync", "Từ chối JOIN_REQUEST: Thành viên ${req.memberName} (${req.memberId}) đã bị thu hồi quyền trước đó!")
                    return JoinResponseMessage(
                        status = "ERROR",
                        message = "Tài khoản của bạn đã bị thu hồi quyền truy cập nhóm này."
                    ).toJson()
                }

                // 3. Lưu thành viên mới vào CSDL của máy này (ký cấp chứng thư hợp chuẩn E2EE nếu có Master Key)
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
                val myLocalIp = NetworkAddressHelper.getLocalIpv4Address()
                val docDtos = allDocs.map {
                    val isMyDoc = it.publisherId == myMemberId || (myMemberId == null && !it.localUri.isNullOrBlank())
                    DocumentSyncDto(
                        docId = it.docId,
                        fileName = it.fileName,
                        fileSize = it.fileSize,
                        mimeType = it.mimeType,
                        expectedSha256 = it.expectedSha256,
                        publisherId = it.publisherId,
                        requireBiometric = it.requireBiometric,
                        publisherHost = if (isMyDoc) (myLocalIp ?: remoteHost) else (it.publisherHost ?: remoteHost),
                        publisherPort = if (isMyDoc && currentListeningPort > 0) currentListeningPort else it.publisherPort,
                        folderId = it.folderId
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
                val myLocalIp = NetworkAddressHelper.getLocalIpv4Address()
                val docDtos = allDocs.map {
                    val isMyDoc = it.publisherId == myMemberId || (myMemberId == null && !it.localUri.isNullOrBlank())
                    DocumentSyncDto(
                        docId = it.docId,
                        fileName = it.fileName,
                        fileSize = it.fileSize,
                        mimeType = it.mimeType,
                        expectedSha256 = it.expectedSha256,
                        publisherId = it.publisherId,
                        requireBiometric = it.requireBiometric,
                        publisherHost = if (isMyDoc) (myLocalIp ?: remoteHost) else (it.publisherHost ?: remoteHost),
                        publisherPort = if (isMyDoc && currentListeningPort > 0) currentListeningPort else it.publisherPort,
                        folderId = it.folderId
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
                    // Bảo vệ khóa công khai của Owner: Nếu bản ghi hiện tại là Owner và đã lưu khóa công khai (hoặc QR key),
                    // không ghi đè bằng signature hex của Owner trong syncResp
                    val isOwnerKeyAlreadyStored = existing?.role == MemberRole.OWNER && existing.credentialData.isNotBlank() && role == MemberRole.OWNER
                    val credDataToSave = if (isOwnerKeyAlreadyStored) existing!!.credentialData else m.credentialData

                    if (existing == null || (m.credentialData.isNotBlank() && existing.credentialData != m.credentialData && !isOwnerKeyAlreadyStored)) {
                        val member = TeamMember(
                            memberId = m.memberId,
                            teamId = syncResp.teamId,
                            memberName = m.memberName,
                            deviceFingerprint = m.deviceFingerprint,
                            role = role,
                            credentialData = credDataToSave,
                            isActive = true
                        )
                        teamRepository.addMember(member)
                        SyncLog.d("TeamShareSync", "Đã cập nhật thông tin/chứng thư cho thành viên ${m.memberName} (${m.memberId})")
                    }
                }

                syncResp.documents.forEach { d ->
                    if (!d.publisherHost.isNullOrBlank() && d.publisherPort > 0) {
                        knownPeers[d.publisherId] = d.publisherHost to d.publisherPort
                    }
                    val existing = documentRepository.getDocumentById(d.docId)
                    if (existing == null) {
                        val newDoc = SharedDocument(
                            docId = d.docId,
                            teamId = syncResp.teamId,
                            publisherId = d.publisherId,
                            folderId = d.folderId,
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
                        folderId = announce.folderId,
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

            PeerHeartbeatMessage.ACTION -> {
                val hb = PeerHeartbeatMessage.fromJson(jsonStr) ?: return null
                if (hb.teamId != currentTeamId) return null
                if (!hb.clientHost.isNullOrBlank() && hb.clientPort > 0) {
                    knownPeers[hb.memberId] = hb.clientHost to hb.clientPort
                }
                peerPresenceMap[hb.memberId] = PeerPresenceInfo(
                    memberId = hb.memberId,
                    memberName = hb.memberName,
                    lastSeenTimestamp = System.currentTimeMillis(),
                    isOnline = true
                )
                _peerPresenceFlow.value = peerPresenceMap.toMap()
                null
            }

            FileProbeRequestMessage.ACTION -> {
                val probe = FileProbeRequestMessage.fromJson(jsonStr) ?: return null
                if (probe.teamId != currentTeamId) return null
                val doc = documentRepository.getDocumentById(probe.docId)
                val file = doc?.localUri?.let { java.io.File(it) }
                val exists = file != null && file.exists() && file.canRead()
                val response = FileProbeResponseMessage(
                    teamId = probe.teamId,
                    docId = probe.docId,
                    streamId = probe.streamId,
                    status = if (exists) "READY" else "FILE_MISSING",
                    message = if (exists) "OK" else "Tệp nguồn không còn tồn tại trên máy người chia sẻ",
                    targetPeerId = probe.requesterId
                )
                response.toJson()
            }

            FileProbeResponseMessage.ACTION -> {
                val resp = FileProbeResponseMessage.fromJson(jsonStr) ?: return null
                if (resp.teamId != currentTeamId) return null
                pendingProbes[resp.streamId]?.complete(resp)
                null
            }

            FileRequestAckMessage.ACTION -> {
                val ack = FileRequestAckMessage.fromJson(jsonStr) ?: return null
                if (ack.teamId != currentTeamId) return null
                pendingAcks[ack.streamId]?.complete(ack)
                null
            }

            FileRequestMessage.ACTION -> {
                val req = FileRequestMessage.fromJson(jsonStr) ?: return null
                if (req.teamId != currentTeamId) return null

                // 1. Phản hồi ngay lập tức bản tin xác nhận FILE_REQUEST_ACK
                val ack = FileRequestAckMessage(
                    teamId = req.teamId,
                    streamId = req.streamId,
                    messageId = req.messageId,
                    status = "ACCEPTED",
                    targetPeerId = req.requesterId
                )
                val ackJson = ack.toJson()

                // Chủ động gửi ACK qua kênh Relay nếu đang mở
                activeRelayConnection?.let { conn ->
                    if (conn.isOpen()) {
                        conn.sendText(ackJson)
                    }
                }

                val lastProcessed = processedFileRequests[req.streamId] ?: 0L
                val now = System.currentTimeMillis()
                if (now - lastProcessed < 5000L) {
                    SyncLog.d("TeamShareSync", "Bỏ qua FILE_REQUEST trùng lặp trong 5s cho streamId=${req.streamId}")
                    return ackJson
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
                ackJson
            }

            MemberRevokedMessage.ACTION -> {
                val revMsg = MemberRevokedMessage.fromJson(jsonStr) ?: return null
                if (revMsg.teamId != currentTeamId) return null
                SyncLog.w("TeamShareSync", "Nhận bản tin MEMBER_REVOKED: Thành viên ${revMsg.revokedMemberId} đã bị tước quyền")

                // 1. Lưu bản ghi Signed CRL
                val record = RevocationEntity(
                    recordId = revMsg.recordId,
                    teamId = revMsg.teamId,
                    revokedMemberId = revMsg.revokedMemberId,
                    revokedFingerprint = revMsg.revokedFingerprint,
                    revocationType = "MEMBER_KICK",
                    revokedAt = revMsg.revokedAt,
                    reason = revMsg.reason,
                    ownerSignature = revMsg.signature
                )
                teamRepository.recordRevocation(record)

                // 2. Cập nhật trạng thái thành viên bị thu hồi
                teamRepository.revokeMember(revMsg.teamId, revMsg.revokedMemberId)

                // 3. Hủy bỏ ngay lập tức các phiên truyền file với peer này
                onSessionAbortRequested?.invoke(revMsg.revokedMemberId, revMsg.teamId)

                // 4. Ghi nhật ký kiểm toán
                auditRepository?.logEvent(
                    AuditLog(
                        teamId = revMsg.teamId,
                        actorId = revMsg.revokedMemberId,
                        eventType = AuditEventType.MEMBER_REVOKED,
                        severity = AuditSeverity.WARNING,
                        details = "Nhận thông báo thu hồi quyền thành viên ${revMsg.revokedMemberId} có chữ ký số hợp lệ của Owner"
                    )
                )
                null
            }

            TeamDisbandedMessage.ACTION -> {
                val disMsg = TeamDisbandedMessage.fromJson(jsonStr) ?: return null
                if (disMsg.teamId != currentTeamId) return null
                SyncLog.w("TeamShareSync", "Nhận bản tin TEAM_DISBANDED: Nhóm $currentTeamId đã bị giải tán")

                val record = RevocationEntity(
                    recordId = UUID.randomUUID().toString(),
                    teamId = disMsg.teamId,
                    revokedMemberId = "ALL",
                    revokedFingerprint = "",
                    revocationType = "TEAM_DISBAND",
                    revokedAt = disMsg.disbandedAt,
                    reason = "DISBANDED_BY_OWNER",
                    ownerSignature = disMsg.signature
                )
                teamRepository.recordRevocation(record)
                teamRepository.disbandTeam(disMsg.teamId)
                onSessionAbortRequested?.invoke(null, disMsg.teamId)

                auditRepository?.logEvent(
                    AuditLog(
                        teamId = disMsg.teamId,
                        actorId = "OWNER",
                        eventType = AuditEventType.TEAM_DISBANDED,
                        severity = AuditSeverity.WARNING,
                        details = "Nhận thông báo giải tán nhóm từ Trưởng nhóm. Nhóm chuyển sang trạng thái chỉ đọc."
                    )
                )
                null
            }

            RevocationSyncQueryMessage.ACTION -> {
                val q = RevocationSyncQueryMessage.fromJson(jsonStr) ?: return null
                if (q.teamId != currentTeamId) return null
                val list = teamRepository.getAllRevocationsList(currentTeamId)
                val dtos = list.map {
                    RevocationSyncDto(
                        recordId = it.recordId,
                        teamId = it.teamId,
                        revokedMemberId = it.revokedMemberId,
                        revokedFingerprint = it.revokedFingerprint,
                        revocationType = it.revocationType,
                        revokedAt = it.revokedAt,
                        reason = it.reason,
                        ownerSignature = it.ownerSignature
                    )
                }
                RevocationSyncResponseMessage(teamId = currentTeamId, revocations = dtos).toJson()
            }

            RevocationSyncResponseMessage.ACTION -> {
                val resp = RevocationSyncResponseMessage.fromJson(jsonStr) ?: return null
                if (resp.teamId != currentTeamId) return null
                resp.revocations.forEach { r ->
                    val entity = RevocationEntity(
                        recordId = r.recordId,
                        teamId = r.teamId,
                        revokedMemberId = r.revokedMemberId,
                        revokedFingerprint = r.revokedFingerprint,
                        revocationType = r.revocationType,
                        revokedAt = r.revokedAt,
                        reason = r.reason,
                        ownerSignature = r.ownerSignature
                    )
                    teamRepository.recordRevocation(entity)
                    if (r.revocationType == "MEMBER_KICK") {
                        teamRepository.revokeMember(r.teamId, r.revokedMemberId)
                        onSessionAbortRequested?.invoke(r.revokedMemberId, r.teamId)
                    } else if (r.revocationType == "TEAM_DISBAND") {
                        teamRepository.disbandTeam(r.teamId)
                        onSessionAbortRequested?.invoke(null, r.teamId)
                    }
                }
                null
            }

            FolderAnnounceMessage.ACTION -> {
                val fMsg = FolderAnnounceMessage.fromJson(jsonStr) ?: return null
                if (fMsg.teamId != currentTeamId) return null
                SyncLog.d("TeamShareSync", "Nhận bản tin FOLDER_ANNOUNCE: ${fMsg.folderName} (${fMsg.folderId})")
                val folder = TeamFolder(
                    folderId = fMsg.folderId,
                    teamId = fMsg.teamId,
                    parentFolderId = fMsg.parentFolderId,
                    folderName = fMsg.folderName,
                    createdBy = fMsg.createdBy,
                    createdAt = fMsg.createdAt,
                    isDeleted = false
                )
                folderRepository?.createFolder(folder)
                null
            }

            FolderDeleteMessage.ACTION -> {
                val delMsg = FolderDeleteMessage.fromJson(jsonStr) ?: return null
                if (delMsg.teamId != currentTeamId) return null
                SyncLog.d("TeamShareSync", "Nhận bản tin FOLDER_DELETE: ${delMsg.folderId}")
                folderRepository?.softDeleteFolder(delMsg.folderId)
                null
            }

            FolderSyncQueryMessage.ACTION -> {
                val qMsg = FolderSyncQueryMessage.fromJson(jsonStr) ?: return null
                if (qMsg.teamId != currentTeamId) return null
                val folders = folderRepository?.getFoldersByTeam(currentTeamId)?.firstOrNull() ?: emptyList()
                val dtos = folders.map {
                    FolderSyncDto(
                        folderId = it.folderId,
                        teamId = it.teamId,
                        parentFolderId = it.parentFolderId,
                        folderName = it.folderName,
                        createdBy = it.createdBy,
                        createdAt = it.createdAt,
                        isDeleted = it.isDeleted
                    )
                }
                FolderSyncResponseMessage(teamId = currentTeamId, folders = dtos).toJson()
            }

            FolderSyncResponseMessage.ACTION -> {
                val resp = FolderSyncResponseMessage.fromJson(jsonStr) ?: return null
                if (resp.teamId != currentTeamId) return null
                val folders = resp.folders.map {
                    TeamFolder(
                        folderId = it.folderId,
                        teamId = it.teamId,
                        parentFolderId = it.parentFolderId,
                        folderName = it.folderName,
                        createdBy = it.createdBy,
                        createdAt = it.createdAt,
                        isDeleted = it.isDeleted
                    )
                }
                folderRepository?.saveFolders(folders)
                null
            }

            AuditLogAnnounceMessage.ACTION -> {
                val aMsg = AuditLogAnnounceMessage.fromJson(jsonStr) ?: return null
                if (aMsg.teamId != currentTeamId) return null
                val dto = aMsg.log
                val eventType = try { AuditEventType.valueOf(dto.eventType) } catch (_: Exception) { AuditEventType.TRANSFER_STARTED }
                val severity = try { AuditSeverity.valueOf(dto.severity) } catch (_: Exception) { AuditSeverity.INFO }
                val log = AuditLog(
                    logId = dto.logId,
                    teamId = dto.teamId,
                    actorId = dto.actorId,
                    timestamp = dto.timestamp,
                    eventType = eventType,
                    severity = severity,
                    details = dto.description,
                    actorName = dto.actorName,
                    signature = dto.signature
                )
                auditRepository?.logEvent(log)
                null
            }

            AuditLogSyncQueryMessage.ACTION -> {
                val q = AuditLogSyncQueryMessage.fromJson(jsonStr) ?: return null
                if (q.teamId != currentTeamId) return null
                val logs = auditRepository?.getLogsSince(currentTeamId, q.sinceTimestamp) ?: emptyList()
                val dtos = logs.map {
                    AuditLogSyncDto(
                        logId = it.logId,
                        teamId = it.teamId ?: currentTeamId,
                        eventType = it.eventType.name,
                        severity = it.severity.name,
                        actorId = it.actorId,
                        actorName = it.actorName,
                        description = it.details,
                        timestamp = it.timestamp,
                        signature = it.signature
                    )
                }
                AuditLogSyncResponseMessage(teamId = currentTeamId, logs = dtos).toJson()
            }

            AuditLogSyncResponseMessage.ACTION -> {
                val resp = AuditLogSyncResponseMessage.fromJson(jsonStr) ?: return null
                if (resp.teamId != currentTeamId) return null
                val logs = resp.logs.map {
                    val eventType = try { AuditEventType.valueOf(it.eventType) } catch (_: Exception) { AuditEventType.TRANSFER_STARTED }
                    val severity = try { AuditSeverity.valueOf(it.severity) } catch (_: Exception) { AuditSeverity.INFO }
                    AuditLog(
                        logId = it.logId,
                        teamId = it.teamId,
                        actorId = it.actorId,
                        timestamp = it.timestamp,
                        eventType = eventType,
                        severity = severity,
                        details = it.description,
                        actorName = it.actorName,
                        signature = it.signature
                    )
                }
                auditRepository?.saveLogs(logs)
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
                folderId = d.folderId,
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
                    folderId = d.folderId,
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
            publisherPort = currentListeningPort,
            folderId = document.folderId
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
     * Thăm dò tính khả dụng của tệp nguồn (Pre-flight Probe) trước khi mở phiên truyền tải.
     */
    suspend fun probeFileAvailability(
        teamId: String,
        docId: String,
        targetPeerId: String,
        requesterId: String,
        timeoutMs: Long = 3000L
    ): Result<FileProbeResponseMessage> = withContext(ioDispatcher) {
        val probeStreamId = "probe_${UUID.randomUUID().toString().take(8)}"
        val deferred = CompletableDeferred<FileProbeResponseMessage>()
        pendingProbes[probeStreamId] = deferred

        val probeMsg = FileProbeRequestMessage(
            teamId = teamId,
            docId = docId,
            streamId = probeStreamId,
            requesterId = requesterId,
            targetPeerId = targetPeerId
        ).toJson()

        // 1. Gửi qua LAN nếu biết peer
        knownPeers[targetPeerId]?.let { (host, port) ->
            launch(ioDispatcher) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 1500)
                        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                        writer.write(probeMsg)
                        writer.newLine()
                        writer.flush()
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                        val line = reader.readLine()
                        if (line != null) {
                            FileProbeResponseMessage.fromJson(line)?.let { deferred.complete(it) }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Gửi qua Relay Sync Room
        activeRelayConnection?.let { conn ->
            if (conn.isOpen()) {
                conn.sendText(probeMsg)
            }
        }

        try {
            val result = withTimeout(timeoutMs) { deferred.await() }
            Result.success(result)
        } catch (e: TimeoutCancellationException) {
            Result.failure(java.io.IOException("Quá thời gian chờ phản hồi thăm dò tệp từ người chia sẻ"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pendingProbes.remove(probeStreamId)
        }
    }

    /**
     * Gửi yêu cầu tải tệp (FILE_REQUEST) có xác nhận 2 pha (ACK) và cơ chế thử lại (Retry Backoff).
     */
    suspend fun requestFileDownloadWithAck(
        teamId: String,
        docId: String,
        targetPeerId: String,
        streamId: String,
        requesterId: String,
        requesterName: String,
        maxRetries: Int = 3,
        preferredChannel: String = "RELAY",
        requesterHost: String? = null,
        requesterPort: Int = 0
    ): Result<Unit> = withContext(ioDispatcher) {
        val messageId = UUID.randomUUID().toString()
        val message = FileRequestMessage(
            teamId = teamId,
            docId = docId,
            requesterId = requesterId,
            requesterName = requesterName,
            streamId = streamId,
            preferredChannel = preferredChannel,
            targetPeerId = targetPeerId,
            messageId = messageId,
            requesterHost = requesterHost,
            requesterPort = requesterPort
        )
        val jsonStr = message.toJson()

        var attempt = 0
        val delays = listOf(1500L, 2500L, 4000L)
        while (attempt < maxRetries) {
            val deferred = CompletableDeferred<FileRequestAckMessage>()
            pendingAcks[streamId] = deferred

            // 1. Gửi qua LAN
            knownPeers[targetPeerId]?.let { (host, port) ->
                launch(ioDispatcher) {
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(host, port), 2000)
                            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                            writer.write(jsonStr)
                            writer.newLine()
                            writer.flush()

                            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                            val line = reader.readLine()
                            if (line != null) {
                                FileRequestAckMessage.fromJson(line)?.let { ack ->
                                    if (ack.status == "ACCEPTED") {
                                        deferred.complete(ack)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // 2. Gửi qua Relay
            activeRelayConnection?.let { conn ->
                if (conn.isOpen()) {
                    conn.sendText(jsonStr)
                }
            }

            try {
                val waitTime = delays.getOrElse(attempt) { 3000L }
                val ack = withTimeout(waitTime) { deferred.await() }
                if (ack.status == "ACCEPTED") {
                    SyncLog.d("TeamShareSync", "Đã nhận FILE_REQUEST_ACK thành công cho streamId=$streamId")
                    return@withContext Result.success(Unit)
                } else {
                    return@withContext Result.failure(java.io.IOException("Người chia sẻ từ chối yêu cầu truyền tệp"))
                }
            } catch (e: TimeoutCancellationException) {
                attempt++
                SyncLog.w("TeamShareSync", "Chưa nhận được ACK cho streamId=$streamId (lần thử $attempt/$maxRetries), thử lại...")
            } finally {
                pendingAcks.remove(streamId)
            }
        }
        Result.failure(java.io.IOException("Không nhận được phản hồi xác nhận từ người chia sẻ tệp sau $maxRetries lần thử"))
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
        relayUrl: String? = null,
        preferredChannel: String = "RELAY",
        requesterHost: String? = null,
        requesterPort: Int = 0
    ) = withContext(ioDispatcher) {
        val message = FileRequestMessage(
            teamId = teamId,
            docId = docId,
            requesterId = requesterId,
            requesterName = requesterName,
            streamId = streamId,
            preferredChannel = preferredChannel,
            requesterHost = requesterHost,
            requesterPort = requesterPort
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

    /**
     * Phát tán bản tin thu hồi quyền thành viên (MEMBER_REVOKED) tới toàn bộ peer qua Relay & LAN.
     */
    suspend fun broadcastMemberRevoked(revocation: RevocationEntity) = withContext(ioDispatcher) {
        val msg = MemberRevokedMessage(
            teamId = revocation.teamId,
            recordId = revocation.recordId,
            revokedMemberId = revocation.revokedMemberId,
            revokedFingerprint = revocation.revokedFingerprint,
            revokedAt = revocation.revokedAt,
            reason = revocation.reason,
            signature = revocation.ownerSignature
        ).toJson()

        knownPeers.values.forEach { (host, port) ->
            launch(ioDispatcher) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 2000)
                        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                        writer.write(msg)
                        writer.newLine()
                        writer.flush()
                    }
                } catch (_: Exception) {}
            }
        }

        activeRelayConnection?.let { conn ->
            if (conn.isOpen()) {
                conn.sendText(msg)
            }
        }
    }

    /**
     * Phát tán bản tin giải tán nhóm (TEAM_DISBANDED) tới toàn bộ peer qua Relay & LAN.
     */
    suspend fun broadcastTeamDisbanded(teamId: String, signature: String) = withContext(ioDispatcher) {
        val msg = TeamDisbandedMessage(
            teamId = teamId,
            signature = signature
        ).toJson()

        knownPeers.values.forEach { (host, port) ->
            launch(ioDispatcher) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 2000)
                        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                        writer.write(msg)
                        writer.newLine()
                        writer.flush()
                    }
                } catch (_: Exception) {}
            }
        }

        activeRelayConnection?.let { conn ->
            if (conn.isOpen()) {
                conn.sendText(msg)
            }
        }
    }

    /**
     * Gửi truy vấn đồng bộ danh sách thu hồi CRL (REVOCATION_SYNC_QUERY) khi online lại.
     */
    suspend fun queryRevocations(teamId: String) = withContext(ioDispatcher) {
        val msg = RevocationSyncQueryMessage(
            teamId = teamId,
            requesterId = myMemberId ?: ""
        ).toJson()
        activeRelayConnection?.let { conn ->
            if (conn.isOpen()) {
                conn.sendText(msg)
            }
        }
    }

    /**
     * Phát sóng thông báo tạo thư mục ảo mới (FOLDER_ANNOUNCE) tới các peer.
     */
    suspend fun broadcastFolderAnnounce(folder: TeamFolder) = withContext(ioDispatcher) {
        val msg = FolderAnnounceMessage(
            folderId = folder.folderId,
            teamId = folder.teamId,
            parentFolderId = folder.parentFolderId,
            folderName = folder.folderName,
            createdBy = folder.createdBy,
            createdAt = folder.createdAt
        ).toJson()

        knownPeers.values.forEach { (host, port) ->
            launch(ioDispatcher) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 2000)
                        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                        writer.write(msg)
                        writer.newLine()
                        writer.flush()
                    }
                } catch (_: Exception) {}
            }
        }

        activeRelayConnection?.let { conn ->
            if (conn.isOpen()) {
                conn.sendText(msg)
            }
        }
    }

    /**
     * Phát sóng thông báo xóa thư mục ảo (FOLDER_DELETE) tới các peer.
     */
    suspend fun broadcastFolderDelete(teamId: String, folderId: String) = withContext(ioDispatcher) {
        val msg = FolderDeleteMessage(folderId = folderId, teamId = teamId).toJson()

        knownPeers.values.forEach { (host, port) ->
            launch(ioDispatcher) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 2000)
                        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                        writer.write(msg)
                        writer.newLine()
                        writer.flush()
                    }
                } catch (_: Exception) {}
            }
        }

        activeRelayConnection?.let { conn ->
            if (conn.isOpen()) {
                conn.sendText(msg)
            }
        }
    }

    /**
     * Gửi truy vấn đồng bộ danh sách thư mục ảo (FOLDER_SYNC_QUERY).
     */
    suspend fun queryFolders(teamId: String) = withContext(ioDispatcher) {
        val msg = FolderSyncQueryMessage(teamId = teamId).toJson()
        activeRelayConnection?.let { conn ->
            if (conn.isOpen()) {
                conn.sendText(msg)
            }
        }
    }

    /**
     * Phát sóng thông báo sự kiện kiểm toán an ninh mới (AUDIT_LOG_ANNOUNCE) tới toàn bộ peer.
     */
    suspend fun broadcastAuditLog(log: AuditLog) = withContext(ioDispatcher) {
        val teamId = log.teamId ?: return@withContext
        val dto = AuditLogSyncDto(
            logId = log.logId,
            teamId = teamId,
            eventType = log.eventType.name,
            severity = log.severity.name,
            actorId = log.actorId,
            actorName = log.actorName,
            description = log.details,
            timestamp = log.timestamp,
            signature = log.signature
        )
        val msg = AuditLogAnnounceMessage(teamId = teamId, log = dto).toJson()

        knownPeers.values.forEach { (host, port) ->
            launch(ioDispatcher) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 2000)
                        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                        writer.write(msg)
                        writer.newLine()
                        writer.flush()
                    }
                } catch (_: Exception) {}
            }
        }

        activeRelayConnection?.let { conn ->
            if (conn.isOpen()) {
                conn.sendText(msg)
            }
        }
    }

    /**
     * Truy vấn đồng bộ log kiểm toán tăng dần (Incremental Gossip Sync - AUDIT_LOG_SYNC_QUERY).
     */
    suspend fun queryAuditLogs(teamId: String, sinceTimestamp: Long) = withContext(ioDispatcher) {
        val msg = AuditLogSyncQueryMessage(teamId = teamId, sinceTimestamp = sinceTimestamp).toJson()
        activeRelayConnection?.let { conn ->
            if (conn.isOpen()) {
                conn.sendText(msg)
            }
        }
    }
}
