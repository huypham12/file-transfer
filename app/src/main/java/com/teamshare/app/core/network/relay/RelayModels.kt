package com.teamshare.app.core.network.relay

import org.json.JSONObject

/**
 * Bản tin yêu cầu gắn kết nối Client vào Relay ChannelBridge (SDD mục 6.3).
 */
data class AttachStreamRequest(
    val action: String = "ATTACH_STREAM",
    val streamId: String,
    val role: String, // "SENDER" hoặc "RECEIVER"
    val authTicket: String? = null
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("action", action)
        json.put("streamId", streamId)
        json.put("role", role)
        authTicket?.let { json.put("authTicket", it) }
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): AttachStreamRequest {
            val json = JSONObject(jsonStr)
            return AttachStreamRequest(
                action = json.optString("action", "ATTACH_STREAM"),
                streamId = json.getString("streamId"),
                role = json.getString("role"),
                authTicket = if (json.has("authTicket")) json.getString("authTicket") else null
            )
        }
    }
}

/**
 * Bản tin phản hồi từ Relay Server sau khi xử lý gắn kênh.
 */
data class AttachStreamResponse(
    val status: String, // "ATTACHED" hoặc "ERROR"
    val streamId: String? = null,
    val role: String? = null,
    val message: String? = null
) {
    val isSuccess: Boolean get() = status == "ATTACHED"

    fun toJson(): String {
        val json = JSONObject()
        json.put("status", status)
        streamId?.let { json.put("streamId", it) }
        role?.let { json.put("role", it) }
        message?.let { json.put("message", it) }
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): AttachStreamResponse {
            val json = JSONObject(jsonStr)
            return AttachStreamResponse(
                status = json.getString("status"),
                streamId = if (json.has("streamId")) json.getString("streamId") else null,
                role = if (json.has("role")) json.getString("role") else null,
                message = if (json.has("message")) json.getString("message") else null
            )
        }
    }
}
