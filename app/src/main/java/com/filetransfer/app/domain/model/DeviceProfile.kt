package com.filetransfer.app.domain.model

/**
 * Hồ sơ định danh phần cứng và dấu vân tay thiết bị (Device Profile) theo SRS FR-DEV-01 & SDD Chương 4.
 *
 * @property manufacturer Hãng sản xuất phần cứng (vd: Xiaomi, Samsung, Google)
 * @property model Model thương mại phần cứng (vd: Redmi Turbo 4 Pro, SM-S918B)
 * @property fingerprint Dấu vân tay định danh thiết bị độc nhất (SHA-256 từ khóa công khai Keystore)
 */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val fingerprint: String
) {
    val deviceId: String get() = fingerprint
}

