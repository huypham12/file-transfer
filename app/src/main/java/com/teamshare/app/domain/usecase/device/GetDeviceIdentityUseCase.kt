package com.teamshare.app.domain.usecase.device

import com.teamshare.app.core.platform.device.IDeviceIdentityManager
import com.teamshare.app.domain.model.DeviceProfile

/**
 * FR-DEV-01: Định danh thiết bị & Thông tin phần cứng.
 * Tuân thủ SDD Mục 4.4 và Bảng 12.1.
 */
class GetDeviceIdentityUseCase(
    private val deviceIdentityManager: IDeviceIdentityManager
) {
    /**
     * Lấy hồ sơ định danh phần cứng (Hãng sản xuất, Model và Fingerprint) của thiết bị hiện tại.
     */
    fun execute(): DeviceProfile {
        return deviceIdentityManager.getDeviceProfile()
    }

    operator fun invoke(): DeviceProfile = execute()
}

