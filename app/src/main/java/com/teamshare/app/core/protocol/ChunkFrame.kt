package com.teamshare.app.core.protocol

import java.io.IOException

/**
 * Ngoại lệ xảy ra khi khung tin nhị phân bị hỏng cấu trúc, sai Magic bytes hoặc kích thước payload bất thường.
 */
class FrameCorruptedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Đại diện cho khung tin nhị phân trao đổi qua mạng Socket LAN theo đặc tả SDD Chương 6.2.
 * Cấu trúc: [Magic 2B: 0x5346] [MsgType 1B] [StreamId 4B] [PayloadLen 4B] [Payload Data N Bytes]
 */
data class ChunkFrame(
    val msgType: Byte,
    val streamId: Int,
    val payload: ByteArray
) {
    val payloadLength: Int get() = payload.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChunkFrame
        if (msgType != other.msgType) return false
        if (streamId != other.streamId) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = msgType.toInt()
        result = 31 * result + streamId
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val MAGIC_BYTE_0: Byte = 0x53 // 'S'
        const val MAGIC_BYTE_1: Byte = 0x46 // 'F'
        val MAGIC_BYTES = byteArrayOf(MAGIC_BYTE_0, MAGIC_BYTE_1)
        const val MAGIC: Short = 0x5346.toShort()

        const val HEADER_SIZE = 11
        const val MAX_PAYLOAD_SIZE = 1024 * 1024 // 1MB tối đa cho mỗi khung tin

        // SDD Mục 6.2: 8 loại gói tin nghiệp vụ chuẩn
        const val TYPE_CONNECT_REQUEST: Byte = 0x01
        const val TYPE_CONNECT_ACCEPTED: Byte = 0x02
        const val TYPE_REQUEST_STREAM: Byte = 0x03
        const val TYPE_DATA_CHUNK: Byte = 0x04
        const val TYPE_CHUNK_ACK: Byte = 0x05
        const val TYPE_CONTROL_SIGNAL: Byte = 0x06
        const val TYPE_VERIFY_RESULT: Byte = 0x07
        const val TYPE_ERROR_ALERT: Byte = 0x08
        const val TYPE_CONNECT_PENDING: Byte = 0x09

        // Aliases hỗ trợ chuyển giao
        @Deprecated("Sử dụng TYPE_CONNECT_REQUEST thay thế", ReplaceWith("TYPE_CONNECT_REQUEST"))
        const val TYPE_HANDSHAKE_AUTH: Byte = 0x01
        @Deprecated("Sử dụng TYPE_CONNECT_ACCEPTED thay thế", ReplaceWith("TYPE_CONNECT_ACCEPTED"))
        const val TYPE_TRANSFER_MANIFEST: Byte = 0x02
        @Deprecated("Sử dụng TYPE_REQUEST_STREAM thay thế", ReplaceWith("TYPE_REQUEST_STREAM"))
        const val TYPE_MANIFEST_RESPONSE: Byte = 0x03
        @Deprecated("Sử dụng TYPE_VERIFY_RESULT thay thế", ReplaceWith("TYPE_VERIFY_RESULT"))
        const val TYPE_REVOKE_SIGNAL: Byte = 0x07

        fun isValidType(type: Byte): Boolean {
            return type in TYPE_CONNECT_REQUEST..TYPE_CONNECT_PENDING
        }
    }
}

