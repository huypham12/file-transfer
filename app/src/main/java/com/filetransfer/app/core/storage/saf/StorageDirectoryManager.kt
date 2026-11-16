package com.filetransfer.app.core.storage.saf

import android.content.Context
import com.filetransfer.app.core.constant.AppConstants
import java.io.File

/**
 * Quản lý phân cấp thư mục sandbox nội bộ an toàn trên Android (/data/user/0/com.filetransfer/files/).
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
     * Lấy đường dẫn tệp tạm dở dang (.part) trong quá trình nhận dữ liệu stream.
     */
    fun getTempPartFile(sessionId: String, fileName: String = ""): File {
        val sanitizedSession = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val suffix = if (fileName.isNotBlank()) "_${fileName.replace(Regex("[^a-zA-Z0-9_.-]"), "_")}" else ""
        return File(transfersDir, "${AppConstants.StorageDirectory.PART_FILE_PREFIX}${sanitizedSession}${suffix}")
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
