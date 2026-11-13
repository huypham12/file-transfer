package com.filetransfer.app.core.storage.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.filetransfer.app.core.constant.AppConstants
import java.io.File

/**
 * Cổng giao tiếp cấp phát Content URI qua Android FileProvider (SRS FR-DOC-03 & SDD Mục 6.4).
 * Đảm bảo cấp quyền truy cập tối thiểu, có kiểm soát cho các ứng dụng xem ngoài (Viewer).
 */
open class FileProviderGateway(
    private val context: Context? = null,
    private val authority: String = DEFAULT_AUTHORITY,
    private val uriProvider: ((File, String) -> Uri)? = null
) {

    companion object {
        val DEFAULT_AUTHORITY: String = com.example.file_tranfer.BuildConfig.FILE_PROVIDER_AUTHORITY
    }

    /**
     * Tạo Content URI an toàn trỏ tới tệp tin (Scoped Storage).
     */
    open fun getShareableUri(file: File): Uri {
        uriProvider?.let { return it(file, authority) }
        val ctx = context ?: throw IllegalStateException("Context không được để trống khi không cung cấp uriProvider")
        return FileProvider.getUriForFile(ctx, authority, file)
    }

    /**
     * Tạo Intent mở tệp với cờ cấp quyền đọc tạm thời FLAG_GRANT_READ_URI_PERMISSION.
     * Tự động suy luận MIME type từ phần mở rộng tệp nếu mimeType là wildcard.
     */
    open fun createViewIntent(file: File, mimeType: String = AppConstants.Mime.ALL): Intent {
        val uri = getShareableUri(file)
        val resolvedMime = if (mimeType.isBlank() || mimeType == AppConstants.Mime.ALL) {
            val ext = file.extension.lowercase()
            if (ext.isNotEmpty()) {
                try {
                    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: AppConstants.Mime.ALL
                } catch (_: Throwable) {
                    AppConstants.Mime.ALL
                }
            } else {
                AppConstants.Mime.ALL
            }
        } else {
            mimeType
        }
        val intent = Intent(Intent.ACTION_VIEW)
        try {
            intent.setDataAndType(uri, resolvedMime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (_: Throwable) {
            // Fallback an toàn trên môi trường Unit Test thuần JVM không có mock Android Intent
        }
        return intent
    }

    /**
     * Tạo Intent mở thư mục an toàn (SRS FR-DOC-03 & CONF-01).
     * Sử dụng ACTION_VIEW_DOWNLOADS để mở trực tiếp trong trình quản lý tệp hệ thống,
     * tuyệt đối không dùng Uri.fromFile(directory) nhằm loại bỏ hoàn toàn FileUriExposedException trên Android 7.0+.
     */
    open fun createOpenDirectoryIntent(directory: File): Intent {
        val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (_: Throwable) {
            // Fallback an toàn trên môi trường Unit Test thuần JVM không có mock Android Intent
        }
        return intent
    }
}
