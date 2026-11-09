package com.teamshare.app.presentation.ui.mapper

import android.content.Context
import androidx.annotation.StringRes
import com.example.file_tranfer.R
import com.teamshare.app.domain.model.DomainError

/**
 * Cầu nối chuyển đổi giữa DomainError (Pure Kotlin) và String Resource của Android UI.
 * Tuân thủ nghiêm ngặt nguyên tắc DDD: Tầng Domain không dính Android R.string,
 * toàn bộ việc chuyển đổi bản địa hóa sang tiếng Việt nằm ở tầng Presentation.
 */
object UiErrorMapper {

    @StringRes
    fun mapToStringRes(error: DomainError): Int {
        return when (error) {
            is DomainError.InvalidCredential -> R.string.err_0x101_invalid_credential
            is DomainError.DeviceRejected -> R.string.err_0x102_device_rejected
            is DomainError.SessionExpired -> R.string.err_0x103_session_expired
            is DomainError.InvalidRole -> R.string.err_0x104_invalid_role
            is DomainError.RelayUnavailable -> R.string.err_0x201_relay_unavailable
            is DomainError.ServerTimeout -> R.string.err_0x202_server_timeout
            is DomainError.ServerBindError -> R.string.err_0x203_bind_error
            is DomainError.ChecksumMismatch -> R.string.err_0x301_checksum_mismatch
            is DomainError.InsufficientStorage -> R.string.err_0x302_insufficient_storage
            is DomainError.RelayChannelBusy -> R.string.err_0x303_relay_busy
            is DomainError.BiometricFailed -> R.string.err_0x401_biometric_failed
            is DomainError.GeneralException -> R.string.err_unknown
        }
    }

    /**
     * Lấy chuỗi thông điệp tiếng Việt đã được bản địa hóa từ đối tượng DomainError.
     */
    fun getLocalizedMessage(context: Context, error: DomainError): String {
        val resId = mapToStringRes(error)
        return if (error is DomainError.GeneralException) {
            context.getString(resId, error.details)
        } else {
            context.getString(resId)
        }
    }

    /**
     * Lấy chuỗi thông điệp tiếng Việt trực tiếp từ mã lỗi string (vd: "0x101").
     */
    fun getLocalizedMessageFromCode(context: Context, code: String, fallback: String = ""): String {
        val domainError = DomainError.fromCode(code, fallback)
        return getLocalizedMessage(context, domainError)
    }

    /**
     * Lấy chuỗi thông điệp tiếng Việt từ bất kỳ Ngoại lệ hoặc Lỗi nào.
     */
    fun getLocalizedMessageFromThrowable(context: Context, throwable: Throwable?): String {
        val domainError = DomainError.fromThrowable(throwable)
        return getLocalizedMessage(context, domainError)
    }
}
