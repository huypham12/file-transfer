package com.teamshare.app.core.storage.saf

import android.content.Context
import com.teamshare.app.core.constant.AppConstants
import java.io.File

/**
 * Quản lý phân cấp thư mục sandbox nội bộ an toàn trên Android (/data/user/0/com.teamshare/files/).
 * Theo đặc tả SDD mục 5.4 và AppConstants.
 */
class StorageDirectoryManager(private val context: Context) {

    val rootFilesDir: File get() = context.filesDir

    val transfersDir: File get() = File(rootFilesDir, AppConstants.StorageDirectory.TRANSFERS).apply { mkdirs() }

    val completedRootDir: File get() = File(transfersDir, AppConstants.StorageDirectory.COMPLETED).apply { mkdirs() }

    val logsDir: File get() = File(rootFilesDir, AppConstants.StorageDirectory.LOGS).apply { mkdirs() }

    val keystoreMetaDir: File get() = File(rootFilesDir, AppConstants.StorageDirectory.KEYSTORE_META).apply { mkdirs() }

    /**
     * Lấy thư mục chứa các tệp đã nhận hoàn chỉnh theo phiên.
     */
    fun getSessionCompletedDir(sessionId: String = ""): File {
        if (sessionId.isBlank()) return completedRootDir
        val sanitizedSessionId = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(completedRootDir, "${AppConstants.StorageDirectory.SESSION_DIR_PREFIX}$sanitizedSessionId").apply { mkdirs() }
    }

    /**
     * Lấy thư mục chứa các tệp đã tải hoàn chỉnh của một nhóm cụ thể (tương thích ngược).
     */
    fun getTeamCompletedDir(teamId: String): File {
        if (teamId.isBlank()) return completedRootDir
        val sanitizedTeamId = teamId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(completedRootDir, "${AppConstants.StorageDirectory.TEAM_DIR_PREFIX}$sanitizedTeamId").apply { mkdirs() }
    }

    /**
     * Tạo đường dẫn tệp đích hoàn chỉnh sau khi tải xong.
     */
    fun getCompletedFile(teamId: String = "", fileName: String): File {
        return File(getTeamCompletedDir(teamId), fileName)
    }

    /**
     * Lấy đường dẫn tệp tạm dở dang (.part) trong quá trình nhận dữ liệu stream.
     */
    fun getTempPartFile(sessionId: String, docId: String): File {
        val sanitizedSession = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val sanitizedDoc = docId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(transfersDir, "${AppConstants.StorageDirectory.PART_FILE_PREFIX}${sanitizedSession}_${sanitizedDoc}")
    }

    /**
     * Đảm bảo toàn bộ cấu trúc thư mục sandbox tồn tại.
     */
    fun ensureDirectoriesExist() {
        transfersDir
        completedRootDir
        logsDir
        keystoreMetaDir
    }
}
