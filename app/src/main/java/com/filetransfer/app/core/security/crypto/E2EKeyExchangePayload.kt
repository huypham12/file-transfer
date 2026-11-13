package com.filetransfer.app.core.security.crypto

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Tải trọng trao đổi khóa mã hóa đầu cuối E2EE (Ephemeral ECDH Key Exchange Payload).
 * Được hai bên Sender và Receiver trao đổi qua Relay Server để thiết lập khóa phiên.
 */
data class E2EKeyExchangePayload(
    val streamId: String,
    val ephemeralPublicKeyBytes: ByteArray,
    val clientNonce: ByteArray,
    val timestamp: Long
) {
    /**
     * Tuần tự hóa đối tượng thành chuỗi JSON.
     */
    fun toJson(): String {
        val json = JSONObject()
        json.put("streamId", streamId)
        json.put("publicKeyHex", ephemeralPublicKeyBytes.joinToString("") { "%02x".format(it) })
        json.put("nonceHex", clientNonce.joinToString("") { "%02x".format(it) })
        json.put("timestamp", timestamp)
        return json.toString()
    }

    /**
     * Tuần tự hóa sang mảng byte nhị phân UTF-8.
     */
    fun toByteArray(): ByteArray {
        return toJson().toByteArray(StandardCharsets.UTF_8)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as E2EKeyExchangePayload

        if (streamId != other.streamId) return false
        if (!ephemeralPublicKeyBytes.contentEquals(other.ephemeralPublicKeyBytes)) return false
        if (!clientNonce.contentEquals(other.clientNonce)) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = streamId.hashCode()
        result = 31 * result + ephemeralPublicKeyBytes.contentHashCode()
        result = 31 * result + clientNonce.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }

    companion object {
        /**
         * Giải tuần tự hóa từ chuỗi JSON.
         */
        fun fromJson(jsonStr: String): E2EKeyExchangePayload {
            val json = JSONObject(jsonStr)
            val streamId = json.getString("streamId")
            val pubKeyHex = json.getString("publicKeyHex")
            val nonceHex = json.getString("nonceHex")
            val timestamp = json.getLong("timestamp")

            return E2EKeyExchangePayload(
                streamId = streamId,
                ephemeralPublicKeyBytes = hexToBytes(pubKeyHex),
                clientNonce = hexToBytes(nonceHex),
                timestamp = timestamp
            )
        }

        /**
         * Giải tuần tự hóa từ mảng byte UTF-8.
         */
        fun fromByteArray(bytes: ByteArray): E2EKeyExchangePayload {
            return fromJson(String(bytes, StandardCharsets.UTF_8))
        }

        private fun hexToBytes(hex: String): ByteArray {
            val len = hex.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                        Character.digit(hex[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }
    }
}

