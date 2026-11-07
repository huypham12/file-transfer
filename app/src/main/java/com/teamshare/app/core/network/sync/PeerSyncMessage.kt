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
    val publisherPort: Int = 0,
    val folderId: String? = null
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
                    put("folderId", d.folderId ?: "")
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
                                publisherPort = d.optInt("publisherPort", 0),
                                folderId = d.optString("folderId").ifBlank { null }
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
    val publisherPort: Int = 0,
    val folderId: String? = null
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
            put("folderId", folderId ?: "")
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
                    publisherPort = obj.optInt("publisherPort", 0),
                    folderId = obj.optString("folderId").ifBlank { null }
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
    val preferredChannel: String = "RELAY",
    val targetPeerId: String? = null,
    val messageId: String = "",
    val requesterHost: String? = null,
    val requesterPort: Int = 0
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
            if (!targetPeerId.isNullOrBlank()) {
                put("targetPeerId", targetPeerId)
            }
            if (messageId.isNotBlank()) {
                put("messageId", messageId)
            }
            if (!requesterHost.isNullOrBlank()) {
                put("requesterHost", requesterHost)
            }
            if (requesterPort > 0) {
                put("requesterPort", requesterPort)
            }
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
                    preferredChannel = obj.optString("preferredChannel", "RELAY"),
                    targetPeerId = obj.optString("targetPeerId").ifBlank { null },
                    messageId = obj.optString("messageId"),
                    requesterHost = obj.optString("requesterHost").ifBlank { null },
                    requesterPort = obj.optInt("requesterPort", 0)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Bản tin nhịp tim định kỳ phát hiện sự hiện diện của các peer trong phòng đồng bộ.
 */
data class PeerHeartbeatMessage(
    val teamId: String,
    val memberId: String,
    val memberName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val clientHost: String? = null,
    val clientPort: Int = 0
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            put("memberId", memberId)
            put("memberName", memberName)
            put("timestamp", timestamp)
            if (!clientHost.isNullOrBlank()) {
                put("clientHost", clientHost)
            }
            if (clientPort > 0) {
                put("clientPort", clientPort)
            }
        }.toString()
    }

    companion object {
        const val ACTION = "PEER_HEARTBEAT"

        fun fromJson(jsonStr: String): PeerHeartbeatMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                PeerHeartbeatMessage(
                    teamId = obj.getString("teamId"),
                    memberId = obj.getString("memberId"),
                    memberName = obj.optString("memberName"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    clientHost = obj.optString("clientHost").takeIf { it.isNotBlank() },
                    clientPort = obj.optInt("clientPort", 0)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Bản tin thăm dò (Pre-flight Probe) trước khi mở phiên truyền tải thực tế.
 */
data class FileProbeRequestMessage(
    val teamId: String,
    val docId: String,
    val streamId: String,
    val requesterId: String,
    val targetPeerId: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            put("docId", docId)
            put("streamId", streamId)
            put("requesterId", requesterId)
            put("targetPeerId", targetPeerId)
        }.toString()
    }

    companion object {
        const val ACTION = "FILE_PROBE_REQUEST"

        fun fromJson(jsonStr: String): FileProbeRequestMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                FileProbeRequestMessage(
                    teamId = obj.getString("teamId"),
                    docId = obj.getString("docId"),
                    streamId = obj.getString("streamId"),
                    requesterId = obj.getString("requesterId"),
                    targetPeerId = obj.getString("targetPeerId")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Phản hồi thăm dò tính khả dụng của tệp nguồn từ Sender tới Receiver.
 */
data class FileProbeResponseMessage(
    val teamId: String,
    val docId: String,
    val streamId: String,
    val status: String, // "READY", "FILE_MISSING", "BUSY"
    val message: String = "",
    val targetPeerId: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            put("docId", docId)
            put("streamId", streamId)
            put("status", status)
            put("message", message)
            put("targetPeerId", targetPeerId)
        }.toString()
    }

    companion object {
        const val ACTION = "FILE_PROBE_RESPONSE"

        fun fromJson(jsonStr: String): FileProbeResponseMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                FileProbeResponseMessage(
                    teamId = obj.getString("teamId"),
                    docId = obj.getString("docId"),
                    streamId = obj.getString("streamId"),
                    status = obj.getString("status"),
                    message = obj.optString("message"),
                    targetPeerId = obj.getString("targetPeerId")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Bản tin xác nhận đã nhận yêu cầu truyền tệp (2-Phase ACK).
 */
data class FileRequestAckMessage(
    val teamId: String,
    val streamId: String,
    val messageId: String = "",
    val status: String = "ACCEPTED", // "ACCEPTED", "REJECTED"
    val targetPeerId: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            put("streamId", streamId)
            put("messageId", messageId)
            put("status", status)
            put("targetPeerId", targetPeerId)
        }.toString()
    }

    companion object {
        const val ACTION = "FILE_REQUEST_ACK"

        fun fromJson(jsonStr: String): FileRequestAckMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                FileRequestAckMessage(
                    teamId = obj.getString("teamId"),
                    streamId = obj.getString("streamId"),
                    messageId = obj.optString("messageId"),
                    status = obj.optString("status", "ACCEPTED"),
                    targetPeerId = obj.getString("targetPeerId")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Bản tin thông báo thu hồi quyền thành viên có chữ ký số của Trưởng nhóm (Signed CRL).
 */
data class MemberRevokedMessage(
    val teamId: String,
    val recordId: String,
    val revokedMemberId: String,
    val revokedFingerprint: String,
    val revokedAt: Long = System.currentTimeMillis(),
    val reason: String = "REMOVED_BY_OWNER",
    val signature: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            put("recordId", recordId)
            put("revokedMemberId", revokedMemberId)
            put("revokedFingerprint", revokedFingerprint)
            put("revokedAt", revokedAt)
            put("reason", reason)
            put("signature", signature)
        }.toString()
    }

    companion object {
        const val ACTION = "MEMBER_REVOKED"

        fun fromJson(jsonStr: String): MemberRevokedMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                MemberRevokedMessage(
                    teamId = obj.getString("teamId"),
                    recordId = obj.getString("recordId"),
                    revokedMemberId = obj.getString("revokedMemberId"),
                    revokedFingerprint = obj.optString("revokedFingerprint"),
                    revokedAt = obj.optLong("revokedAt", System.currentTimeMillis()),
                    reason = obj.optString("reason", "REMOVED_BY_OWNER"),
                    signature = obj.getString("signature")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Bản tin thông báo giải tán nhóm có chữ ký số của Trưởng nhóm (Tombstone).
 */
data class TeamDisbandedMessage(
    val teamId: String,
    val disbandedAt: Long = System.currentTimeMillis(),
    val signature: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            put("disbandedAt", disbandedAt)
            put("signature", signature)
        }.toString()
    }

    companion object {
        const val ACTION = "TEAM_DISBANDED"

        fun fromJson(jsonStr: String): TeamDisbandedMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                TeamDisbandedMessage(
                    teamId = obj.getString("teamId"),
                    disbandedAt = obj.optLong("disbandedAt", System.currentTimeMillis()),
                    signature = obj.getString("signature")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Truy vấn danh sách thu hồi quyền (CRL Sync Query) khi peer mới online lại.
 */
data class RevocationSyncQueryMessage(
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
        const val ACTION = "REVOCATION_SYNC_QUERY"

        fun fromJson(jsonStr: String): RevocationSyncQueryMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                RevocationSyncQueryMessage(
                    teamId = obj.getString("teamId"),
                    requesterId = obj.optString("requesterId")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class RevocationSyncDto(
    val recordId: String,
    val teamId: String,
    val revokedMemberId: String,
    val revokedFingerprint: String,
    val revocationType: String,
    val revokedAt: Long,
    val reason: String,
    val ownerSignature: String
)

data class RevocationSyncResponseMessage(
    val teamId: String,
    val revocations: List<RevocationSyncDto>
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            val arr = JSONArray()
            revocations.forEach { r ->
                arr.put(JSONObject().apply {
                    put("recordId", r.recordId)
                    put("teamId", r.teamId)
                    put("revokedMemberId", r.revokedMemberId)
                    put("revokedFingerprint", r.revokedFingerprint)
                    put("revocationType", r.revocationType)
                    put("revokedAt", r.revokedAt)
                    put("reason", r.reason)
                    put("ownerSignature", r.ownerSignature)
                })
            }
            put("revocations", arr)
        }.toString()
    }

    companion object {
        const val ACTION = "REVOCATION_SYNC_RESPONSE"

        fun fromJson(jsonStr: String): RevocationSyncResponseMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                val list = mutableListOf<RevocationSyncDto>()
                val arr = obj.optJSONArray("revocations")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        list.add(
                            RevocationSyncDto(
                                recordId = item.getString("recordId"),
                                teamId = item.getString("teamId"),
                                revokedMemberId = item.getString("revokedMemberId"),
                                revokedFingerprint = item.optString("revokedFingerprint"),
                                revocationType = item.optString("revocationType", "MEMBER_KICK"),
                                revokedAt = item.getLong("revokedAt"),
                                reason = item.optString("reason"),
                                ownerSignature = item.getString("ownerSignature")
                            )
                        )
                    }
                }
                RevocationSyncResponseMessage(
                    teamId = obj.getString("teamId"),
                    revocations = list
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

// -------------------------------------------------------------
// FOLDER VIRTUAL DIRECTORY MESSAGES (Phase 3)
// -------------------------------------------------------------

data class FolderSyncDto(
    val folderId: String,
    val teamId: String,
    val parentFolderId: String?,
    val folderName: String,
    val createdBy: String,
    val createdAt: Long,
    val isDeleted: Boolean = false
)

data class FolderAnnounceMessage(
    val folderId: String,
    val teamId: String,
    val parentFolderId: String?,
    val folderName: String,
    val createdBy: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("folderId", folderId)
            put("teamId", teamId)
            put("parentFolderId", parentFolderId ?: "")
            put("folderName", folderName)
            put("createdBy", createdBy)
            put("createdAt", createdAt)
        }.toString()
    }

    companion object {
        const val ACTION = "FOLDER_ANNOUNCE"

        fun fromJson(jsonStr: String): FolderAnnounceMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                FolderAnnounceMessage(
                    folderId = obj.getString("folderId"),
                    teamId = obj.getString("teamId"),
                    parentFolderId = obj.optString("parentFolderId").ifBlank { null },
                    folderName = obj.getString("folderName"),
                    createdBy = obj.getString("createdBy"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class FolderDeleteMessage(
    val folderId: String,
    val teamId: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("folderId", folderId)
            put("teamId", teamId)
        }.toString()
    }

    companion object {
        const val ACTION = "FOLDER_DELETE"

        fun fromJson(jsonStr: String): FolderDeleteMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                FolderDeleteMessage(
                    folderId = obj.getString("folderId"),
                    teamId = obj.getString("teamId")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class FolderSyncQueryMessage(
    val teamId: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
        }.toString()
    }

    companion object {
        const val ACTION = "FOLDER_SYNC_QUERY"

        fun fromJson(jsonStr: String): FolderSyncQueryMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                FolderSyncQueryMessage(teamId = obj.getString("teamId"))
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class FolderSyncResponseMessage(
    val teamId: String,
    val folders: List<FolderSyncDto>
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            val arr = JSONArray()
            folders.forEach { f ->
                arr.put(JSONObject().apply {
                    put("folderId", f.folderId)
                    put("teamId", f.teamId)
                    put("parentFolderId", f.parentFolderId ?: "")
                    put("folderName", f.folderName)
                    put("createdBy", f.createdBy)
                    put("createdAt", f.createdAt)
                    put("isDeleted", f.isDeleted)
                })
            }
            put("folders", arr)
        }.toString()
    }

    companion object {
        const val ACTION = "FOLDER_SYNC_RESPONSE"

        fun fromJson(jsonStr: String): FolderSyncResponseMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                val list = mutableListOf<FolderSyncDto>()
                val arr = obj.optJSONArray("folders")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        list.add(
                            FolderSyncDto(
                                folderId = item.getString("folderId"),
                                teamId = item.getString("teamId"),
                                parentFolderId = item.optString("parentFolderId").ifBlank { null },
                                folderName = item.getString("folderName"),
                                createdBy = item.getString("createdBy"),
                                createdAt = item.getLong("createdAt"),
                                isDeleted = item.optBoolean("isDeleted", false)
                            )
                        )
                    }
                }
                FolderSyncResponseMessage(
                    teamId = obj.getString("teamId"),
                    folders = list
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

// -------------------------------------------------------------
// AUDIT LOG SYNC MESSAGES (Phase 4: Incremental Gossip Sync)
// -------------------------------------------------------------

data class AuditLogSyncDto(
    val logId: Long = 0L,
    val teamId: String,
    val eventType: String,
    val severity: String,
    val actorId: String,
    val actorName: String,
    val description: String,
    val timestamp: Long,
    val signature: String
)

data class AuditLogAnnounceMessage(
    val teamId: String,
    val log: AuditLogSyncDto
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            put("log", JSONObject().apply {
                put("logId", log.logId)
                put("teamId", log.teamId)
                put("eventType", log.eventType)
                put("severity", log.severity)
                put("actorId", log.actorId)
                put("actorName", log.actorName)
                put("description", log.description)
                put("timestamp", log.timestamp)
                put("signature", log.signature)
            })
        }.toString()
    }

    companion object {
        const val ACTION = "AUDIT_LOG_ANNOUNCE"

        fun fromJson(jsonStr: String): AuditLogAnnounceMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                val logObj = obj.getJSONObject("log")
                AuditLogAnnounceMessage(
                    teamId = obj.getString("teamId"),
                    log = AuditLogSyncDto(
                        logId = logObj.optLong("logId", 0L),
                        teamId = logObj.getString("teamId"),
                        eventType = logObj.getString("eventType"),
                        severity = logObj.getString("severity"),
                        actorId = logObj.getString("actorId"),
                        actorName = logObj.optString("actorName"),
                        description = logObj.getString("description"),
                        timestamp = logObj.getLong("timestamp"),
                        signature = logObj.optString("signature")
                    )
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class AuditLogSyncQueryMessage(
    val teamId: String,
    val sinceTimestamp: Long
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            put("sinceTimestamp", sinceTimestamp)
        }.toString()
    }

    companion object {
        const val ACTION = "AUDIT_LOG_SYNC_QUERY"

        fun fromJson(jsonStr: String): AuditLogSyncQueryMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                AuditLogSyncQueryMessage(
                    teamId = obj.getString("teamId"),
                    sinceTimestamp = obj.getLong("sinceTimestamp")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class AuditLogSyncResponseMessage(
    val teamId: String,
    val logs: List<AuditLogSyncDto>
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("action", ACTION)
            put("teamId", teamId)
            val arr = JSONArray()
            logs.forEach { l ->
                arr.put(JSONObject().apply {
                    put("logId", l.logId)
                    put("teamId", l.teamId)
                    put("eventType", l.eventType)
                    put("severity", l.severity)
                    put("actorId", l.actorId)
                    put("actorName", l.actorName)
                    put("description", l.description)
                    put("timestamp", l.timestamp)
                    put("signature", l.signature)
                })
            }
            put("logs", arr)
        }.toString()
    }

    companion object {
        const val ACTION = "AUDIT_LOG_SYNC_RESPONSE"

        fun fromJson(jsonStr: String): AuditLogSyncResponseMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("action") != ACTION) return null
                val list = mutableListOf<AuditLogSyncDto>()
                val arr = obj.optJSONArray("logs")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        list.add(
                            AuditLogSyncDto(
                                logId = item.optLong("logId", 0L),
                                teamId = item.getString("teamId"),
                                eventType = item.getString("eventType"),
                                severity = item.getString("severity"),
                                actorId = item.getString("actorId"),
                                actorName = item.optString("actorName"),
                                description = item.getString("description"),
                                timestamp = item.getLong("timestamp"),
                                signature = item.optString("signature")
                            )
                        )
                    }
                }
                AuditLogSyncResponseMessage(
                    teamId = obj.getString("teamId"),
                    logs = list
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}


