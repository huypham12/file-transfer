package com.filetransfer.app.core.security.sanitizer

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SafeLoggerTest {

    private class TestLogSink : SafeLogger.LogSink {
        val messages = mutableListOf<String>()
        val priorities = mutableListOf<Int>()
        val tags = mutableListOf<String>()

        override fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
            priorities.add(priority)
            tags.add(tag)
            messages.add(message)
        }
    }

    private lateinit var testSink: TestLogSink

    @Before
    fun setUp() {
        testSink = TestLogSink()
        SafeLogger.setLogSink(testSink)
        SafeLogger.isDebugEnabled = true
    }

    @After
    fun tearDown() {
        SafeLogger.setLogSink(null)
        SafeLogger.isDebugEnabled = true
    }

    @Test
    fun testSanitizeSessionToken() {
        val raw = "Khởi tạo kết nối với sessionToken=0123456789abcdef và tok=token_xyz_999 thành công"
        val clean = SafeLogger.sanitize(raw)

        assertFalse(clean.contains("0123456789abcdef"))
        assertFalse(clean.contains("token_xyz_999"))
        assertTrue(clean.contains("sessionToken=[REDACTED]"))
        assertTrue(clean.contains("tok=[REDACTED]"))
    }

    @Test
    fun testSanitizePassphraseAndPassword() {
        val raw = "Cấu hình SQLCipher với passphrase=super_secret_db_pass! và password=user_pass_123"
        val clean = SafeLogger.sanitize(raw)

        assertFalse(clean.contains("super_secret_db_pass!"))
        assertFalse(clean.contains("user_pass_123"))
        assertTrue(clean.contains("passphrase=[REDACTED]"))
        assertTrue(clean.contains("password=[REDACTED]"))
    }

    @Test
    fun testSanitizePemPrivateKeyBlock() {
        val pem = """
            -----BEGIN EC PRIVATE KEY-----
            MHcCAQEEILeK3hLz9/7y...secret_key_material...
            -----END EC PRIVATE KEY-----
        """.trimIndent()
        val raw = "Khóa cá nhân được sinh: $pem trong TEE"
        val clean = SafeLogger.sanitize(raw)

        assertFalse(clean.contains("secret_key_material"))
        assertTrue(clean.contains("[REDACTED_PRIVATE_KEY]"))
    }

    @Test
    fun testDebugLoggingSuppressedWhenDebugDisabled() {
        SafeLogger.isDebugEnabled = false

        SafeLogger.d("TEST_TAG", "Tin nhắn debug không được phép xuất hiện trong release build")
        assertEquals(0, testSink.messages.size)

        // Các mức Info, Warn, Error vẫn hoạt động
        SafeLogger.i("TEST_TAG", "Tin nhắn info quan trọng")
        assertEquals(1, testSink.messages.size)
        assertEquals("Tin nhắn info quan trọng", testSink.messages[0])
    }

    @Test
    fun testWarningAndErrorLogging() {
        val exception = IllegalStateException("Lỗi kiểm thử")
        SafeLogger.w("WARN_TAG", "Cảnh báo an ninh với token=tok_leak_test", exception)
        SafeLogger.e("ERR_TAG", "Lỗi nghiêm trọng với password=leak_pass", exception)

        assertEquals(2, testSink.messages.size)
        assertTrue(testSink.messages[0].contains("token=[REDACTED]"))
        assertTrue(testSink.messages[1].contains("password=[REDACTED]"))
    }

    @Test
    fun testNormalMessageWithoutSensitiveDataRemainsUnchanged() {
        val normal = "Truyền thành công tệp sample.pdf (dung lượng: 1048576 bytes)"
        val clean = SafeLogger.sanitize(normal)
        assertEquals(normal, clean)
    }
}

