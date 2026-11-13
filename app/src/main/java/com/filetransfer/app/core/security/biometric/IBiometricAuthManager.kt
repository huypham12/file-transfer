package com.filetransfer.app.core.security.biometric

import androidx.fragment.app.FragmentActivity

/**
 * Các trạng thái khả dụng của sinh trắc học và khóa thiết bị.
 */
enum class BiometricStatus {
    AVAILABLE,              // Sẵn sàng (Strong Biometric: Vân tay/khuôn mặt)
    CREDENTIAL_AVAILABLE,   // Sẵn sàng qua mã PIN/Mật khẩu khóa màn hình hệ thống
    NONE_ENROLLED,          // Thiết bị có hỗ trợ nhưng chưa đăng ký vân tay hay mã PIN
    NO_HARDWARE,            // Thiết bị không có cảm biến sinh trắc học
    HW_UNAVAILABLE          // Cảm biến bận hoặc tạm thời không khả dụng
}

/**
 * Giao diện trừu tượng hóa dịch vụ xác thực sinh trắc học vân tay / khuôn mặt.
 * Tuân thủ Clean Architecture: Cho phép mock hoặc kiểm thử trên môi trường không có UI thật.
 */
interface IBiometricAuthManager {

    /**
     * Kiểm tra thiết bị có thể xác thực (sinh trắc học hoặc mã PIN/mật khẩu màn hình khóa).
     */
    fun canAuthenticate(): Boolean

    /**
     * Kiểm tra chi tiết trạng thái năng lực sinh trắc học của thiết bị.
     */
    fun getBiometricStatus(): BiometricStatus = BiometricStatus.AVAILABLE

    /**
     * Kích hoạt hộp thoại BiometricPrompt của hệ điều hành Android.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String = "Hủy bỏ",
        allowDeviceCredential: Boolean = true,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errString: String) -> Unit,
        onFailed: () -> Unit
    )
}
