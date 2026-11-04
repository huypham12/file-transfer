package com.teamshare.app.core.protocol

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Các lớp mô hình hóa và tiện ích tuần tự hóa cho 8 loại tải trọng khung tin nhị phân theo SDD Chương 6.2.
 */

// -------------------------------------------------------------------------------------
// 0x01: HANDSHAKE_AUTH
// -------------------------------------------------------------------------------------
data class HandshakeAuthPayload(
    val teamId: String,
    val memberId: String,
    val deviceFingerprint: String,
    val role: String,
    val credentialData: String,
    val clientNonce: String
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("teamId", teamId)
            put("memberId", memberId)
            put("deviceFingerprint", deviceFingerprint)
            put("role", role)
            put("credentialData", credentialData)
            put("clientNonce", clientNonce)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): HandshakeAuthPayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return HandshakeAuthPayload(
                teamId = json.getString("teamId"),
                memberId = json.getString("memberId"),
                deviceFingerprint = json.getString("deviceFingerprint"),
                role = json.getString("role"),
                credentialData = json.getString("credentialData"),
                clientNonce = json.getString("clientNonce")
            )
        }
    }
}

// -------------------------------------------------------------------------------------
// 0x02: TRANSFER_MANIFEST
// -------------------------------------------------------------------------------------
data class TransferManifestPayload(
    val docId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val expectedSha256: String,
    val chunkSize: Int = 65536
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
                docId = json.getString("docId"),
                fileName = json.getString("fileName"),
                fileSize = json.getLong("fileSize"),
                mimeType = json.getString("mimeType"),
                expectedSha256 = json.getString("expectedSha256"),
                chunkSize = json.optInt("chunkSize", 65536)
            )
        }
    }
}

// -------------------------------------------------------------------------------------
// 0x03: MANIFEST_RESPONSE
// -------------------------------------------------------------------------------------
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
                accepted = json.getBoolean("accepted"),
                startOffset = json.optLong("startOffset", 0L),
                rejectionReason = if (json.has("rejectionReason") && !json.isNull("rejectionReason")) {
                    json.getString("rejectionReason")
                } else null
            )
        }
    }
}

// -------------------------------------------------------------------------------------
// 0x04: DATA_CHUNK
// Header nhị phân: [chunkSeq (4B Int32 BigEndian)] + [chunkOffset (8B Int64 BigEndian)] + [Data]
// -------------------------------------------------------------------------------------
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

// -------------------------------------------------------------------------------------
// 0x05: CHUNK_ACK
// Cấu trúc nhị phân: [confirmedOffset (8B Int64 BigEndian)]
// -------------------------------------------------------------------------------------
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

// -------------------------------------------------------------------------------------
// 0x06: CONTROL_SIGNAL
// -------------------------------------------------------------------------------------
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
        fun fromBytes(bytes: ByteArray): ControlSignalPayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return ControlSignalPayload(
                command = json.getString("command"),
                reasonCode = json.optInt("reasonCode", 0)
            )
        }
    }
}

// -------------------------------------------------------------------------------------
// 0x07: REVOKE_SIGNAL
// -------------------------------------------------------------------------------------
data class RevokeSignalPayload(
    val revokedMemberId: String,
    val ownerSignature: String
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
                revokedMemberId = json.getString("revokedMemberId"),
                ownerSignature = json.getString("ownerSignature")
            )
        }
    }
}

// -------------------------------------------------------------------------------------
// 0x08: VERIFY_RESULT
// -------------------------------------------------------------------------------------
data class VerifyResultPayload(
    val docId: String,
    val status: String, // "SUCCESS", "CHECKSUM_FAILED"
    val actualSha256: String
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("docId", docId)
            put("status", status)
            put("actualSha256", actualSha256)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): VerifyResultPayload {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return VerifyResultPayload(
                docId = json.getString("docId"),
                status = json.getString("status"),
                actualSha256 = json.getString("actualSha256")
            )
        }
    }
}

