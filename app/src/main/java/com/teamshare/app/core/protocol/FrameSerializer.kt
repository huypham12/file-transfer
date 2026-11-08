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

    // =====================================================================================
    // Helper Factory Methods cho 8 loại thông điệp chuẩn SDD Mục 6.2
    // =====================================================================================

    fun buildConnectRequestFrame(streamId: Int, payload: ConnectRequestPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_CONNECT_REQUEST, streamId, payload.toBytes())
    }

    fun buildConnectAcceptedFrame(streamId: Int, payload: ConnectAcceptedPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_CONNECT_ACCEPTED, streamId, payload.toBytes())
    }

    fun buildRequestStreamFrame(streamId: Int, payload: RequestStreamPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_REQUEST_STREAM, streamId, payload.toBytes())
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

    fun buildVerifyResultFrame(streamId: Int, payload: VerifyResultPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_VERIFY_RESULT, streamId, payload.toBytes())
    }

    fun buildErrorAlertFrame(streamId: Int, payload: ErrorAlertPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_ERROR_ALERT, streamId, payload.toBytes())
    }

    // =====================================================================================
    // Helper Factory Methods Deprecated hỗ trợ tương thích ngược
    // =====================================================================================

    @Deprecated("Sử dụng buildConnectRequestFrame thay thế", ReplaceWith("buildConnectRequestFrame(streamId, payload)"))
    fun buildHandshakeAuthFrame(streamId: Int, payload: HandshakeAuthPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_CONNECT_REQUEST, streamId, payload.toBytes())
    }

    @Deprecated("Sử dụng buildConnectAcceptedFrame thay thế", ReplaceWith("buildConnectAcceptedFrame(streamId, payload)"))
    fun buildTransferManifestFrame(streamId: Int, payload: TransferManifestPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_CONNECT_ACCEPTED, streamId, payload.toBytes())
    }

    @Deprecated("Sử dụng buildRequestStreamFrame thay thế", ReplaceWith("buildRequestStreamFrame(streamId, payload)"))
    fun buildManifestResponseFrame(streamId: Int, payload: ManifestResponsePayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_REQUEST_STREAM, streamId, payload.toBytes())
    }

    @Deprecated("Đã loại bỏ trong giao thức mới")
    fun buildRevokeSignalFrame(streamId: Int, payload: RevokeSignalPayload): ChunkFrame {
        return ChunkFrame(ChunkFrame.TYPE_CONTROL_SIGNAL, streamId, payload.toBytes())
    }
}
