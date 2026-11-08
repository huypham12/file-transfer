package com.teamshare.app.core.platform.share

import com.teamshare.app.domain.model.SessionQrPayload
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferType
import org.junit.Assert.*
import org.junit.Test

class ShareCodeHelperTest {

    private val samplePayload = SessionQrPayload(
        sessionId = "sess_share_test_123",
        sessionToken = "tok_share_test_456",
        senderIp = "192.168.1.88",
        senderPort = 8889,
        channel = TransferChannel.DIRECT_LAN,
        transferType = TransferType.BATCH_FILES,
        totalBytes = 15_000_000L,
        itemCount = 3,
        senderDeviceModel = "Xiaomi Redmi Turbo 4 Pro",
        expiresAt = System.currentTimeMillis() + 300_000L
    )

    @Test
    fun testExtractPayload_pureUri() {
        val uri = samplePayload.toQrString()
        val extracted = ShareCodeHelper.extractPayload(uri)
        assertEquals(uri, extracted)
    }

    @Test
    fun testExtractPayload_chatMessageSurroundedWithText() {
        val uri = samplePayload.toQrString()
        val chatMessage = """
            Chào bạn, mình gửi bạn tệp dự án qua TeamShare nhé!
            Mã nhận tệp: $uri
            Bạn dán vào app để tải ngay nhé, mã có hiệu lực 5 phút!
        """.trimIndent()

        val extracted = ShareCodeHelper.extractPayload(chatMessage)
        assertEquals(uri, extracted)

        // Phân giải thành công từ chuỗi đã trích xuất
        val parsed = SessionQrPayload.parse(chatMessage)
        assertEquals("sess_share_test_123", parsed.sessionId)
        assertEquals("tok_share_test_456", parsed.sessionToken)
        assertEquals("192.168.1.88", parsed.senderIp)
    }

    @Test
    fun testExtractPayload_pureJson() {
        val json = samplePayload.toJson()
        val extracted = ShareCodeHelper.extractPayload(json)
        assertEquals(json, extracted)

        val parsed = SessionQrPayload.parse(extracted)
        assertEquals("sess_share_test_123", parsed.sessionId)
    }

    @Test
    fun testExtractPayload_emptyOrWhitespace() {
        assertEquals("", ShareCodeHelper.extractPayload(""))
        assertEquals("", ShareCodeHelper.extractPayload("   \n\t  "))
    }

    @Test
    fun testFormatShareMessage() {
        val message = ShareCodeHelper.formatShareMessage(samplePayload)
        assertTrue(message.contains("TeamShare"))
        assertTrue(message.contains("Xiaomi Redmi Turbo 4 Pro"))
        assertTrue(message.contains("3 tệp"))
        assertTrue(message.contains(samplePayload.toQrString()))
        assertTrue(message.contains("5 phút"))
    }
}

