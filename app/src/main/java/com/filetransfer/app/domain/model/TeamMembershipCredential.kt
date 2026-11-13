package com.filetransfer.app.domain.model

/**
 * Lớp chứng chỉ thành viên tạm thời (Compatibility Stub).
 * Được bảo lưu trong giai đoạn chuyển tiếp Stage 1 -> Stage 2
 * và sẽ được thay thế hoàn toàn bởi khung gói tin Binary Frame V2 & Session Token ở Stage 3.
 */
@Deprecated("Sẽ được thay thế bởi Session Token và Binary Frame V2 trong Stage 3")
data class TeamMembershipCredential(
    val teamId: String = "",
    val memberId: String = "",
    val deviceFingerprint: String = "",
    val role: MemberRole = MemberRole.MEMBER,
    val issuedAt: Long = 0L,
    val ownerSignature: String = ""
)

