package com.teamshare.app.presentation.ui.share

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.teamshare.app.presentation.MainActivity
import com.teamshare.app.core.platform.share.ShareIntentParser
import com.teamshare.app.core.security.sanitizer.FilenameSanitizer
import com.teamshare.app.core.storage.saf.SafStorageGateway

/**
 * Màn hình tiếp nhận chia sẻ tệp tin từ các ứng dụng khác trên hệ điều hành Android (Android Share Sheet).
 * Tuân thủ:
 * - SRS FR-MOB-01 (UC-16): Tiếp nhận Intent ACTION_SEND và ACTION_SEND_MULTIPLE.
 * - Bảo lưu quyền đọc an toàn bằng takePersistableUriPermission() qua SafStorageGateway.
 * - Khử độc tên tệp qua FilenameSanitizer chống tấn công Path Traversal.
 * - Chuyển tiếp danh sách tệp vào MainActivity để người dùng chọn Không gian Nhóm nhận tệp.
 */
class ShareIntentHandlerActivity : Activity() {

    companion object {
        const val EXTRA_SHARED_URIS = "com.teamshare.app.extra.SHARED_URIS"
        const val EXTRA_SHARED_NAMES = "com.teamshare.app.extra.SHARED_NAMES"
        const val EXTRA_FROM_SHARE_INTENT = "com.teamshare.app.extra.FROM_SHARE_INTENT"
        const val EXTRA_SHARE_COUNT = "com.teamshare.app.extra.SHARE_COUNT"
        const val EXTRA_MIME_TYPE = "com.teamshare.app.extra.MIME_TYPE"
    }

    private lateinit var safGateway: SafStorageGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        safGateway = SafStorageGateway(applicationContext)

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val parsedData = ShareIntentParser.parse(intent)

        if (parsedData.uris.isEmpty()) {
            Toast.makeText(this, "Không tìm thấy tệp tin hợp lệ để chia sẻ", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Bảo lưu quyền truy cập đọc bền vững và kiểm tra metadata
        val validatedUris = ArrayList<Uri>()
        val sanitizedNames = ArrayList<String>()

        for (uri in parsedData.uris) {
            try {
                safGateway.takePersistableUriPermission(uri)
            } catch (e: Exception) {
                // Nếu không xin được persistable permission (URI tạm), vẫn cố gắng chuyển tiếp qua ClipData
            }

            val metadata = safGateway.queryMetadata(uri)
            val sanitizedName = if (metadata != null) {
                FilenameSanitizer.sanitize(metadata.fileName)
            } else {
                "shared_file_${System.currentTimeMillis()}"
            }

            validatedUris.add(uri)
            sanitizedNames.add(sanitizedName)
        }

        if (validatedUris.isNotEmpty()) {
            // Chuyển tiếp tới MainActivity
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putParcelableArrayListExtra(EXTRA_SHARED_URIS, validatedUris)
                putStringArrayListExtra(EXTRA_SHARED_NAMES, sanitizedNames)
                putExtra(EXTRA_FROM_SHARE_INTENT, true)
                putExtra(EXTRA_SHARE_COUNT, validatedUris.size)
                putExtra(EXTRA_MIME_TYPE, parsedData.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                // Finding 1 (P2): Thiết lập ClipData để ủy quyền đọc URI an toàn sang MainActivity
                val clipData = ClipData.newUri(contentResolver, "TeamShare Incoming", validatedUris[0])
                for (i in 1 until validatedUris.size) {
                    clipData.addItem(ClipData.Item(validatedUris[i]))
                }
                this.clipData = clipData
            }
            startActivity(mainIntent)
        }

        // Đóng activity trung gian ngay sau khi đã chuyển giao dữ liệu an toàn
        finish()
    }
}

