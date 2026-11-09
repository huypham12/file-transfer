package com.teamshare.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.file_tranfer.R
import com.teamshare.app.core.constant.AppConstants
import com.teamshare.app.core.platform.network.NetworkAddressHelper
import com.teamshare.app.core.platform.network.NetworkConfig
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.DeviceProfile
import com.teamshare.app.domain.model.DomainError
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.model.TransferType
import com.teamshare.app.domain.usecase.transfer.CancelTransferSessionUseCase
import com.teamshare.app.domain.usecase.transfer.CreateSenderSessionUseCase
import com.teamshare.app.domain.usecase.transfer.ManageApprovalModeUseCase
import com.teamshare.app.presentation.ui.mapper.UiErrorMapper
import com.teamshare.app.presentation.ui.qr.QrCodeBitmapGenerator
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel quản lý phiên phát chia sẻ 1-N phía Người gửi (FR-SND-01..04).
 * Tuân thủ SDD Mục 4.2.3 (SenderViewModel).
 */
class SenderViewModel(
    private val createSenderSessionUseCase: CreateSenderSessionUseCase,
    private val manageApprovalModeUseCase: ManageApprovalModeUseCase,
    private val cancelTransferSessionUseCase: CancelTransferSessionUseCase,
    private val lanEngine: LanTransferEngine? = null,
    private val relayEngine: RelayClientEngine? = null,
    private val customScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = customScope ?: viewModelScope

    private val _uiState = MutableStateFlow(SenderSessionUiState())
    val uiState: StateFlow<SenderSessionUiState> = _uiState.asStateFlow()

    private var ttlTimerJob: Job? = null
    private var serverJob: Job? = null
    private var relayJob: Job? = null
    private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Cập nhật danh sách tệp hoặc thư mục được người dùng chọn qua SAF.
     */
    fun onFilesSelected(files: List<File>) {
        val totalBytes = files.sumOf { calculateTotalBytes(it) }
        _uiState.update {
            it.copy(
                selectedFiles = files,
                totalBytes = totalBytes,
                errorMessage = null,
                errorResId = null
            )
        }
    }

    /**
     * Cập nhật thời gian sống (TTL) của phiên chia sẻ do người dùng lựa chọn.
     */
    fun setSelectedTtlSeconds(seconds: Long) {
        _uiState.update { it.copy(selectedTtlSeconds = seconds, remainingTtlSeconds = seconds) }
    }

    /**
     * Tiếp nhận danh sách Uri từ Android SAF (Sharesheet hoặc OpenMultipleDocuments / OpenDocumentTree).
     * Sao chép an toàn vào cache sandbox để đảm bảo luồng I/O không bị ngắt quãng.
     */
    fun onUrisSelected(uris: List<android.net.Uri>, context: android.content.Context) {
        scope.launch {
            val safGateway = com.teamshare.app.core.storage.saf.SafStorageGateway(context)
            val cacheFolder = File(context.cacheDir, "outgoing_shares").apply { mkdirs() }
            val resolvedFiles = mutableListOf<File>()

            for (uri in uris) {
                if (safGateway.isTreeUri(uri)) {
                    val copiedFolder = safGateway.copyTreeUriToDirectory(uri, cacheFolder)
                    if (copiedFolder != null && copiedFolder.exists()) {
                        resolvedFiles.add(copiedFolder)
                    }
                    continue
                }

                val metadata = safGateway.queryMetadata(uri) ?: continue
                val targetFile = File(cacheFolder, metadata.fileName)
                try {
                    safGateway.openInputStream(uri)?.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (targetFile.exists() && targetFile.length() > 0) {
                        resolvedFiles.add(targetFile)
                    }
                } catch (_: Throwable) {
                }
            }

            if (resolvedFiles.isNotEmpty()) {
                val current = _uiState.value.selectedFiles.toMutableList()
                current.addAll(resolvedFiles)
                val distinct = current.distinctBy { it.absolutePath }
                onFilesSelected(distinct)
            }
        }
    }

    /**
     * Xóa một tệp khỏi danh sách đã chọn.
     */
    fun removeSelectedFile(file: File) {
        val updatedFiles = _uiState.value.selectedFiles.filterNot { it.absolutePath == file.absolutePath }
        val totalBytes = updatedFiles.sumOf { calculateTotalBytes(it) }
        _uiState.update {
            it.copy(
                selectedFiles = updatedFiles,
                totalBytes = totalBytes
            )
        }
    }

    /**
     * Dọn sạch toàn bộ danh sách tệp đã chọn.
     */
    fun clearSelectedFiles() {
        _uiState.update {
            it.copy(
                selectedFiles = emptyList(),
                totalBytes = 0L
            )
        }
    }

    /**
     * Thay đổi chế độ phê duyệt thiết bị (Tự động hoặc Phê duyệt thủ công).
     */
    fun setApprovalMode(mode: ApprovalMode) {
        _uiState.update { it.copy(approvalMode = mode) }
        val currentSessionId = _uiState.value.sessionId
        if (currentSessionId.isNotBlank() && _uiState.value.isSharing) {
            scope.launch {
                manageApprovalModeUseCase(currentSessionId, mode)
            }
        }
    }

    /**
     * Khởi tạo phiên phát chia sẻ 1-N và tạo mã QR động.
     * Tự động phát hiện địa chỉ IPv4 Wi-Fi của thiết bị và tự động kích hoạt kênh kép LAN + Relay Server.
     */
    fun startSharingSession(
        senderIp: String? = null,
        senderPort: Int = LanTransferEngine.DEFAULT_LAN_PORT,
        onSessionCreated: ((String) -> Unit)? = null
    ): Job {
        val files = _uiState.value.selectedFiles
        if (files.isEmpty()) {
            _uiState.update {
                it.copy(
                    errorResId = R.string.msg_select_file_required,
                    errorMessage = "Vui lòng chọn ít nhất một tệp hoặc thư mục để chia sẻ"
                )
            }
            return Job().apply { complete() }
        }

        val isEmulator = NetworkConfig.isEmulator()

        return scope.launch {
            if (isEmulator && NetworkConfig.detectedHostLanIp.isBlank()) {
                NetworkConfig.autoDetectRelayHostFromEmulator()
            }

            // Tự động lấy IP Wi-Fi thực tế của thiết bị (hoặc IP Wi-Fi host máy tính nếu là máy ảo)
            val fallbackIp = NetworkConfig.DEFAULT_FALLBACK_IP
            val resolvedIp = if (!senderIp.isNullOrBlank() && senderIp != fallbackIp) {
                senderIp
            } else if (isEmulator && NetworkConfig.detectedHostLanIp.isNotBlank()) {
                NetworkConfig.detectedHostLanIp
            } else {
                NetworkAddressHelper.getLocalIpv4Address() ?: fallbackIp
            }

            val relayQrUrl = NetworkConfig.resolveRelayUrlForQr()
            val senderRelayUrl = NetworkConfig.resolveRelayUrlForSender()
            val primaryChannel = if (isEmulator) TransferChannel.ZERO_KNOWLEDGE_RELAY else TransferChannel.DIRECT_LAN
            val streamId = UUID.randomUUID().toString()

            val result = createSenderSessionUseCase.execute(
                files = files,
                senderIp = resolvedIp,
                senderPort = senderPort,
                approvalMode = _uiState.value.approvalMode,
                channel = primaryChannel,
                relayUrl = relayQrUrl,
                streamId = streamId,
                ttlSeconds = _uiState.value.selectedTtlSeconds
            )

            result.onSuccess { sessionData ->
                val rawQrText = sessionData.qrPayload.toQrString()
                val qrBitmap = try {
                    QrCodeBitmapGenerator.generateQrBitmap(rawQrText, 512)
                } catch (_: Throwable) {
                    null
                }

                _uiState.update {
                    it.copy(
                        isSharing = true,
                        sessionId = sessionData.session.sessionId,
                        qrPayload = sessionData.qrPayload,
                        qrBitmap = qrBitmap,
                        remainingTtlSeconds = _uiState.value.selectedTtlSeconds,
                        errorMessage = null,
                        errorResId = null
                    )
                }

                val preparedFile = prepareFileToSend(files, sessionData.session.sessionId)

                // 1. Mở ServerSocket trên máy phát để lắng nghe kết nối từ máy nhận qua LAN
                startSenderServerJob(
                    fileToSend = preparedFile,
                    sessionId = sessionData.session.sessionId,
                    sessionToken = sessionData.session.sessionToken,
                    port = senderPort
                )

                // 2. Đồng thời kết nối tới Relay Server Go nếu có relayEngine
                if (relayEngine != null) {
                    startSenderRelayJob(
                        fileToSend = preparedFile,
                        sessionId = sessionData.session.sessionId,
                        sessionToken = sessionData.session.sessionToken,
                        relayUrl = senderRelayUrl
                    )
                }

                startTtlTimer()
                onSessionCreated?.invoke(sessionData.session.sessionId)
            }.onFailure { error ->
                val domainErr = DomainError.fromThrowable(error)
                val resId = UiErrorMapper.mapToStringRes(domainErr)
                _uiState.update {
                    it.copy(
                        isSharing = false,
                        errorResId = resId,
                        errorMessage = if (domainErr is DomainError.GeneralException) domainErr.details else null
                    )
                }
            }
        }
    }

    private fun prepareFileToSend(files: List<File>, sessionId: String): File {
        return if (files.size == 1) {
            files[0]
        } else {
            val parentDir = files[0].parentFile ?: File(".")
            val batchDir = File(parentDir, "batch_$sessionId").apply { mkdirs() }
            files.forEach { src ->
                val dest = File(batchDir, src.name)
                if (!dest.exists()) {
                    try { src.copyTo(dest, overwrite = true) } catch (_: Exception) {}
                }
            }
            batchDir
        }
    }

    /**
     * Khởi chạy ServerSocket trong chế độ nền để tiếp nhận kết nối tải tệp P2P.
     */
    private fun startSenderServerJob(
        fileToSend: File,
        sessionId: String,
        sessionToken: String,
        port: Int
    ) {
        val engine = lanEngine ?: return
        serverJob?.cancel()
        serverJob = scope.launch(Dispatchers.IO) {
            engine.startSenderServer(
                port = port,
                sessionToken = sessionToken,
                file = fileToSend,
                sessionId = sessionId,
                approvalMode = _uiState.value.approvalMode,
                onDeviceApproval = { peerProfile ->
                    handleDeviceApproval(peerProfile)
                },
                onPeerConnected = { peerProfile ->
                    onDeviceConnectionRequested(peerProfile)
                },
                serverTimeoutMs = AppConstants.Timeout.SERVER_SOCKET_ACCEPT_TIMEOUT_MS
            )
        }
    }

    /**
     * Khởi chạy gắn kênh vào Server Go Relay để tiếp nhận máy nhận qua mạng Internet / NAT.
     */
    private fun startSenderRelayJob(
        fileToSend: File,
        sessionId: String,
        sessionToken: String,
        relayUrl: String
    ) {
        val relay = relayEngine ?: return
        relayJob?.cancel()
        relayJob = scope.launch(Dispatchers.IO) {
            val isFolder = fileToSend.isDirectory
            val actualFile = if (isFolder) {
                val tempZip = File(fileToSend.parentFile ?: File("."), "${fileToSend.name}_tmp.zip")
                try {
                    tempZip.outputStream().use { fos ->
                        com.teamshare.app.core.transfer.zip.ZipStreamPacker.packDirectoryToStream(fileToSend, fos)
                    }
                    tempZip
                } catch (_: Exception) {
                    fileToSend
                }
            } else {
                fileToSend
            }

            val dummySession = TransferSession(
                sessionId = sessionId,
                sessionToken = sessionToken,
                docId = actualFile.name,
                direction = TransferDirection.SENDER,
                channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
                peerDeviceModel = "",
                peerFingerprint = "",
                approvalMode = _uiState.value.approvalMode,
                transferType = if (fileToSend.isDirectory) TransferType.FOLDER_ZIP else TransferType.SINGLE_FILE,
                manifestJson = "",
                totalBytes = actualFile.length(),
                confirmedOffset = 0L,
                tempFilePath = "",
                expectedSha256 = "",
                status = SessionStatus.PENDING,
                startedAt = System.currentTimeMillis()
            )

            var raf: RandomAccessFile? = null
            try {
                raf = RandomAccessFile(actualFile, "r")
                val sha = com.teamshare.app.core.security.crypto.Sha256StreamCalculator.calculateFileSha256(actualFile)
                relay.sendFileViaRelay(
                    relayWsUrl = relayUrl,
                    session = dummySession,
                    fileChannel = raf.channel,
                    expectedSha256 = sha,
                    ttlSeconds = _uiState.value.selectedTtlSeconds.toInt(),
                    peerValidator = { peerAuth ->
                        val peerProfile = DeviceProfile(
                            manufacturer = "Android",
                            model = peerAuth.memberId,
                            fingerprint = peerAuth.deviceFingerprint
                        )
                        handleDeviceApproval(peerProfile)
                    }
                )
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = e.message)
                }
            } finally {
                try { raf?.close() } catch (_: Exception) {}
                if (isFolder && actualFile.name.endsWith("_tmp.zip")) {
                    try { actualFile.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Xử lý phê duyệt thiết bị (Auto Accept hoặc Manual Dialog).
     */
    private suspend fun handleDeviceApproval(peerProfile: DeviceProfile): Boolean {
        if (_uiState.value.approvalMode == ApprovalMode.AUTO_ACCEPT) {
            onDeviceConnectionRequested(peerProfile)
            return true
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingApprovalDeferred = deferred
        _uiState.update { it.copy(pendingApprovalDevice = peerProfile) }
        return deferred.await()
    }

    /**
     * Bắt đầu đồng hồ đếm ngược hiệu lực mã QR (TTL do người dùng chọn).
     */
    private fun startTtlTimer() {
        ttlTimerJob?.cancel()
        ttlTimerJob = scope.launch {
            var timeLeft = _uiState.value.selectedTtlSeconds
            while (timeLeft > 0 && _uiState.value.isSharing) {
                delay(1000L)
                timeLeft--
                _uiState.update { it.copy(remainingTtlSeconds = timeLeft) }
            }
            if (timeLeft <= 0) {
                _uiState.update {
                    it.copy(
                        errorResId = R.string.msg_qr_expired,
                        errorMessage = null
                    )
                }
            }
        }
    }

    /**
     * Ghi nhận yêu cầu kết nối từ thiết bị nhận.
     */
    fun onDeviceConnectionRequested(device: DeviceProfile) {
        if (_uiState.value.approvalMode == ApprovalMode.MANUAL_APPROVAL) {
            _uiState.update { it.copy(pendingApprovalDevice = device) }
        } else {
            // Tự động phê duyệt
            val updatedList = _uiState.value.connectedDevices.toMutableList()
            if (updatedList.none { it.fingerprint == device.fingerprint }) {
                updatedList.add(device)
            }
            _uiState.update { it.copy(connectedDevices = updatedList) }
        }
    }

    /**
     * Phê duyệt hoặc Từ chối thiết bị nhận đang chờ kết nối.
     */
    fun approvePendingDevice(device: DeviceProfile, accept: Boolean) {
        val currentPending = _uiState.value.pendingApprovalDevice
        if (currentPending?.fingerprint == device.fingerprint) {
            val updatedList = _uiState.value.connectedDevices.toMutableList()
            if (accept && updatedList.none { it.fingerprint == device.fingerprint }) {
                updatedList.add(device)
            }
            _uiState.update {
                it.copy(
                    pendingApprovalDevice = null,
                    connectedDevices = updatedList
                )
            }
            pendingApprovalDeferred?.complete(accept)
            pendingApprovalDeferred = null
        }
    }

    /**
     * Dừng và đóng phiên phát tệp.
     */
    fun stopSharingSession(onStopped: (() -> Unit)? = null): Job {
        val sessionId = _uiState.value.sessionId
        ttlTimerJob?.cancel()
        serverJob?.cancel()
        serverJob = null
        relayJob?.cancel()
        relayJob = null
        lanEngine?.cancel()
        relayEngine?.cancel()
        pendingApprovalDeferred?.complete(false)
        pendingApprovalDeferred = null

        _uiState.update {
            it.copy(
                isSharing = false,
                sessionId = "",
                qrBitmap = null,
                qrPayload = null,
                pendingApprovalDevice = null,
                connectedDevices = emptyList()
            )
        }
        onStopped?.invoke()
        return scope.launch {
            if (sessionId.isNotBlank()) {
                cancelTransferSessionUseCase(sessionId)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, errorResId = null) }
    }

    private fun calculateTotalBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var size = 0L
        file.listFiles()?.forEach { child ->
            size += calculateTotalBytes(child)
        }
        return size
    }

    override fun onCleared() {
        super.onCleared()
        ttlTimerJob?.cancel()
        serverJob?.cancel()
        lanEngine?.cancel()
        pendingApprovalDeferred?.complete(false)
        pendingApprovalDeferred = null
    }
}
