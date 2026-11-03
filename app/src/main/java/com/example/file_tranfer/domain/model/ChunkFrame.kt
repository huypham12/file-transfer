package com.example.file_tranfer.domain.model

/**
 * Cấu trúc nhị phân của một khối dữ liệu (Chunk Frame) trên tầng Data Plane.
 */
data class ChunkFrame(
    val streamId: Int,
    val msgType: Byte = FrameType.DATA_CHUNK,
    val chunkIndex: Int,
    val offsetInFile: Long,
    val data: ByteArray,
    val length: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChunkFrame

        if (streamId != other.streamId) return false
        if (msgType != other.msgType) return false
        if (chunkIndex != other.chunkIndex) return false
        if (offsetInFile != other.offsetInFile) return false
        if (length != other.length) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = streamId
        result = 31 * result + msgType.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + offsetInFile.hashCode()
        result = 31 * result + length
        result = 31 * result + data.contentHashCode()
        return result
    }
}

