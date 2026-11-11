package com.teamshare.app.core.protocol

import com.teamshare.app.core.constant.AppConstants
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Các lớp mô hình hóa và tiện ích tuần tự hóa cho 8 loại tải trọng khung tin nhị phân theo SDD Chương 6.2.
 */

// =====================================================================================
// 0x01: CONNECT_REQUEST (SDD Mục 6.2)
// =====================================================================================
data class ConnectRequestPayload(
    val sessionToken: String,
    val deviceModel: String,
    val deviceManufacturer: String,
    val deviceFingerprint: String,
    val clientNonce: String,
    val clientPublicKey: String = "",
    val authSignature: String = ""
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("sessionToken", sessionToken)
            put("deviceModel", deviceModel)
            put("deviceManufacturer", deviceManufacturer)
            put("deviceFingerprint", deviceFingerprint)
            put("clientNonce", clientNonce)
            if (clientPublicKey.isNotBlank()) {
                put("clientPublicKey", clientPublicKey)
            }
            if (authSignature.isNotBlank()) {
                put("authSignature", authSignature)
            }
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): ConnectRequestPayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return ConnectRequestPayload(
                sessionToken = json.optString("sessionToken", ""),
                deviceModel = json.optString("deviceModel", "Unknown Model"),
                deviceManufacturer = json.optString("deviceManufacturer", "Unknown Manufacturer"),
                deviceFingerprint = json.optString("deviceFingerprint", ""),
                clientNonce = json.optString("clientNonce", ""),
                clientPublicKey = json.optString("clientPublicKey", ""),
                authSignature = json.optString("authSignature", "")
            )
        }
    }
}

// =====================================================================================
// 0x09: CONNECT_PENDING (Phê duyệt thủ công)
// =====================================================================================
data class ConnectPendingPayload(
    val timeoutSeconds: Int = 60,
    val message: String = ""
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("timeoutSeconds", timeoutSeconds)
            put("message", message)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): ConnectPendingPayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return ConnectPendingPayload(
                timeoutSeconds = json.optInt("timeoutSeconds", 60),
                message = json.optString("message", "")
            )
        }
    }
}

// =====================================================================================
// 0x02: CONNECT_ACCEPTED (SDD Mục 6.2)
// =====================================================================================
data class ManifestItemPayload(
    val relativePath: String,
    val fileSize: Long,
    val mimeType: String,
    val sha256: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("relativePath", relativePath)
        put("fileSize", fileSize)
        put("mimeType", mimeType)
        put("sha256", sha256)
    }

    companion object {
        fun fromJson(json: JSONObject): ManifestItemPayload = ManifestItemPayload(
            relativePath = json.optString("relativePath", ""),
            fileSize = json.optLong("fileSize", 0L),
            mimeType = json.optString("mimeType", "application/octet-stream"),
            sha256 = json.optString("sha256", "")
        )
    }
}

data class ConnectAcceptedPayload(
    val status: String, // "APPROVED" | "REJECTED"
    val rejectionReason: String? = null,
    val reasonCode: String? = null, // e.g. "0x105" for ERR_SESSION_FULL
    val transferType: String = "SINGLE_FILE", // "SINGLE_FILE" | "BATCH_FILES" | "FOLDER_ZIP"
    val manifest: List<ManifestItemPayload> = emptyList()
) {
    val isApproved: Boolean get() = status.equals("APPROVED", ignoreCase = true)

    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("status", status)
            if (rejectionReason != null) {
                put("rejectionReason", rejectionReason)
            }
            if (reasonCode != null) {
                put("reasonCode", reasonCode)
            }
            put("transferType", transferType)
            val manifestArray = JSONArray()
            manifest.forEach { manifestArray.put(it.toJson()) }
            put("manifest", manifestArray)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"

        fun fromBytes(bytes: ByteArray): ConnectAcceptedPayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val manifestList = mutableListOf<ManifestItemPayload>()
            val manifestArray = json.optJSONArray("manifest")
            if (manifestArray != null) {
                for (i in 0 until manifestArray.length()) {
                    manifestList.add(ManifestItemPayload.fromJson(manifestArray.getJSONObject(i)))
                }
            }
            return ConnectAcceptedPayload(
                status = json.optString("status", STATUS_REJECTED),
                rejectionReason = if (json.has("rejectionReason") && !json.isNull("rejectionReason")) {
                    json.getString("rejectionReason")
                } else null,
                reasonCode = if (json.has("reasonCode") && !json.isNull("reasonCode")) {
                    json.getString("reasonCode")
                } else null,
                transferType = json.optString("transferType", "SINGLE_FILE"),
                manifest = manifestList
            )
        }
    }
}

// =====================================================================================
// 0x03: REQUEST_STREAM (SDD Mục 6.2)
// =====================================================================================
data class RequestStreamPayload(
    val sessionId: String,
    val startOffset: Long = 0L
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("sessionId", sessionId)
            put("startOffset", startOffset)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): RequestStreamPayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return RequestStreamPayload(
                sessionId = json.optString("sessionId", ""),
                startOffset = json.optLong("startOffset", 0L)
            )
        }
    }
}

// =====================================================================================
// 0x04: DATA_CHUNK (SDD Mục 6.2)
// Header nhị phân: [chunkSeq (4B Int32 BigEndian)] + [chunkOffset (8B Int64 BigEndian)] + [Data]
// =====================================================================================
data class DataChunkPayload(
    val chunkSeq: Int,
    val chunkOffset: Long,
    val chunkData: ByteArray
) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + chunkData.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(chunkSeq)
        buffer.putLong(chunkOffset)
        buffer.put(chunkData)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DataChunkPayload
        if (chunkSeq != other.chunkSeq) return false
        if (chunkOffset != other.chunkOffset) return false
        if (!chunkData.contentEquals(other.chunkData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chunkSeq
        result = 31 * result + chunkOffset.hashCode()
        result = 31 * result + chunkData.contentHashCode()
        return result
    }

    companion object {
        const val HEADER_SIZE = 12 // 4B chunkSeq + 8B chunkOffset

        fun fromBytes(bytes: ByteArray): DataChunkPayload {
            require(bytes.size >= HEADER_SIZE) {
                "Dữ liệu DATA_CHUNK không đủ kích thước header tối thiểu ($HEADER_SIZE bytes)"
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val chunkSeq = buffer.int
            val chunkOffset = buffer.long
            val dataLen = bytes.size - HEADER_SIZE
            val chunkData = ByteArray(dataLen)
            buffer.get(chunkData)
            return DataChunkPayload(chunkSeq, chunkOffset, chunkData)
        }
    }
}

// =====================================================================================
// 0x05: CHUNK_ACK (SDD Mục 6.2)
// Cấu trúc nhị phân: [confirmedOffset (8B Int64 BigEndian)]
// =====================================================================================
data class ChunkAckPayload(
    val confirmedOffset: Long
) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(PAYLOAD_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(confirmedOffset)
        return buffer.array()
    }

    companion object {
        const val PAYLOAD_SIZE = 8

        fun fromBytes(bytes: ByteArray): ChunkAckPayload {
            require(bytes.size >= PAYLOAD_SIZE) {
                "Dữ liệu CHUNK_ACK không đủ kích thước ($PAYLOAD_SIZE bytes)"
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val confirmedOffset = buffer.long
            return ChunkAckPayload(confirmedOffset)
        }
    }
}

// =====================================================================================
// 0x06: CONTROL_SIGNAL (SDD Mục 6.2)
// =====================================================================================
data class ControlSignalPayload(
    val command: String, // "PAUSE", "RESUME", "CANCEL"
    val reasonCode: Int = 0
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("command", command)
            put("reasonCode", reasonCode)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val CMD_PAUSE = "PAUSE"
        const val CMD_RESUME = "RESUME"
        const val CMD_CANCEL = "CANCEL"
        const val CMD_REVOKE = "REVOKE"

        fun fromBytes(bytes: ByteArray): ControlSignalPayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return ControlSignalPayload(
                command = json.optString("command", ""),
                reasonCode = json.optInt("reasonCode", 0)
            )
        }
    }
}

// =====================================================================================
// 0x07: VERIFY_RESULT (SDD Mục 6.2)
// =====================================================================================
data class VerifyResultPayload(
    val sessionId: String,
    val status: String, // "SUCCESS", "CHECKSUM_FAILED"
    val actualSha256: String
) {
    val isSuccess: Boolean get() = status.equals("SUCCESS", ignoreCase = true)

    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("sessionId", sessionId)
            put("status", status)
            put("actualSha256", actualSha256)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_CHECKSUM_FAILED = "CHECKSUM_FAILED"

        fun fromBytes(bytes: ByteArray): VerifyResultPayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return VerifyResultPayload(
                sessionId = json.optString("sessionId", ""),
                status = json.optString("status", STATUS_CHECKSUM_FAILED),
                actualSha256 = json.optString("actualSha256", "")
            )
        }
    }
}

// =====================================================================================
// 0x08: ERROR_ALERT (SDD Mục 6.2)
// =====================================================================================
data class ErrorAlertPayload(
    val errorCode: String,
    val errorMessage: String
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("errorCode", errorCode)
            put("errorMessage", errorMessage)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): ErrorAlertPayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return ErrorAlertPayload(
                errorCode = json.optString("errorCode", "0x500"),
                errorMessage = json.optString("errorMessage", "Unknown error")
            )
        }
    }
}

// =====================================================================================
// BACKWARD COMPATIBILITY STUBS (Hỗ trợ chuyển tiếp)
// =====================================================================================

@Deprecated("Sử dụng ConnectRequestPayload thay thế")
data class HandshakeAuthPayload(
    val teamId: String = "",
    val memberId: String = "",
    val deviceFingerprint: String = "",
    val role: String = "",
    val credentialData: String = "",
    val clientNonce: String = "",
    val deviceManufacturer: String = "",
    val deviceModel: String = ""
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("teamId", teamId)
            put("memberId", memberId)
            put("deviceFingerprint", deviceFingerprint)
            put("role", role)
            put("credentialData", credentialData)
            put("clientNonce", clientNonce)
            put("deviceManufacturer", deviceManufacturer)
            put("deviceModel", deviceModel)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): HandshakeAuthPayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return HandshakeAuthPayload(
                teamId = json.optString("teamId", ""),
                memberId = json.optString("memberId", ""),
                deviceFingerprint = json.optString("deviceFingerprint", ""),
                role = json.optString("role", ""),
                credentialData = json.optString("credentialData", ""),
                clientNonce = json.optString("clientNonce", ""),
                deviceManufacturer = json.optString("deviceManufacturer", ""),
                deviceModel = json.optString("deviceModel", "")
            )
        }
    }
}

@Deprecated("Sử dụng ConnectAcceptedPayload thay thế")
data class TransferManifestPayload(
    val docId: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val mimeType: String = "application/octet-stream",
    val expectedSha256: String = "",
    val chunkSize: Int = AppConstants.Buffer.CHUNK_SIZE_BYTES
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("docId", docId)
            put("fileName", fileName)
            put("fileSize", fileSize)
            put("mimeType", mimeType)
            put("expectedSha256", expectedSha256)
            put("chunkSize", chunkSize)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): TransferManifestPayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return TransferManifestPayload(
                docId = json.optString("docId", ""),
                fileName = json.optString("fileName", ""),
                fileSize = json.optLong("fileSize", 0L),
                mimeType = json.optString("mimeType", "application/octet-stream"),
                expectedSha256 = json.optString("expectedSha256", ""),
                chunkSize = json.optInt("chunkSize", AppConstants.Buffer.CHUNK_SIZE_BYTES)
            )
        }
    }
}

@Deprecated("Sử dụng RequestStreamPayload thay thế")
data class ManifestResponsePayload(
    val accepted: Boolean,
    val startOffset: Long = 0L,
    val rejectionReason: String? = null
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("accepted", accepted)
            put("startOffset", startOffset)
            if (rejectionReason != null) {
                put("rejectionReason", rejectionReason)
            }
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): ManifestResponsePayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return ManifestResponsePayload(
                accepted = json.optBoolean("accepted", false),
                startOffset = json.optLong("startOffset", 0L),
                rejectionReason = if (json.has("rejectionReason") && !json.isNull("rejectionReason")) {
                    json.getString("rejectionReason")
                } else null
            )
        }
    }
}

@Deprecated("Đã loại bỏ trong giao thức mới")
data class RevokeSignalPayload(
    val revokedMemberId: String = "",
    val ownerSignature: String = ""
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("revokedMemberId", revokedMemberId)
            put("ownerSignature", ownerSignature)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): RevokeSignalPayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return RevokeSignalPayload(
                revokedMemberId = json.optString("revokedMemberId", ""),
                ownerSignature = json.optString("ownerSignature", "")
            )
        }
    }
}
