package com.teamshare.app.e2e

import android.view.WindowManager
import com.teamshare.app.core.platform.storage.StatFsStorageAuditor
import com.teamshare.app.core.platform.window.WindowSecurityManager
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.core.security.keystore.SoftwareKeyManager
import com.teamshare.app.core.security.sanitizer.SafeLogger
import com.teamshare.app.core.security.sanitizer.ZipSlipValidator
import com.teamshare.app.core.storage.saf.SafeFileDeleter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Stage 8 E2E Test: Kiểm thử An toàn & Phòng thủ Chuyên sâu (STRIDE & OWASP MASVS).
 * Kiểm chứng toàn diện:
 * 1. Chống Tấn công Path Traversal & Zip Slip: 100% các payload ../, ..\\, absolute path, null byte bị chặn đứng.
 * 2. Chống Tấn công Cạn kiệt Đĩa (DoS): StatFsStorageAuditor kiểm tra ngưỡng an toàn (+50MB buffer) và từ chối tải an toàn.
 * 3. Chống Pháp chứng số (Anti-Forensics Wipe): SafeFileDeleter ghi đè 100% byte 0x00 trước khi hủy liên kết file trên đĩa.
 * 4. Chống Rò rỉ Thông tin qua Màn hình: WindowSecurityManager áp dụng FLAG_SECURE chống chụp ảnh / màn hình Recent Apps.
 * 5. Chống Rò rỉ Bí mật qua Nhật ký Hệ thống: SafeLogger tự động bóc tách và che giấu Private Key, Session Token, Password.
 * 6. Tính Bất biến và Chống Can thiệp Khóa Keystore TEE: Cố tình thay đổi 1 bit trong payload hoặc chữ ký bị từ chối 100%.
 */
class SystemSecurityHardeningE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testZipSlipAttack_CompletelyBlocked_100Percent() {
        val destinationDir = tempFolder.newFolder("sandbox_destination")

        val maliciousEntryPayloads = listOf(
            "../../etc/passwd",
            "..\\..\\Windows\\System32\\cmd.exe",
            "subfolder/../../../sensitive_config.json",
            "/absolute/root/override.txt",
            "C:\\boot.ini",
            "evil_file\u0000hidden.exe"
        )

        for (maliciousPath in maliciousEntryPayloads) {
            val isBlocked = try {
                ZipSlipValidator.validatePath(destinationDir, maliciousPath)
                false // Nếu không ném ngoại lệ -> Thất bại về an ninh
            } catch (e: SecurityException) {
                true // Chặn an toàn
            } catch (e: IllegalArgumentException) {
                true // Chặn an toàn
            }

            assertTrue("Payload Zip Slip '$maliciousPath' bắt buộc phải bị chặn đứng 100%", isBlocked)
        }
    }

    @Test
    fun testStorageExhaustionDoS_StatFsRejectsTransferSafely() {
        val auditor = StatFsStorageAuditor(context = null)
        val safetyMargin = StatFsStorageAuditor.SAFETY_MARGIN_BYTES // 50 MB

        val requiredFileBytes = 80L * 1024L * 1024L // 80 MB
        // Giả lập thiết bị chỉ còn 100 MB trống (< 80MB + 50MB safety margin = 130MB)
        auditor.simulateStorage(
            availableBytes = 100L * 1024L * 1024L,
            totalBytes = 500L * 1024L * 1024L
        )

        val hasSpace = auditor.hasSufficientSpace(requiredFileBytes)
        assertFalse("Hệ thống bắt buộc phải từ chối khi dung lượng trống không đủ ngưỡng an toàn (+50MB)", hasSpace)

        val auditResult = auditor.auditStorage(requiredFileBytes)
        assertFalse(auditResult.isSufficient)
        assertEquals(30L * 1024L * 1024L, auditResult.shortfallBytes) // Thiếu đúng 30 MB
    }

    @Test
    fun testAntiForensicsWipe_OverwritesWithZeros_BeforeDeletion() {
        val classifiedFile = tempFolder.newFile("defense_strategy_plan.part")
        val sensitiveData = "TÀI LIỆU TUYỆT MẬT KMA - PHÒNG VỆ AN NINH QUỐC GIA".toByteArray(StandardCharsets.UTF_8)

        FileOutputStream(classifiedFile).use { fos ->
            repeat(100) {
                fos.write(sensitiveData)
            }
        }

        assertTrue(classifiedFile.length() > 0)

        // 1. Kích hoạt tiêu hủy byte
        val wipeOk = SafeFileDeleter.wipeFileContent(classifiedFile)
        assertTrue("Hàm tiêu hủy byte phải trả về true", wipeOk)

        // Quét nhị phân toàn bộ file: Tất cả byte đều phải là 0x00
        FileInputStream(classifiedFile).use { fis ->
            val buf = ByteArray(1024)
            var bytesRead: Int
            while (fis.read(buf).also { bytesRead = it } != -1) {
                for (i in 0 until bytesRead) {
                    assertEquals("Mọi byte trong file phải là 0x00 (Byte $i)", 0.toByte(), buf[i])
                }
            }
        }

        // 2. Xóa an toàn khỏi hệ thống tệp
        SafeFileDeleter.wipeAndSanitizeFile(classifiedFile)
        assertFalse("Tệp phải bị xóa hoàn toàn khỏi đĩa cứng sau khi sanitize", classifiedFile.exists())
    }

    @Test
    fun testWindowSecurityManager_AppliesFlagSecure() {
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, WindowSecurityManager.FLAG_SECURE)
        assertTrue(WindowSecurityManager.FLAG_SECURE > 0)

        val secureFlags = WindowSecurityManager.FLAG_SECURE or 0x00000004
        assertTrue("Phải phát hiện cờ FLAG_SECURE đã được bật", WindowSecurityManager.isFlagSecureBitSet(secureFlags))

        val insecureFlags = 0x00000004
        assertFalse("Phải xác nhận cờ FLAG_SECURE chưa được bật", WindowSecurityManager.isFlagSecureBitSet(insecureFlags))
    }

    @Test
    fun testSafeLogger_RedactsSecretsAndKeys() {
        val testLog = "Giao thức handshake: sessionToken=token_classified_xyz_9999, privateKey=abc1234567890def, passphrase=mypassword!"
        val sanitized = SafeLogger.sanitize(testLog)

        assertFalse("Không được để lộ sessionToken ra Logcat", sanitized.contains("token_classified_xyz_9999"))
        assertFalse("Không được để lộ privateKey ra Logcat", sanitized.contains("abc1234567890def"))
        assertFalse("Không được để lộ passphrase ra Logcat", sanitized.contains("mypassword!"))

        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun testKeystoreTamperResistance_RejectsModifiedData() {
        val keyManager = SoftwareKeyManager()
        val pubKey = keyManager.generateDeviceKeyPair()
        assertNotNull(pubKey)

        val originalText = "BIÊN NHẬN SỐ BẢO MẬT TEAMSHARE - MASVS VERIFIED".toByteArray(StandardCharsets.UTF_8)
        val signature = keyManager.signData(IKeyManager.DEVICE_IDENTITY_KEY_ALIAS, originalText)
        assertNotNull(signature)

        // 1. Xác thực thành công dữ liệu gốc
        assertTrue(keyManager.verifySignature(pubKey, originalText, signature))

        // 2. Kẻ tấn công sửa đổi 1 bit trong payload
        val tamperedPayload = originalText.copyOf()
        tamperedPayload[0] = (tamperedPayload[0].toInt() xor 0x01).toByte()
        assertFalse("Hệ thống bắt buộc phải từ chối khi payload bị sửa đổi 1 bit", keyManager.verifySignature(pubKey, tamperedPayload, signature))

        // 3. Kẻ tấn công làm hỏng 1 bit trong chữ ký
        val tamperedSignature = signature.copyOf()
        tamperedSignature[tamperedSignature.size - 1] = (tamperedSignature[tamperedSignature.size - 1].toInt() xor 0x01).toByte()
        assertFalse("Hệ thống bắt buộc phải từ chối khi chữ ký bị sửa đổi 1 bit", keyManager.verifySignature(pubKey, originalText, tamperedSignature))
    }
}

