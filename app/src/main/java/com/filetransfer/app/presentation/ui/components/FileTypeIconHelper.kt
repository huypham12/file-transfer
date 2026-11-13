package com.filetransfer.app.presentation.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Tiện ích xác định Icon đại diện cho tệp tin dựa trên phần mở rộng hoặc MIME type.
 * Giúp người dùng phân biệt nhanh chóng loại tài liệu (PDF, Ảnh, Video, Nén, Mã nguồn...).
 */
fun getFileIcon(fileName: String, mimeType: String? = null): ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val mime = mimeType?.lowercase() ?: ""

    return when {
        ext == "pdf" || mime.contains("pdf") -> Icons.Default.PictureAsPdf
        ext in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg") || mime.startsWith("image/") -> Icons.Default.Image
        ext in listOf("mp4", "mkv", "avi", "mov", "webm", "flv") || mime.startsWith("video/") -> Icons.Default.VideoFile
        ext in listOf("mp3", "wav", "m4a", "flac", "ogg", "aac") || mime.startsWith("audio/") -> Icons.Default.AudioFile
        ext in listOf("zip", "rar", "7z", "tar", "gz", "bz2") || mime.contains("zip") || mime.contains("compressed") -> Icons.Default.FolderZip
        ext in listOf("kt", "java", "py", "c", "cpp", "h", "hpp", "js", "ts", "html", "css", "json", "xml", "sh", "sql") -> Icons.Default.Code
        ext in listOf("xls", "xlsx", "csv", "tsv") || mime.contains("excel") || mime.contains("spreadsheet") -> Icons.Default.TableChart
        ext in listOf("ppt", "pptx") || mime.contains("powerpoint") || mime.contains("presentation") -> Icons.Default.Slideshow
        ext in listOf("doc", "docx", "txt", "md", "rtf") || mime.contains("word") || mime.startsWith("text/") -> Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

