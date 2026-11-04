package com.teamshare.app.domain.usecase.platform

import com.teamshare.app.core.platform.service.ITransferServiceController
import com.teamshare.app.core.platform.service.TransferServiceController
import com.teamshare.app.domain.model.ServiceAction

/**
 * UC-18: Quản lý truyền phát ngầm chống Doze Mode qua Foreground Service (SRS FR-MOB-03).
 * Điều khiển bộ điều khiển dịch vụ TransferServiceController:
 * START (khởi tạo session notification), PAUSE, RESUME, CANCEL, STOP.
 */
class ManageForegroundServiceUseCase(
    private val serviceController: ITransferServiceController = TransferServiceController
) {
    operator fun invoke(
        action: ServiceAction,
        sessionId: String? = null,
        docName: String? = null,
        totalBytes: Long = 0L,
        isLan: Boolean = true
    ) {
        when (action) {
            ServiceAction.START -> {
                serviceController.startSession(
                    sessionId = sessionId ?: "",
                    documentName = docName ?: "Tài liệu",
                    totalBytes = totalBytes,
                    isLan = isLan
                )
            }
            ServiceAction.PAUSE -> serviceController.requestPause()
            ServiceAction.RESUME -> serviceController.requestResume()
            ServiceAction.CANCEL -> serviceController.requestCancel()
            ServiceAction.STOP -> serviceController.reset()
        }
    }
}

