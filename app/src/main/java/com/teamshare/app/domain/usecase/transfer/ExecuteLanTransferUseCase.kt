package com.teamshare.app.domain.usecase.transfer

import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.domain.model.TransferProgress
import com.teamshare.app.domain.model.TransferSession
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.security.PublicKey

/**
 * UC-12: Truyền phát trực tiếp P2P trong mạng nội bộ LAN (SRS FR-TRF-02).
 * Điều phối LanTransferEngine qua TransferCoordinator, bọc TLS 1.3 socket,
 * truyền khối 64KB/256KB qua BufferPoolManager và báo cáo tiến độ thời gian thực.
 */
class ExecuteLanTransferUseCase(
    private val transferCoordinator: TransferCoordinator
) {
    operator fun invoke(
        session: TransferSession,
        peerIp: String,
        file: File? = null,
        targetDir: File? = null,
        port: Int = 0,
        teamId: String = "",
        memberId: String = "",
        deviceFingerprint: String = "",
        role: String = "MEMBER",
        credentialData: String = "",
        ownerPublicKey: PublicKey? = null
    ): Flow<TransferProgress> {
        return transferCoordinator.startTransfer(
            session = session,
            file = file,
            targetDirectory = targetDir,
            peerIp = peerIp,
            port = port,
            documentName = file?.name ?: "document_${session.docId}",
            teamId = teamId,
            memberId = memberId,
            deviceFingerprint = deviceFingerprint,
            role = role,
            credentialData = credentialData,
            ownerPublicKey = ownerPublicKey
        )
    }
}

