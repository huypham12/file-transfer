package com.teamshare.app.core.network.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Các cấu trúc bản tin điều khiển và đồng bộ trạng thái P2P giữa các thiết bị thành viên.
 */

data class JoinRequestMessage(
    val teamId: String,
    val invitationToken: String,
    val memberId: String,
    val memberName: String,
    val deviceFingerprint: String,
    val memberPublicKey: String,
    val signature: String,
    val clientHost: String? = null,
    val clientPort: Int = 0
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            put("invitationToken", invitationToken)
            put("memberId", memberId)
            put("memberName", memberName)
            put("deviceFingerprint", deviceFingerprint)
            put("memberPublicKey", memberPublicKey)
            put("signature", signature)
            put("clientHost", clientHost ?: "")
            put("clientPort", clientPort)
        }.toString()
    }

    companion object {
        const val ACTION = "JOIN_REQUEST"

        fun fromJson(jsonStr: String): JoinRequestMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                JoinRequestMessage(
                    teamId = obj.getString("teamId"),
                    invitationToken = obj.optString("invitationToken"),
                    memberId = obj.getString("memberId"),
                    memberName = obj.getString("memberName"),
                    deviceFingerprint = obj.getString("deviceFingerprint"),
                    memberPublicKey = obj.optString("memberPublicKey"),
                    signature = obj.optString("signature"),
                    clientHost = obj.optString("clientHost").ifBlank { null },
                    clientPort = obj.optInt("clientPort", 0)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class MemberSyncDto(
    val memberId: String,
    val memberName: String,
    val deviceFingerprint: String,
    val role: String,
    val credentialData: String = ""
)

data class DocumentSyncDto(
    val docId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val expectedSha256: String,
    val publisherId: String,
    val requireBiometric: Boolean = false,
    val publisherHost: String? = null,
    val publisherPort: Int = 0
)

data class JoinResponseMessage(
    val status: String, // "OK" hoặc "ERROR"
    val message: String = "",
    val members: List<MemberSyncDto> = emptyList(),
    val documents: List<DocumentSyncDto> = emptyList()
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("status", status)
            put("message", message)

            val memberArray = JSONArray()
            members.forEach { m ->
                memberArray.put(JSONObject().apply {
                    put("memberId", m.memberId)
                    put("memberName", m.memberName)
                    put("deviceFingerprint", m.deviceFingerprint)
                    put("role", m.role)
                    put("credentialData", m.credentialData)
                })
            }
            put("members", memberArray)

            val docArray = JSONArray()
            documents.forEach { d ->
                docArray.put(JSONObject().apply {
                    put("docId", d.docId)
                    put("fileName", d.fileName)
                    put("fileSize", d.fileSize)
                    put("mimeType", d.mimeType)
                    put("expectedSha256", d.expectedSha256)
                    put("publisherId", d.publisherId)
                    put("requireBiometric", d.requireBiometric)
                    put("publisherHost", d.publisherHost ?: "")
                    put("publisherPort", d.publisherPort)
                })
            }
            put("documents", docArray)
        }.toString()
    }

    companion object {
        const val ACTION = "JOIN_RESPONSE"

        fun fromJson(jsonStr: String): JoinResponseMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null

                val memberList = mutableListOf<MemberSyncDto>()
                val memberArray = obj.optJSONArray("members")
                if (memberArray != null) {
                    for (i in 0 until memberArray.length()) {
                        val m = memberArray.getJSONObject(i)
                        memberList.add(
                            MemberSyncDto(
                                memberId = m.getString("memberId"),
                                memberName = m.getString("memberName"),
                                deviceFingerprint = m.getString("deviceFingerprint"),
                                role = m.optString("role", "MEMBER"),
                                credentialData = m.optString("credentialData")
                            )
                        )
                    }
                }

                val docList = mutableListOf<DocumentSyncDto>()
                val docArray = obj.optJSONArray("documents")
                if (docArray != null) {
                    for (i in 0 until docArray.length()) {
                        val d = docArray.getJSONObject(i)
                        docList.add(
                            DocumentSyncDto(
                                docId = d.getString("docId"),
                                fileName = d.getString("fileName"),
                                fileSize = d.getLong("fileSize"),
                                mimeType = d.optString("mimeType", "application/octet-stream"),
                                expectedSha256 = d.getString("expectedSha256"),
                                publisherId = d.getString("publisherId"),
                                requireBiometric = d.optBoolean("requireBiometric", false),
                                publisherHost = d.optString("publisherHost").ifBlank { null },
                                publisherPort = d.optInt("publisherPort", 0)
                            )
                        )
                    }
                }

                JoinResponseMessage(
                    status = obj.getString("status"),
                    message = obj.optString("message"),
                    members = memberList,
                    documents = docList
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class CatalogAnnounceMessage(
    val teamId: String,
    val docId: String,
    val publisherId: String,
    val publisherName: String = "",
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val expectedSha256: String,
    val requireBiometric: Boolean = false,
    val publisherHost: String? = null,
    val publisherPort: Int = 0
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            put("docId", docId)
            put("publisherId", publisherId)
            put("publisherName", publisherName)
            put("fileName", fileName)
            put("fileSize", fileSize)
            put("mimeType", mimeType)
            put("expectedSha256", expectedSha256)
            put("requireBiometric", requireBiometric)
            put("publisherHost", publisherHost ?: "")
            put("publisherPort", publisherPort)
        }.toString()
    }

    companion object {
        const val ACTION = "CATALOG_ANNOUNCE"

        fun fromJson(jsonStr: String): CatalogAnnounceMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                CatalogAnnounceMessage(
                    teamId = obj.getString("teamId"),
                    docId = obj.getString("docId"),
                    publisherId = obj.getString("publisherId"),
                    publisherName = obj.optString("publisherName"),
                    fileName = obj.getString("fileName"),
                    fileSize = obj.getLong("fileSize"),
                    mimeType = obj.optString("mimeType", "application/octet-stream"),
                    expectedSha256 = obj.getString("expectedSha256"),
                    requireBiometric = obj.optBoolean("requireBiometric", false),
                    publisherHost = obj.optString("publisherHost").ifBlank { null },
                    publisherPort = obj.optInt("publisherPort", 0)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class CatalogQueryMessage(
    val teamId: String,
    val requesterId: String = ""
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            put("requesterId", requesterId)
        }.toString()
    }

    companion object {
        const val ACTION = "CATALOG_QUERY"

        fun fromJson(jsonStr: String): CatalogQueryMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                CatalogQueryMessage(
                    teamId = obj.getString("teamId"),
                    requesterId = obj.optString("requesterId")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class CatalogSyncResponseMessage(
    val teamId: String,
    val documents: List<DocumentSyncDto>,
    val members: List<MemberSyncDto> = emptyList()
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            val docArray = JSONArray()
            documents.forEach { d ->
                docArray.put(JSONObject().apply {
                    put("docId", d.docId)
                    put("fileName", d.fileName)
                    put("fileSize", d.fileSize)
                    put("mimeType", d.mimeType)
                    put("expectedSha256", d.expectedSha256)
                    put("publisherId", d.publisherId)
                    put("requireBiometric", d.requireBiometric)
                    put("publisherHost", d.publisherHost ?: "")
                    put("publisherPort", d.publisherPort)
                })
            }
            put("documents", docArray)
            val memberArray = JSONArray()
            members.forEach { m ->
                memberArray.put(JSONObject().apply {
                    put("memberId", m.memberId)
                    put("memberName", m.memberName)
                    put("deviceFingerprint", m.deviceFingerprint)
                    put("role", m.role)
                    put("credentialData", m.credentialData)
                })
            }
            put("members", memberArray)
        }.toString()
    }

    companion object {
        const val ACTION = "CATALOG_SYNC_RESPONSE"

        fun fromJson(jsonStr: String): CatalogSyncResponseMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                val docList = mutableListOf<DocumentSyncDto>()
                val docArray = obj.optJSONArray("documents")
                if (docArray != null) {
                    for (i in 0 until docArray.length()) {
                        val d = docArray.getJSONObject(i)
                        docList.add(
                            DocumentSyncDto(
                                docId = d.getString("docId"),
                                fileName = d.getString("fileName"),
                                fileSize = d.getLong("fileSize"),
                                mimeType = d.optString("mimeType", "application/octet-stream"),
                                expectedSha256 = d.getString("expectedSha256"),
                                publisherId = d.getString("publisherId"),
                                requireBiometric = d.optBoolean("requireBiometric", false),
                                publisherHost = d.optString("publisherHost").ifBlank { null },
                                publisherPort = d.optInt("publisherPort", 0)
                            )
                        )
                    }
                }
                val memberList = mutableListOf<MemberSyncDto>()
                val memberArray = obj.optJSONArray("members")
                if (memberArray != null) {
                    for (i in 0 until memberArray.length()) {
                        val m = memberArray.getJSONObject(i)
                        memberList.add(
                            MemberSyncDto(
                                memberId = m.getString("memberId"),
                                memberName = m.getString("memberName"),
                                deviceFingerprint = m.getString("deviceFingerprint"),
                                role = m.optString("role", "MEMBER"),
                                credentialData = m.optString("credentialData", "")
                            )
                        )
                    }
                }
                CatalogSyncResponseMessage(
                    teamId = obj.getString("teamId"),
                    documents = docList,
                    members = memberList
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class FileRequestMessage(
    val teamId: String,
    val docId: String,
    val requesterId: String,
    val requesterName: String,
    val streamId: String,
    val preferredChannel: String = "RELAY"
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            put("docId", docId)
            put("requesterId", requesterId)
            put("requesterName", requesterName)
            put("streamId", streamId)
            put("preferredChannel", preferredChannel)
        }.toString()
    }

    companion object {
        const val ACTION = "FILE_REQUEST"

        fun fromJson(jsonStr: String): FileRequestMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                FileRequestMessage(
                    teamId = obj.getString("teamId"),
                    docId = obj.getString("docId"),
                    requesterId = obj.getString("requesterId"),
                    requesterName = obj.optString("requesterName"),
                    streamId = obj.getString("streamId"),
                    preferredChannel = obj.optString("preferredChannel", "RELAY")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

