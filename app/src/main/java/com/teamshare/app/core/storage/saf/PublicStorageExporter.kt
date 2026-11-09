package com.teamshare.app.core.storage.saf

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.teamshare.app.core.constant.AppConstants
import com.teamshare.app.core.security.sanitizer.FilenameSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Tiện ích xuất tệp và thư mục an toàn ra thư mục công cộng Downloads/TeamShare của thiết bị.
 * Tương thích toàn diện với Android 10+ (API 29+ Scoped Storage MediaStore) và Android 9-.
 */
class PublicStorageExporter(private val context: Context) {

    /**
     * Xuất một tệp đơn lẻ vào Downloads/TeamShare/[subDir]/[fileName].
     */
    suspend fun exportSingleFile(sourceFile: File, subDir: String? = null): Result<String> = withContext(Dispatchers.IO) {
        if (!sourceFile.exists()) {
            return@withContext Result.failure(IOException("Tệp nguồn không tồn tại"))
        }

        val sanitizedName = FilenameSanitizer.sanitize(sourceFile.name)
        val relativeSubPath = buildRelativePath(subDir)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizedName)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeSubPath)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext Result.failure(IOException("Không thể tạo mục ghi trong MediaStore.Downloads"))

                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out)
                    }
                } ?: return@withContext Result.failure(IOException("Không thể mở luồng ghi vào MediaStore"))

                Result.success("$relativeSubPath/$sanitizedName")
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetFolder = if (!subDir.isNullOrBlank()) {
                    File(downloadsDir, "${AppConstants.StorageDirectory.TEAM_SHARE_PUBLIC_FOLDER}/$subDir")
                } else {
                    File(downloadsDir, AppConstants.StorageDirectory.TEAM_SHARE_PUBLIC_FOLDER)
                }.apply { mkdirs() }

                val destFile = File(targetFolder, sanitizedName)
                sourceFile.copyTo(destFile, overwrite = true)
                Result.success(destFile.absolutePath)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Xuất danh sách nhiều tệp con (batch files) vào Downloads/TeamShare/[targetSubDir].
     * Trả về số lượng tệp đã xuất thành công.
     */
    suspend fun exportBatchFiles(sourceFiles: List<File>, targetSubDir: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        if (sourceFiles.isEmpty()) {
            return@withContext Result.success(0)
        }

        var successCount = 0
        for (file in sourceFiles) {
            if (file.exists() && !file.isDirectory) {
                val res = exportSingleFile(file, targetSubDir)
                if (res.isSuccess) {
                    successCount++
                }
            }
        }

        if (successCount > 0) {
            Result.success(successCount)
        } else {
            Result.failure(IOException("Không thể lưu bất kỳ tệp tin nào vào thư mục Downloads"))
        }
    }

    /**
     * Xuất/Giải nén toàn bộ cây thư mục vào Downloads/TeamShare/[folderName]/...
     * Giữ nguyên cấu trúc thư mục con và các tệp bên trong.
     */
    suspend fun exportFolder(folder: File): Result<Int> = withContext(Dispatchers.IO) {
        if (!folder.exists() || !folder.isDirectory) {
            return@withContext Result.failure(IOException("Thư mục nguồn không hợp lệ"))
        }

        val folderName = FilenameSanitizer.sanitize(folder.name)
        val filesToExport = mutableListOf<File>()

        fun collectFiles(dir: File) {
            dir.listFiles()?.filterNot { it.name.startsWith(".") }?.forEach { f ->
                if (f.isDirectory) {
                    collectFiles(f)
                } else {
                    filesToExport.add(f)
                }
            }
        }
        collectFiles(folder)

        if (filesToExport.isEmpty()) {
            return@withContext Result.success(0)
        }

        var exportedCount = 0
        for (file in filesToExport) {
            val relParent = file.parentFile?.relativeToOrNull(folder)?.path?.replace('\\', '/')
            val subDir = if (!relParent.isNullOrBlank()) "$folderName/$relParent" else folderName
            val res = exportSingleFile(file, subDir)
            if (res.isSuccess) {
                exportedCount++
            }
        }

        Result.success(exportedCount)
    }

    /**
     * Giải nén trực tiếp tệp .zip vào thư mục công cộng Downloads/TeamShare/[targetFolderName].
     */
    suspend fun extractZipToDownloads(zipFile: File, targetFolderName: String): Result<Int> = withContext(Dispatchers.IO) {
        if (!zipFile.exists()) {
            return@withContext Result.failure(IOException("Tệp zip không tồn tại"))
        }

        val sanitizedFolder = FilenameSanitizer.sanitize(targetFolderName)
        var count = 0

        try {
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && !entry.name.startsWith("__MACOSX") && !entry.name.startsWith(".")) {
                        val entryFile = File(entry.name)
                        val fileName = FilenameSanitizer.sanitize(entryFile.name)
                        val entryParent = entryFile.parent?.replace('\\', '/')
                        val subDir = if (!entryParent.isNullOrBlank()) "$sanitizedFolder/$entryParent" else sanitizedFolder
                        val relativeSubPath = buildRelativePath(subDir)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeSubPath)
                            }
                            val resolver = context.contentResolver
                            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                            if (uri != null) {
                                resolver.openOutputStream(uri)?.use { out ->
                                    zis.copyTo(out)
                                }
                                count++
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            val targetDir = File(downloadsDir, "${AppConstants.StorageDirectory.TEAM_SHARE_PUBLIC_FOLDER}/$subDir").apply { mkdirs() }
                            val dest = File(targetDir, fileName)
                            FileOutputStream(dest).use { fos ->
                                zis.copyTo(fos)
                            }
                            count++
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildRelativePath(subDir: String?): String {
        return if (!subDir.isNullOrBlank()) {
            "${Environment.DIRECTORY_DOWNLOADS}/${AppConstants.StorageDirectory.TEAM_SHARE_PUBLIC_FOLDER}/$subDir"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/${AppConstants.StorageDirectory.TEAM_SHARE_PUBLIC_FOLDER}"
        }
    }
}

