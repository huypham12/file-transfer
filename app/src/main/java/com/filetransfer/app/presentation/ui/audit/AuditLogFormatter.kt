package com.filetransfer.app.presentation.ui.audit

data class FormattedAuditDetails(
    val fileName: String? = null,
    val userMessage: String,
    val technicalInfo: String? = null
)

object AuditLogFormatter {

    fun format(rawDetails: String): FormattedAuditDetails {
        var technicalInfo: String? = null
        var text = rawDetails

        // Kiểm tra xem có chứa mã lỗi kỹ thuật 0x... không
        if (text.contains("0x")) {
            technicalInfo = rawDetails
            text = text.replace(Regex("\\(Mã lỗi: 0x[0-9a-fA-F]+\\)"), "")
                .replace(Regex("0x[0-9a-fA-F]+:?\\s*"), "")
                .trim()

            if (rawDetails.contains("0x401") || rawDetails.contains("sinh trắc học")) {
                text = "Cần xác thực bảo mật hoặc thiết bị chưa thiết lập sinh trắc học"
            }
        }

        // Tách nhãn tên tệp nếu bắt đầu bằng [filename]
        var extractedFile: String? = null
        if (text.startsWith("[") && text.contains("]")) {
            val potentialFile = text.substringAfter("[").substringBefore("]")
            if (potentialFile.isNotBlank()) {
                extractedFile = potentialFile
                text = text.substringAfter("]").trim()
            }
        }

        // Làm sạch các dấu cách thừa
        text = text.replace(Regex("\\s+"), " ").trim()

        return FormattedAuditDetails(
            fileName = extractedFile,
            userMessage = text.ifBlank { rawDetails },
            technicalInfo = technicalInfo
        )
    }

    fun formatActorDevice(actor: String): String {
        val trimmed = actor.trim()
        return when {
            trimmed.isBlank() -> "Thiết bị này"
            trimmed.equals("local", ignoreCase = true) || trimmed.equals("LOCAL DEVICE", ignoreCase = true) -> "Thiết bị này"
            trimmed.startsWith("sdk_gphone", ignoreCase = true) -> "Máy ảo Android (Emulator)"
            else -> trimmed
        }
    }
}

