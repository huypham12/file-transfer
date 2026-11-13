package com.filetransfer.app.core.platform.storage

import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Hiện thực hóa kiểm toán dung lượng bộ nhớ trống thực tế sử dụng android.os.StatFs.
 * Tuân thủ quy định SDD mục 4.6, 8.5 và SAD ADR-06:
 * availableBytes = availableBlocksLong * blockSizeLong
 * Yêu cầu đĩa trống >= requiredBytes + 50MB (vùng đệm an toàn).
 */
class StatFsStorageAuditor(
    private val context: Context? = null,
    private val safetyMarginBytes: Long = SAFETY_MARGIN_BYTES
) : IStorageAuditor {

    companion object {
        /**
         * Vùng đệm an toàn tối thiểu theo SDD Mục 8.5 (50 Megabytes).
         */
        const val SAFETY_MARGIN_BYTES = 50L * 1024L * 1024L
    }

    // Biến mô phỏng phục vụ Unit Testing mà không cần can thiệp hệ thống tệp
    private val simulatedAvailable = AtomicReference<Long?>(null)
    private val simulatedTotal = AtomicReference<Long?>(null)

    /**
     * Mô phỏng dung lượng trống phục vụ kiểm thử đơn vị.
     * @param bytes Số byte giả lập, hoặc null để dùng giá trị thực tế
     */
    fun simulateStorage(availableBytes: Long?, totalBytes: Long? = null) {
        simulatedAvailable.set(availableBytes)
        simulatedTotal.set(totalBytes)
    }

    private fun resolveTargetDirectory(targetDir: File?): File {
        if (targetDir != null) {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            if (targetDir.exists()) return targetDir
        }
        if (context != null) {
            val appDir = context.getExternalFilesDir(null) ?: context.filesDir
            if (appDir != null) {
                if (!appDir.exists()) {
                    appDir.mkdirs()
                }
                return appDir
            }
        }
        val defaultDir = File(System.getProperty("java.io.tmpdir") ?: ".")
        if (!defaultDir.exists()) {
            defaultDir.mkdirs()
        }
        return defaultDir
    }

    override fun getAvailableBytes(targetDir: File?): Long {
        val sim = simulatedAvailable.get()
        if (sim != null) return maxOf(0L, sim)

        val dir = resolveTargetDirectory(targetDir)
        return try {
            val statFs = StatFs(dir.absolutePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                statFs.availableBlocksLong * statFs.blockSizeLong
            } else {
                @Suppress("DEPRECATION")
                statFs.availableBlocks.toLong() * statFs.blockSize.toLong()
            }
        } catch (_: Throwable) {
            // Fallback an toàn trên môi trường JVM Unit Test hoặc khi hệ thống tệp không hỗ trợ StatFs
            maxOf(0L, dir.usableSpace)
        }
    }

    override fun getTotalBytes(targetDir: File?): Long {
        val sim = simulatedTotal.get()
        if (sim != null) return maxOf(0L, sim)

        val dir = resolveTargetDirectory(targetDir)
        return try {
            val statFs = StatFs(dir.absolutePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                statFs.blockCountLong * statFs.blockSizeLong
            } else {
                @Suppress("DEPRECATION")
                statFs.blockCount.toLong() * statFs.blockSize.toLong()
            }
        } catch (_: Throwable) {
            maxOf(0L, dir.totalSpace)
        }
    }

    override fun hasSufficientSpace(requiredBytes: Long, targetDir: File?): Boolean {
        val available = getAvailableBytes(targetDir)
        return available >= (requiredBytes + safetyMarginBytes)
    }

    override fun auditStorage(requiredBytes: Long, targetDir: File?): StorageAuditResult {
        val available = getAvailableBytes(targetDir)
        val total = getTotalBytes(targetDir)
        val isSufficient = available >= (requiredBytes + safetyMarginBytes)
        return StorageAuditResult(
            availableBytes = available,
            totalBytes = total,
            requiredBytes = requiredBytes,
            safetyMarginBytes = safetyMarginBytes,
            isSufficient = isSufficient
        )
    }
}

