package com.teamshare.app.domain.model

/**
 * Cấu trúc khung tin dữ liệu nhị phân (Binary Frame Protocol) theo SDD Chương 6.
 * Magic cố định 0x5346 ("SF" - Secure File).
 */
data class ChunkFrame(
    val magic: Short = MAGIC,
    val msgType: Byte,
    val streamId: Int,
    val payloadLen: Int,
    val payload: ByteArray
) {
    companion object {
        const val MAGIC: Short = 0x5346.toShort() // "SF"
        const val HEADER_SIZE: Int = 11 // 2 (Magic) + 1 (MsgType) + 4 (StreamId) + 4 (PayloadLen)
        const val MAX_PAYLOAD_SIZE: Int = 1024 * 1024 // 1 MB
    }

    init {
        require(payload.size == payloadLen) {
            "Kích thước payload thực tế (${payload.size}) không khớp với payloadLen ($payloadLen)"
        }
        require(payloadLen <= MAX_PAYLOAD_SIZE) {
            "Kích thước payload ($payloadLen) vượt quá giới hạn tối đa ($MAX_PAYLOAD_SIZE bytes)"
        }
    }

    val isValidMagic: Boolean
        get() = magic == MAGIC

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChunkFrame

        if (magic != other.magic) return false
        if (msgType != other.msgType) return false
        if (streamId != other.streamId) return false
        if (payloadLen != other.payloadLen) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = magic.toInt()
        result = 31 * result + msgType.toInt()
        result = 31 * result + streamId
        result = 31 * result + payloadLen
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

