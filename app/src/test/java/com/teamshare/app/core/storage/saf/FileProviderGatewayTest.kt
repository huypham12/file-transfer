package com.teamshare.app.core.storage.saf

import android.net.FakeUri
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileProviderGatewayTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testDefaultAuthorityConstant() {
        assertNotNull(FileProviderGateway.DEFAULT_AUTHORITY)
        assertTrue(FileProviderGateway.DEFAULT_AUTHORITY.endsWith(".fileprovider"))
    }

    @Test
    fun testGetShareableUriWithCustomProvider() {
        val gateway = FileProviderGateway(
            authority = "com.teamshare.fileprovider",
            uriProvider = { file, auth -> FakeUri("content", "$auth/${file.name}") }
        )
        val file = File("sample_report.pdf")
        val uri = gateway.getShareableUri(file)

        assertNotNull(uri)
        assertTrue(uri.toString().startsWith("content://com.teamshare.fileprovider/sample_report.pdf"))
    }

    @Test
    fun testCreateViewIntentProperties() {
        val gateway = FileProviderGateway(
            uriProvider = { file, auth -> FakeUri("content", "$auth/${file.name}") }
        )
        val file = tempFolder.newFile("confidential.docx")
        val intent = gateway.createViewIntent(file, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")

        assertNotNull(intent)
        val shareableUri = gateway.getShareableUri(file)
        assertTrue(shareableUri.toString().contains("confidential.docx"))
    }
}
