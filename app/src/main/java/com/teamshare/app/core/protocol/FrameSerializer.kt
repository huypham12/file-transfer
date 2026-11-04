package com.teamshare.app.core.protocol

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Đóng gói và tuần tự hóa các thực thể ChunkFrame thành mảng byte nhị phân theo đặc tả SDD Chương 6.2.
 */
class FrameSerializer {

    /**
     * Tuần tự hóa một ChunkFrame thành mảng byte nhị phân hoàn chỉnh gồm 11-byte header và payload.
     */
    fun serialize(frame: ChunkFrame): ByteArray {
        require(frame.payload.size <= ChunkFrame.MAX_PAYLOAD_SIZE) {
            "Payload length ${frame.payload.size} vượt quá giới hạn tối đa ${ChunkFrame.MAX_PAYLOAD_SIZE} bytes"
        }

        val totalSize = ChunkFrame.HEADER_SIZE + frame.payload.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(ChunkFrame.MAGIC_BYTE_0)
        buffer.put(ChunkFrame.MAGIC_BYTE_1)
        buffer.put(frame.msgType)
        buffer.putInt(frame.streamId)
        buffer.putInt(frame.payload.size)
        buffer.put(frame.payload)
        return buffer.array()
    }

    /**
     * Ghi trực tiếp một ChunkFrame vào luồng OutputStream.
     */
    fun serializeTo(frame: ChunkFrame, outputStream: OutputStream) {
        val bytes = serialize(frame)
        outputStream.write(bytes)
        outputStream.flush()
    }

    // --- Helper Factory Methods cho 8 loại thông điệp ---

    fun buildHandshakeAuthFrame(streamId: Int, payload: HandshakeAuthPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_HANDSHAKE_AUTH, streamId, payload.toBytes())
    }

    fun buildTransferManifestFrame(streamId: Int, payload: TransferManifestPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_TRANSFER_MANIFEST, streamId, payload.toBytes())
    }

    fun buildManifestResponseFrame(streamId: Int, payload: ManifestResponsePayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_MANIFEST_RESPONSE, streamId, payload.toBytes())
    }

    fun buildDataChunkFrame(streamId: Int, payload: DataChunkPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_DATA_CHUNK, streamId, payload.toBytes())
    }

    fun buildChunkAckFrame(streamId: Int, payload: ChunkAckPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_CHUNK_ACK, streamId, payload.toBytes())
    }

    fun buildControlSignalFrame(streamId: Int, payload: ControlSignalPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_CONTROL_SIGNAL, streamId, payload.toBytes())
    }

    fun buildRevokeSignalFrame(streamId: Int, payload: RevokeSignalPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_REVOKE_SIGNAL, streamId, payload.toBytes())
    }

    fun buildVerifyResultFrame(streamId: Int, payload: VerifyResultPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_VERIFY_RESULT, streamId, payload.toBytes())
    }
}

