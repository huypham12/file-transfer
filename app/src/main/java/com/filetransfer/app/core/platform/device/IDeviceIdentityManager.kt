package com.filetransfer.app.core.platform.device

import com.filetransfer.app.domain.model.DeviceProfile

/**
 * Giao diện quản lý thông tin định danh và hồ sơ phần cứng thiết bị.
 * Tuân thủ yêu cầu SRS FR-DEV-01 và SDD mục 4.4, 7.4.
 */
interface IDeviceIdentityManager {
    /**
     * Lấy hồ sơ định danh phần cứng (Hãng sản xuất, Model và Fingerprint) của thiết bị hiện tại.
     */
    fun getDeviceProfile(): DeviceProfile
}

