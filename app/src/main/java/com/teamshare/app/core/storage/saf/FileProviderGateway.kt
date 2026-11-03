package com.teamshare.app.core.storage.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Cổng giao tiếp cấp phát Content URI qua Android FileProvider.
 * Đảm bảo cấp quyền truy cập tối thiểu, có kiểm soát cho các ứng dụng xem ngoài (Viewer).
 */
class FileProviderGateway(
    private val context: Context,
    private val authority: String = DEFAULT_AUTHORITY
) {

    companion object {
        const val DEFAULT_AUTHORITY = "com.teamshare.fileprovider"
    }

    /**
     * Tạo Content URI an toàn trỏ tới tệp trong thư mục transfers/completed/.
     */
    fun getShareableUri(file: File): Uri {
        return FileProvider.getUriForFile(context, authority, file)
    }

    /**
     * Tạo Intent mở tệp với cờ cấp quyền đọc tạm thời FLAG_GRANT_READ_URI_PERMISSION.
     */
    fun createViewIntent(file: File, mimeType: String): Intent {
        val uri = getShareableUri(file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

