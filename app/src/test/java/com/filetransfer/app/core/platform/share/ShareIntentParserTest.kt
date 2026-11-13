package com.filetransfer.app.core.platform.share

import android.content.Intent
import android.net.FakeUri
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntentParserTest {

    class FakeShareIntentSource(
        override val action: String?,
        override val mimeType: String? = null,
        private val singleUri: Uri? = null,
        private val multipleUris: List<Uri>? = null
    ) : IShareIntentSource {
        override fun getSingleStreamUri(): Uri? = singleUri
        override fun getMultipleStreamUris(): List<Uri>? = multipleUris
    }

    @Test
    fun testParseNullIntentReturnsEmpty() {
        val parsed = ShareIntentParser.parse(null as Intent?)
        assertTrue(parsed.uris.isEmpty())
        assertFalse(ShareIntentParser.hasValidShareData(null as Intent?))
    }

    @Test
    fun testParseActionSendSingleContentUri() {
        val uri = FakeUri("content", "media/external/images/media/12345")
        val source = FakeShareIntentSource(
            action = Intent.ACTION_SEND,
            mimeType = "image/png",
            singleUri = uri
        )

        val parsed = ShareIntentParser.parse(source)
        assertEquals(1, parsed.uris.size)
        assertEquals("image/png", parsed.mimeType)
        assertEquals(Intent.ACTION_SEND, parsed.action)
        assertTrue(ShareIntentParser.hasValidShareData(source))
    }

    @Test
    fun testParseActionSendMultipleUris() {
        val uri1 = FakeUri("content", "documents/doc1.pdf")
        val uri2 = FakeUri("content", "documents/doc2.pdf")
        val urisList = listOf(uri1, uri2)

        val source = FakeShareIntentSource(
            action = Intent.ACTION_SEND_MULTIPLE,
            mimeType = "application/pdf",
            multipleUris = urisList
        )

        val parsed = ShareIntentParser.parse(source)
        assertEquals(2, parsed.uris.size)
        assertEquals("application/pdf", parsed.mimeType)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, parsed.action)
        assertTrue(ShareIntentParser.hasValidShareData(source))
    }

    @Test
    fun testParseFiltersOutDangerousWebSchemes() {
        val validUri = FakeUri("content", "media/image.jpg")
        val httpUri = FakeUri("http", "malicious-site.com/exploit.apk")
        val urisList = listOf(validUri, httpUri)

        val source = FakeShareIntentSource(
            action = Intent.ACTION_SEND_MULTIPLE,
            mimeType = "*/*",
            multipleUris = urisList
        )

        val parsed = ShareIntentParser.parse(source)
        // Chỉ giữ lại content URI, loại bỏ http URI
        assertEquals(1, parsed.uris.size)
        assertEquals("content", parsed.uris[0].scheme)
    }

    @Test
    fun testParseUnknownActionReturnsEmpty() {
        val uri = FakeUri("content", "test.txt")
        val source = FakeShareIntentSource(
            action = Intent.ACTION_VIEW,
            mimeType = "text/plain",
            singleUri = uri
        )

        val parsed = ShareIntentParser.parse(source)
        assertTrue(parsed.uris.isEmpty())
        assertFalse(ShareIntentParser.hasValidShareData(source))
    }

    @Test
    fun testIsValidScheme() {
        assertTrue(ShareIntentParser.isValidScheme("content"))
        assertTrue(ShareIntentParser.isValidScheme("file"))
        assertTrue(ShareIntentParser.isValidScheme("CONTENT"))
        assertFalse(ShareIntentParser.isValidScheme("http"))
        assertFalse(ShareIntentParser.isValidScheme("https"))
        assertFalse(ShareIntentParser.isValidScheme(null))
    }

    @Test
    fun testShareIntentHandlerConstants() {
        assertEquals("com.filetransfer.app.extra.SHARED_URIS", com.filetransfer.app.presentation.ui.share.ShareIntentHandlerActivity.EXTRA_SHARED_URIS)
        assertEquals("com.filetransfer.app.extra.SHARED_NAMES", com.filetransfer.app.presentation.ui.share.ShareIntentHandlerActivity.EXTRA_SHARED_NAMES)
        assertEquals("com.filetransfer.app.extra.FROM_SHARE_INTENT", com.filetransfer.app.presentation.ui.share.ShareIntentHandlerActivity.EXTRA_FROM_SHARE_INTENT)
        assertEquals("com.filetransfer.app.extra.SHARE_COUNT", com.filetransfer.app.presentation.ui.share.ShareIntentHandlerActivity.EXTRA_SHARE_COUNT)
        assertEquals("com.filetransfer.app.extra.MIME_TYPE", com.filetransfer.app.presentation.ui.share.ShareIntentHandlerActivity.EXTRA_MIME_TYPE)
    }
}
