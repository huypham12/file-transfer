package com.teamshare.app.domain.usecase.transfer

import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferProgress
import com.teamshare.app.domain.model.TransferSession
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.security.PublicKey

/**
 * UC-13: Truyền phát E2EE qua Zero-Knowledge Relay Server (Internet) (SRS FR-TRF-03).
 * Điều phối RelayClientEngine qua TransferCoordinator, bọc mã hóa đầu cuối AES-256-GCM,
 * trao đổi khóa phiên E2EE độc lập với Relay Server và báo cáo tiến độ kèm mã SAS.
 */
class ExecuteRelayTransferUseCase(
    private val transferCoordinator: TransferCoordinator
) {
    operator fun invoke(
        session: TransferSession,
        file: File? = null,
        targetDir: File? = null,
        relayUrl: String? = null,
        streamId: String? = null,
        teamId: String = "",
        memberId: String = "",
        deviceFingerprint: String = "",
        role: String = "MEMBER",
        credentialData: String = "",
        ownerPublicKey: PublicKey? = null,
        peerFingerprint: String = "",
        peerEphemeralPublicKey: ByteArray? = null,
        expectedSha256: String? = null
    ): Flow<TransferProgress> {
        return transferCoordinator.startTransfer(
            session = session.copy(channel = TransferChannel.ZERO_KNOWLEDGE_RELAY),
            file = file,
            targetDirectory = targetDir,
            peerIp = null,
            relayUrl = relayUrl,
            streamId = streamId,
            documentName = file?.name ?: "document_${session.docId}",
            teamId = teamId,
            memberId = memberId,
            deviceFingerprint = deviceFingerprint,
            role = role,
            credentialData = credentialData,
            ownerPublicKey = ownerPublicKey,
            peerFingerprint = peerFingerprint,
            peerEphemeralPublicKey = peerEphemeralPublicKey,
            expectedSha256 = expectedSha256
        )
    }
}

