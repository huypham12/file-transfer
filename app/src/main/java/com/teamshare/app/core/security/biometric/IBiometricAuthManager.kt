package com.teamshare.app.core.security.biometric

import androidx.fragment.app.FragmentActivity

/**
 * Giao diện trừu tượng hóa dịch vụ xác thực sinh trắc học vân tay / khuôn mặt.
 * Tuân thủ Clean Architecture: Cho phép mock hoặc kiểm thử trên môi trường không có UI thật.
 */
interface IBiometricAuthManager {

    /**
     * Kiểm tra thiết bị có phần cứng sinh trắc học và người dùng đã đăng ký vân tay hay chưa.
     */
    fun canAuthenticate(): Boolean

    /**
     * Kích hoạt hộp thoại BiometricPrompt của hệ điều hành Android.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String = "Hủy bỏ",
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errString: String) -> Unit,
        onFailed: () -> Unit
    )
}

