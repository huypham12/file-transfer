package com.teamshare.app.domain.model

/**
 * Định nghĩa lỗi chuẩn hóa cấp Domain theo nguyên tắc DDD và SDD Chương 10.
 * Hoàn toàn thuần Kotlin (Pure Kotlin), không phụ thuộc vào Android SDK.
 *
 * @property code Mã lỗi chuẩn hóa (vd: "0x101", "0x201")
 * @property technicalMessage Thông điệp kỹ thuật tiếng Anh (phục vụ log, audit và test)
 * @property severity Mức độ nghiêm trọng của sự kiện (INFO, WARNING, SECURITY_ALERT)
 */
sealed class DomainError(
    val code: String,
    val technicalMessage: String,
    val severity: AuditSeverity = AuditSeverity.WARNING
) : Exception("[$code] $technicalMessage") {
    // =========================================================================
    // 0x100: Xác thực, Quyền hạn & Phiên (Authentication & Session)
    // =========================================================================
    object InvalidCredential : DomainError(
        code = ErrorCode.ERR_INVALID_CREDENTIAL,
        technicalMessage = "Session token or credential signature is invalid",
        severity = AuditSeverity.SECURITY_ALERT
    )

    object DeviceRejected : DomainError(
        code = ErrorCode.ERR_DEVICE_REJECTED,
        technicalMessage = "Connection request was rejected by the peer",
        severity = AuditSeverity.WARNING
    )

    object SessionExpired : DomainError(
        code = ErrorCode.ERR_SESSION_EXPIRED,
        technicalMessage = "Sharing session token or QR code has expired (TTL)",
        severity = AuditSeverity.WARNING
    )

    object InvalidRole : DomainError(
        code = ErrorCode.ERR_INVALID_ROLE,
        technicalMessage = "Device role or permissions are not authorized for this operation",
        severity = AuditSeverity.SECURITY_ALERT
    )

    object SessionFull : DomainError(
        code = ErrorCode.ERR_SESSION_FULL,
        technicalMessage = "Sharing session has reached its maximum capacity of 5 devices",
        severity = AuditSeverity.WARNING
    )

    // =========================================================================
    // 0x200: Kết nối Mạng & Relay Server (Network & Relay Connectivity)
    // =========================================================================
    object RelayUnavailable : DomainError(
        code = ErrorCode.ERR_RELAY_UNAVAILABLE,
        technicalMessage = "Unable to connect to Zero-Knowledge Relay Server",
        severity = AuditSeverity.WARNING
    )

    object ServerTimeout : DomainError(
        code = ErrorCode.ERR_SERVER_TIMEOUT,
        technicalMessage = "Connection timed out waiting for peer",
        severity = AuditSeverity.WARNING
    )

    object ServerBindError : DomainError(
        code = ErrorCode.ERR_SERVER_BIND_ERROR,
        technicalMessage = "Failed to bind ServerSocket to listening port",
        severity = AuditSeverity.SECURITY_ALERT
    )

    // =========================================================================
    // 0x300: Lưu trữ & Toàn vẹn Dữ liệu (Storage & Data Integrity)
    // =========================================================================
    object ChecksumMismatch : DomainError(
        code = ErrorCode.ERR_CHECKSUM_MISMATCH,
        technicalMessage = "SHA-256 integrity checksum verification failed",
        severity = AuditSeverity.SECURITY_ALERT
    )

    object InsufficientStorage : DomainError(
        code = ErrorCode.ERR_INSUFFICIENT_STORAGE,
        technicalMessage = "Insufficient storage space available on receiving device",
        severity = AuditSeverity.WARNING
    )

    object RelayChannelBusy : DomainError(
        code = ErrorCode.ERR_RELAY_CHANNEL_BUSY,
        technicalMessage = "Relay channel is currently busy serving another device (1-1 preview mode)",
        severity = AuditSeverity.WARNING
    )

    // =========================================================================
    // 0x400: Phần cứng & Sinh trắc học (Hardware & Biometrics)
    // =========================================================================
    object BiometricFailed : DomainError(
        code = ErrorCode.ERR_BIOMETRIC_FAILED,
        technicalMessage = "Biometric authentication failed or was cancelled",
        severity = AuditSeverity.WARNING
    )

    // =========================================================================
    // 0x500: Lỗi Hệ thống / Ngoại lệ (System & General Exceptions)
    // =========================================================================
    data class GeneralException(val details: String) : DomainError(
        code = ErrorCode.ERR_SYSTEM_EXCEPTION,
        technicalMessage = details.ifBlank { "Unspecified system exception occurred" },
        severity = AuditSeverity.WARNING
    )

    companion object {
        fun fromCode(code: String, fallbackDetail: String = ""): DomainError {
            return when (code) {
                ErrorCode.ERR_INVALID_CREDENTIAL -> InvalidCredential
                ErrorCode.ERR_DEVICE_REJECTED, ErrorCode.ERR_MEMBER_REVOKED -> DeviceRejected
                ErrorCode.ERR_SESSION_EXPIRED, ErrorCode.ERR_INVITATION_EXPIRED -> SessionExpired
                ErrorCode.ERR_INVALID_ROLE -> InvalidRole
                ErrorCode.ERR_SESSION_FULL -> SessionFull
                ErrorCode.ERR_RELAY_UNAVAILABLE -> RelayUnavailable
                ErrorCode.ERR_SERVER_TIMEOUT -> ServerTimeout
                ErrorCode.ERR_SERVER_BIND_ERROR -> ServerBindError
                ErrorCode.ERR_CHECKSUM_MISMATCH -> ChecksumMismatch
                ErrorCode.ERR_INSUFFICIENT_STORAGE -> InsufficientStorage
                ErrorCode.ERR_RELAY_CHANNEL_BUSY -> RelayChannelBusy
                ErrorCode.ERR_BIOMETRIC_FAILED -> BiometricFailed
                else -> GeneralException(fallbackDetail.ifBlank { "Error code: $code" })
            }
        }

        fun fromThrowable(throwable: Throwable?): DomainError {
            if (throwable == null) return GeneralException("Unknown error")
            if (throwable is DomainError) return throwable
            val msg = throwable.message ?: return GeneralException(throwable.javaClass.simpleName)
            for (code in listOf(
                ErrorCode.ERR_INVALID_CREDENTIAL,
                ErrorCode.ERR_DEVICE_REJECTED,
                ErrorCode.ERR_MEMBER_REVOKED,
                ErrorCode.ERR_SESSION_EXPIRED,
                ErrorCode.ERR_INVITATION_EXPIRED,
                ErrorCode.ERR_INVALID_ROLE,
                ErrorCode.ERR_SESSION_FULL,
                ErrorCode.ERR_RELAY_UNAVAILABLE,
                ErrorCode.ERR_SERVER_TIMEOUT,
                ErrorCode.ERR_SERVER_BIND_ERROR,
                ErrorCode.ERR_CHECKSUM_MISMATCH,
                ErrorCode.ERR_INSUFFICIENT_STORAGE,
                ErrorCode.ERR_RELAY_CHANNEL_BUSY,
                ErrorCode.ERR_BIOMETRIC_FAILED
            )) {
                if (msg.contains(code)) {
                    return fromCode(code, msg)
                }
            }
            return GeneralException(msg)
        }
    }
}
