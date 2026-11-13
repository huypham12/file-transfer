package com.filetransfer.app.core.platform.device

import android.os.Build
import com.filetransfer.app.core.security.crypto.Sha256StreamCalculator
import com.filetransfer.app.core.security.keystore.IKeyManager
import com.filetransfer.app.domain.model.DeviceProfile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * Hiện thực hóa trích xuất danh tính phần cứng thiết bị Android theo chuẩn SRS FR-DEV-01.
 * Trích xuất Build.MANUFACTURER và Build.MODEL để hiển thị rõ ràng trên Dialog phê duyệt,
 * kết hợp dấu vân tay SHA-256 từ khóa định danh thiết bị trong Keystore.
 */
class DeviceIdentityManager(
    private val keyManager: IKeyManager? = null
) : IDeviceIdentityManager {

    companion object {
        const val DEVICE_KEY_ALIAS = IKeyManager.MEMBER_IDENTITY_KEY_ALIAS
    }

    private val overrideProfile = AtomicReference<DeviceProfile?>(null)

    /**
     * Ghi đè hồ sơ định danh phục vụ kiểm thử đơn vị hoặc giả lập thiết bị đối tác.
     */
    fun setOverrideProfile(profile: DeviceProfile?) {
        overrideProfile.set(profile)
    }

    override fun getDeviceProfile(): DeviceProfile {
        val override = overrideProfile.get()
        if (override != null) return override

        val rawManufacturer = try {
            Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: "Android"
        } catch (_: Throwable) {
            "Android"
        }

        val rawModel = try {
            Build.MODEL?.takeIf { it.isNotBlank() } ?: "Generic Device"
        } catch (_: Throwable) {
            "Generic Device"
        }

        val formattedManufacturer = rawManufacturer.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }

        val fingerprint = resolveDeviceFingerprint()

        return DeviceProfile(
            manufacturer = formattedManufacturer,
            model = rawModel,
            fingerprint = fingerprint
        )
    }

    private fun resolveDeviceFingerprint(): String {
        return try {
            if (keyManager != null) {
                val pubKey = keyManager.getPublicKey(DEVICE_KEY_ALIAS)
                    ?: keyManager.generateMemberIdentityKey()
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(pubKey.encoded)
                digest.joinToString("") { "%02x".format(it) }
            } else {
                // Fallback khi không có Keystore: Dẫn xuất mã băm SHA-256 từ thông tin phần cứng
                val seed = "${Build.MANUFACTURER}:${Build.MODEL}:${Build.BOARD}:${Build.HARDWARE}"
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(seed.toByteArray(Charsets.UTF_8))
                digest.joinToString("") { "%02x".format(it) }
            }
        } catch (_: Throwable) {
            "0000000000000000000000000000000000000000000000000000000000000000"
        }
    }
}

