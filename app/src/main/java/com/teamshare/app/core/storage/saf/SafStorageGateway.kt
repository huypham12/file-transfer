package com.teamshare.app.core.storage.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.teamshare.app.core.security.sanitizer.FilenameSanitizer
import java.io.InputStream

/**
 * Siêu dữ liệu của tệp tin được truy xuất thông qua Storage Access Framework (SAF).
 */
data class SafFileMetadata(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val uri: Uri
)

/**
 * Cổng giao tiếp với khung lưu trữ Android Scoped Storage (Storage Access Framework - SAF).
 */
class SafStorageGateway(private val context: Context) {

    /**
     * Mở luồng đọc InputStream từ Content URI an toàn.
     */
    fun openInputStream(uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Duy trì quyền truy cập đọc lâu dài qua các lần khởi động lại máy.
     */
    fun takePersistableUriPermission(uri: Uri, flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            // Không phải URI nào cũng hỗ trợ persistable permission (ví dụ URI tạm)
        }
    }

    /**
     * Trích xuất siêu dữ liệu tên tệp, kích thước và MIME type từ SAF Uri.
     * Tự động làm sạch tên tệp qua FilenameSanitizer để chống tấn công Path Traversal.
     */
    fun queryMetadata(uri: Uri): SafFileMetadata? {
        var rawName: String? = null
        var size: Long? = null
        var mimeType: String? = null
        var querySucceeded = false

        try {
            mimeType = context.contentResolver.getType(uri)
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    querySucceeded = true
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) {
                            rawName = name
                        }
                    }

                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            // Nếu query cursor lỗi, fallback ước lượng từ descriptor
        }

        if (size == null || size <= 0L) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val statSize = pfd.statSize
                    if (statSize > 0L) {
                        size = statSize
                        querySucceeded = true
                    }
                }
            } catch (ignored: Exception) {
            }
        }

        // Kiểm tra khả năng mở stream nếu query/pfd chưa xác nhận
        var streamOpenable = false
        if (!querySucceeded) {
            try {
                context.contentResolver.openInputStream(uri)?.use {
                    streamOpenable = true
                }
            } catch (ignored: Exception) {
            }
        }

        // Nếu hoàn toàn không thể truy cập URI qua bất kỳ cơ chế nào
        if (!querySucceeded && !streamOpenable) {
            return null
        }

        val finalName = rawName ?: "document_${System.currentTimeMillis()}"
        val sanitizedName = FilenameSanitizer.sanitize(finalName)
        val finalMimeType = mimeType ?: "application/octet-stream"

        return SafFileMetadata(
            fileName = sanitizedName,
            fileSize = maxOf(0L, size ?: 0L),
            mimeType = finalMimeType,
            uri = uri
        )
    }
}

