package com.filetransfer.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.file_tranfer.R
import com.filetransfer.app.core.constant.AppConstants
import com.filetransfer.app.core.platform.network.NetworkAddressHelper
import com.filetransfer.app.core.platform.network.NetworkConfig
import com.filetransfer.app.core.platform.service.ITransferForegroundServiceLauncher
import com.filetransfer.app.core.transfer.lan.LanTransferEngine
import com.filetransfer.app.core.transfer.lan.TransferProgressCallback
import com.filetransfer.app.core.transfer.relay.RelayClientEngine
import com.filetransfer.app.domain.model.ApprovalMode
import com.filetransfer.app.domain.model.DeviceProfile
import com.filetransfer.app.domain.model.DomainError
import com.filetransfer.app.domain.model.ErrorCode
import com.filetransfer.app.domain.model.SessionStatus
import com.filetransfer.app.domain.model.TransferChannel
import com.filetransfer.app.domain.model.TransferDirection
import com.filetransfer.app.domain.model.TransferSession
import com.filetransfer.app.domain.model.TransferType
import com.filetransfer.app.domain.repository.ITransferRepository
import com.filetransfer.app.domain.usecase.platform.AuthenticateBiometricUseCase
import com.filetransfer.app.domain.usecase.transfer.CancelTransferSessionUseCase
import com.filetransfer.app.domain.usecase.transfer.CreateSenderSessionUseCase
import com.filetransfer.app.domain.usecase.transfer.ManageApprovalModeUseCase
import com.filetransfer.app.presentation.ui.mapper.UiErrorMapper
import com.filetransfer.app.presentation.ui.qr.QrCodeBitmapGenerator
import androidx.fragment.app.FragmentActivity
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
import kotlinx.coroutines.isActive
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
    private val authenticateBiometricUseCase: AuthenticateBiometricUseCase? = null,
    private val serviceLauncher: ITransferForegroundServiceLauncher? = null,
    private val transferRepository: ITransferRepository? = null,
    private val preferencesManager: com.filetransfer.app.core.platform.preference.AppPreferencesManager? = null,
    private val customScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = customScope ?: viewModelScope

    private val _uiState = MutableStateFlow(
        SenderSessionUiState(
            maxReceivers = preferencesManager?.maxSessionReceivers?.value ?: AppConstants.FlowControl.MAX_SESSION_RECEIVERS
        )
    )
    val uiState: StateFlow<SenderSessionUiState> = _uiState.asStateFlow()

    init {
        preferencesManager?.let { prefs ->
            scope.launch {
                prefs.maxSessionReceivers.collect { limit ->
                    if (!_uiState.value.isSharing) {
                        _uiState.update { it.copy(maxReceivers = limit) }
                    }
                }
            }
        }
    }

    private var ttlTimerJob: Job? = null
    private var serverJob: Job? = null
    private var relayJob: Job? = null
    private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null
    private val approvedPeerFingerprints = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val rejectedPeerFingerprints = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val lastReceiverProgressTime = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
    private var activePreparedFile: File? = null

    /**
     * Cập nhật danh sách tệp tin được người dùng chọn qua SAF.
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
     * Tiếp nhận danh sách Uri từ Android SAF (Sharesheet hoặc OpenMultipleDocuments).
     * Sao chép an toàn vào cache sandbox để đảm bảo luồng I/O không bị ngắt quãng.
     */
    fun onUrisSelected(uris: List<android.net.Uri>, context: android.content.Context) {
        scope.launch(Dispatchers.IO) {
            val safGateway = com.filetransfer.app.core.storage.saf.SafStorageGateway(context)
            val cacheFolder = File(context.cacheDir, "outgoing_shares").apply { mkdirs() }
            val resolvedFiles = mutableListOf<File>()

            for (uri in uris) {
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
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onFilesSelected(distinct)
                }
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
        activity: FragmentActivity? = null,
        onSessionCreated: ((String) -> Unit)? = null
    ): Job {
        val files = _uiState.value.selectedFiles
        if (files.isEmpty()) {
            _uiState.update {
                it.copy(
                    errorResId = R.string.msg_select_file_required,
                    errorMessage = "Vui lòng chọn ít nhất một tệp tin để chia sẻ"
                )
            }
            return Job().apply { complete() }
        }

        val isEmulator = NetworkConfig.isEmulator()

        return scope.launch {
            if (authenticateBiometricUseCase != null && activity != null) {
                val authResult = CompletableDeferred<Boolean>()
                authenticateBiometricUseCase(
                    activity = activity,
                    title = "Xác thực tạo phiên chia sẻ bảo mật",
                    subtitle = "Vui lòng quét vân tay để phát mã chia sẻ"
                ).collect { res ->
                    res.onSuccess { authResult.complete(true) }
                        .onFailure { authResult.completeExceptionally(it) }
                }
                try {
                    val ok = authResult.await()
                    if (!ok) {
                        _uiState.update { it.copy(errorMessage = "Xác thực sinh trắc học không thành công") }
                        return@launch
                    }
                } catch (e: Exception) {
                    _uiState.update { it.copy(errorMessage = "Xác thực danh tính thất bại: ${e.message}") }
                    return@launch
                }
            }

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
                approvedPeerFingerprints.clear()
                rejectedPeerFingerprints.clear()
                lastReceiverProgressTime.clear()
                val rawQrText = sessionData.qrPayload.toQrString()
                val qrBitmap = try {
                    QrCodeBitmapGenerator.generateQrBitmap(rawQrText, AppConstants.UI.QR_CODE_SIZE_PX)
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
                        receiverProgressMap = emptyMap(),
                        errorMessage = null,
                        errorResId = null
                    )
                }

                try {
                    serviceLauncher?.startService(
                        sessionId = sessionData.session.sessionId,
                        documentName = if (files.size == 1) files[0].name else "Bộ tệp (${files.size} mục)",
                        totalBytes = _uiState.value.totalBytes,
                        isLan = (primaryChannel == TransferChannel.DIRECT_LAN)
                    )
                } catch (_: Exception) {}

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
        val file = if (files.size == 1) {
            files[0]
        } else {
            val parentDir = files[0].parentFile ?: File(".")
            val batchDir = File(parentDir, "${AppConstants.StorageDirectory.BATCH_DIR_PREFIX}$sessionId").apply { mkdirs() }
            files.forEach { src ->
                val dest = File(batchDir, src.name)
                if (!dest.exists()) {
                    try {
                        src.copyTo(dest, overwrite = true)
                    } catch (_: Exception) {}
                }
            }
            batchDir
        }
        activePreparedFile = file
        return file
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
                onClientProgress = { peerProfile, bytesTransferred, totalBytes, speed ->
                    onReceiverProgress(peerProfile, bytesTransferred, totalBytes, speed)
                },
                onClientCompleted = { peerProfile ->
                    onReceiverCompleted(peerProfile)
                },
                onClientFailed = { peerProfile, error ->
                    onReceiverFailed(peerProfile, error)
                },
                maxReceivers = _uiState.value.maxReceivers,
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
                val tempZip = File(fileToSend.parentFile ?: File("."), "${fileToSend.name}${AppConstants.StorageDirectory.TEMP_ZIP_SUFFIX}")
                try {
                    tempZip.outputStream().use { fos ->
                        com.filetransfer.app.core.transfer.zip.ZipStreamPacker.packDirectoryToStream(fileToSend, fos)
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
                transferType = if (_uiState.value.selectedFiles.size > 1) TransferType.BATCH_FILES else TransferType.SINGLE_FILE,
                manifestJson = "",
                totalBytes = actualFile.length(),
                confirmedOffset = 0L,
                tempFilePath = "",
                expectedSha256 = "",
                status = SessionStatus.PENDING,
                startedAt = System.currentTimeMillis()
            )

            try {
                while (isActive && _uiState.value.isSharing && _uiState.value.remainingTtlSeconds > 0) {
                    var raf: RandomAccessFile? = null
                    try {
                        raf = RandomAccessFile(actualFile, "r")
                        val sha = com.filetransfer.app.core.security.crypto.Sha256StreamCalculator.calculateFileSha256(actualFile)
                        val ttl = _uiState.value.remainingTtlSeconds.toInt().coerceAtLeast(10)
                        relay.sendFileViaRelay(
                            relayWsUrl = relayUrl,
                            session = dummySession,
                            fileChannel = raf.channel,
                            expectedSha256 = sha,
                            callback = object : TransferProgressCallback {
                                override fun onProgress(bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long) {
                                    val peer = _uiState.value.connectedDevices.firstOrNull { it.fingerprint.isNotBlank() }
                                        ?: DeviceProfile(manufacturer = "Relay", model = "Receiver", fingerprint = "relay_peer")
                                    onReceiverProgress(peer, bytesTransferred, totalBytes, speedBytesPerSec)
                                }
                                override fun onCompleted(docId: String, finalSha256: String) {
                                    val peer = _uiState.value.connectedDevices.firstOrNull { it.fingerprint.isNotBlank() }
                                        ?: DeviceProfile(manufacturer = "Relay", model = "Receiver", fingerprint = "relay_peer")
                                    onReceiverCompleted(peer)
                                }
                                override fun onError(errorCode: String, message: String) {
                                    val peer = _uiState.value.connectedDevices.firstOrNull { it.fingerprint.isNotBlank() }
                                        ?: DeviceProfile(manufacturer = "Relay", model = "Receiver", fingerprint = "relay_peer")
                                    onReceiverFailed(peer, Exception("$errorCode: $message"))
                                }
                                override fun onStateChanged(newState: com.filetransfer.app.core.transfer.lan.TransferEngineState) {}
                            },
                            ttlSeconds = ttl,
                            peerValidator = { peerAuth ->
                                val peerProfile = DeviceProfile(
                                    manufacturer = peerAuth.deviceManufacturer.ifBlank { "Android" },
                                    model = peerAuth.deviceModel.ifBlank { peerAuth.memberId },
                                    fingerprint = peerAuth.deviceFingerprint
                                )
                                handleDeviceApproval(peerProfile)
                            },
                            onPeerConnected = { peerProfile ->
                                onDeviceConnectionRequested(peerProfile)
                            }
                        )
                    } catch (e: java.util.concurrent.CancellationException) {
                        break
                    } catch (e: Exception) {
                        val isRejection = e.message?.contains(ErrorCode.ERR_DEVICE_REJECTED) == true ||
                                e.message?.contains("0x102") == true ||
                                e.message?.contains("từ chối", ignoreCase = true) == true
                        if (!isRejection && _uiState.value.isSharing) {
                            _uiState.update {
                                it.copy(errorMessage = e.message)
                            }
                        }
                    } finally {
                        try { raf?.close() } catch (_: Exception) {}
                    }

                    if (isActive && _uiState.value.isSharing && _uiState.value.remainingTtlSeconds > 0) {
                        delay(500L)
                    }
                }
            } finally {
                if (isFolder && actualFile.name.endsWith(AppConstants.StorageDirectory.TEMP_ZIP_SUFFIX)) {
                    try { actualFile.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Xử lý phê duyệt thiết bị (Auto Accept hoặc Manual Dialog có Session Cache chống lặp).
     */
    private suspend fun handleDeviceApproval(peerProfile: DeviceProfile): Boolean {
        if (approvedPeerFingerprints.contains(peerProfile.fingerprint)) {
            return true
        }
        if (rejectedPeerFingerprints.contains(peerProfile.fingerprint)) {
            return false
        }
        if (_uiState.value.approvalMode == ApprovalMode.AUTO_ACCEPT) {
            approvedPeerFingerprints.add(peerProfile.fingerprint)
            onDeviceConnectionRequested(peerProfile)
            return true
        }

        // Tránh trùng lặp khi cả LAN và Relay cùng kích hoạt phê duyệt cho cùng một thiết bị
        val currentPending = _uiState.value.pendingApprovalDevice
        val existingDeferred = pendingApprovalDeferred
        if (currentPending?.fingerprint == peerProfile.fingerprint && existingDeferred != null) {
            return existingDeferred.await()
        }

        val deferred = CompletableDeferred<Boolean>()
        pendingApprovalDeferred = deferred
        _uiState.update { it.copy(pendingApprovalDevice = peerProfile) }
        val approved = deferred.await()
        if (approved) {
            approvedPeerFingerprints.add(peerProfile.fingerprint)
        } else {
            rejectedPeerFingerprints.add(peerProfile.fingerprint)
        }
        return approved
    }

    /**
     * Bắt đầu đồng hồ đếm ngược hiệu lực mã QR (TTL do người dùng chọn).
     */
    private fun startTtlTimer() {
        ttlTimerJob?.cancel()
        ttlTimerJob = scope.launch {
            var timeLeft = _uiState.value.selectedTtlSeconds
            while (timeLeft > 0 && _uiState.value.isSharing) {
                delay(AppConstants.Timeout.COUNTDOWN_TICK_INTERVAL_MS)
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
            val updatedMap = _uiState.value.receiverProgressMap.toMutableMap()
            if (!updatedMap.containsKey(device.fingerprint)) {
                updatedMap[device.fingerprint] = ReceiverProgressInfo(
                    device = device,
                    bytesTransferred = 0L,
                    totalBytes = _uiState.value.totalBytes,
                    status = ReceiverTransferStatus.CONNECTING
                )
            }
            _uiState.update { it.copy(connectedDevices = updatedList, receiverProgressMap = updatedMap) }
            savePeerInfoToDatabase(device)
        }
    }

    /**
     * Phê duyệt hoặc Từ chối thiết bị nhận đang chờ kết nối.
     */
    fun approvePendingDevice(device: DeviceProfile, accept: Boolean) {
        val currentPending = _uiState.value.pendingApprovalDevice
        if (currentPending?.fingerprint == device.fingerprint) {
            val updatedList = _uiState.value.connectedDevices.toMutableList()
            val updatedMap = _uiState.value.receiverProgressMap.toMutableMap()
            if (accept && updatedList.none { it.fingerprint == device.fingerprint }) {
                updatedList.add(device)
                if (!updatedMap.containsKey(device.fingerprint)) {
                    updatedMap[device.fingerprint] = ReceiverProgressInfo(
                        device = device,
                        bytesTransferred = 0L,
                        totalBytes = _uiState.value.totalBytes,
                        status = ReceiverTransferStatus.CONNECTING
                    )
                }
            }
            if (accept) {
                approvedPeerFingerprints.add(device.fingerprint)
                savePeerInfoToDatabase(device)
            } else {
                rejectedPeerFingerprints.add(device.fingerprint)
            }
            _uiState.update {
                it.copy(
                    pendingApprovalDevice = null,
                    connectedDevices = updatedList,
                    receiverProgressMap = updatedMap
                )
            }
            pendingApprovalDeferred?.complete(accept)
            pendingApprovalDeferred = null
        }
    }

    private fun savePeerInfoToDatabase(device: DeviceProfile) {
        val sId = _uiState.value.sessionId
        if (sId.isNotBlank() && transferRepository != null) {
            val deviceName = if (device.model.contains(device.manufacturer, ignoreCase = true)) {
                device.model
            } else {
                "${device.manufacturer} ${device.model}".trim()
            }
            scope.launch {
                try {
                    transferRepository.updatePeerInfo(sId, deviceName, device.fingerprint)
                } catch (_: Exception) {}
            }
        }
    }

    fun onReceiverProgress(device: DeviceProfile, bytesTransferred: Long, totalBytes: Long, speed: Long = 0L) {
        val now = System.currentTimeMillis()
        val calculatedSpeed = if (speed > 0L) {
            speed
        } else {
            val prev = lastReceiverProgressTime[device.fingerprint]
            val s = if (prev != null && now > prev.second) {
                val deltaBytes = (bytesTransferred - prev.first).coerceAtLeast(0L)
                val deltaTimeSec = (now - prev.second) / 1000.0
                if (deltaTimeSec > 0) (deltaBytes / deltaTimeSec).toLong() else 0L
            } else 0L
            lastReceiverProgressTime[device.fingerprint] = Pair(bytesTransferred, now)
            s
        }

        _uiState.update { current ->
            val updatedMap = current.receiverProgressMap.toMutableMap()
            val existing = updatedMap[device.fingerprint]
            val resolvedTotal = if (totalBytes > 0L) totalBytes else current.totalBytes
            val isFinished = bytesTransferred >= resolvedTotal && resolvedTotal > 0L
            updatedMap[device.fingerprint] = ReceiverProgressInfo(
                device = device,
                bytesTransferred = bytesTransferred,
                totalBytes = resolvedTotal,
                speedBytesPerSec = if (isFinished) 0L else (if (calculatedSpeed > 0L) calculatedSpeed else existing?.speedBytesPerSec ?: 0L),
                status = if (isFinished) ReceiverTransferStatus.COMPLETED else ReceiverTransferStatus.TRANSFERRING
            )
            val updatedDevices = current.connectedDevices.toMutableList()
            if (updatedDevices.none { it.fingerprint == device.fingerprint }) {
                updatedDevices.add(device)
            }
            current.copy(
                receiverProgressMap = updatedMap,
                connectedDevices = updatedDevices
            )
        }
    }

    fun onReceiverCompleted(device: DeviceProfile) {
        lastReceiverProgressTime.remove(device.fingerprint)
        _uiState.update { current ->
            val updatedMap = current.receiverProgressMap.toMutableMap()
            val existing = updatedMap[device.fingerprint]
            val total = existing?.totalBytes ?: current.totalBytes
            updatedMap[device.fingerprint] = ReceiverProgressInfo(
                device = device,
                bytesTransferred = total,
                totalBytes = total,
                speedBytesPerSec = 0L,
                status = ReceiverTransferStatus.COMPLETED
            )
            current.copy(receiverProgressMap = updatedMap)
        }
    }

    fun onReceiverFailed(device: DeviceProfile, error: Throwable) {
        lastReceiverProgressTime.remove(device.fingerprint)
        _uiState.update { current ->
            val updatedMap = current.receiverProgressMap.toMutableMap()
            val existing = updatedMap[device.fingerprint]
            updatedMap[device.fingerprint] = ReceiverProgressInfo(
                device = device,
                bytesTransferred = existing?.bytesTransferred ?: 0L,
                totalBytes = existing?.totalBytes ?: current.totalBytes,
                speedBytesPerSec = 0L,
                status = ReceiverTransferStatus.FAILED,
                errorMessage = error.message
            )
            current.copy(receiverProgressMap = updatedMap)
        }
    }

    private fun cleanupSessionCache(sessionId: String) {
        try {
            activePreparedFile?.let { file ->
                if (file.isDirectory && file.name.startsWith(AppConstants.StorageDirectory.BATCH_DIR_PREFIX)) {
                    file.deleteRecursively()
                }
            }
        } catch (_: Exception) {}
        activePreparedFile = null
        lastReceiverProgressTime.clear()
    }

    /**
     * Người gửi chủ động thu hồi (revoke) quyền kết nối của một thiết bị nhận khỏi phiên chia sẻ.
     * Thao tác này giải phóng 1 slot trong ReceiverRegistry để cho phép thiết bị khác kết nối,
     * theo đúng đặc tả kiến trúc v2.2.
     */
    fun revokeReceiver(fingerprint: String) {
        lanEngine?.revokeReceiver(fingerprint)
        approvedPeerFingerprints.remove(fingerprint)
        rejectedPeerFingerprints.add(fingerprint)
        lastReceiverProgressTime.remove(fingerprint)
        _uiState.update { current ->
            val updatedDevices = current.connectedDevices.filterNot { it.fingerprint == fingerprint }
            val updatedMap = current.receiverProgressMap.toMutableMap()
            updatedMap.remove(fingerprint)
            current.copy(
                connectedDevices = updatedDevices,
                receiverProgressMap = updatedMap
            )
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
        approvedPeerFingerprints.clear()
        rejectedPeerFingerprints.clear()
        try {
            serviceLauncher?.stopService()
        } catch (_: Exception) {}
        cleanupSessionCache(sessionId)

        _uiState.update {
            it.copy(
                isSharing = false,
                sessionId = "",
                qrBitmap = null,
                qrPayload = null,
                pendingApprovalDevice = null,
                connectedDevices = emptyList(),
                receiverProgressMap = emptyMap()
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
        return 0L
    }

    override fun onCleared() {
        super.onCleared()
        ttlTimerJob?.cancel()
        serverJob?.cancel()
        relayJob?.cancel()
        lanEngine?.cancel()
        relayEngine?.cancel()
        pendingApprovalDeferred?.complete(false)
        pendingApprovalDeferred = null
        try {
            serviceLauncher?.stopService()
        } catch (_: Exception) {}
        cleanupSessionCache(_uiState.value.sessionId)
    }
}
