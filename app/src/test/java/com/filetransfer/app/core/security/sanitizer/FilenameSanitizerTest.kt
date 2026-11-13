package com.filetransfer.app.core.security.sanitizer

import org.junit.Assert.*
import org.junit.Test

class FilenameSanitizerTest {

    @Test
    fun testNormalValidFilenameIsPreserved() {
        val input = "BaoCao_TotNghiep_2026.pdf"
        assertEquals(input, FilenameSanitizer.sanitize(input))
    }

    @Test
    fun testPathTraversalIsNeutralized() {
        val attack1 = "../../etc/passwd"
        val sanitized1 = FilenameSanitizer.sanitize(attack1)
        assertFalse("Không được chứa ../", sanitized1.contains("../"))
        assertFalse("Không được chứa ..", sanitized1.contains(".."))

        val attack2 = "..\\..\\Windows\\System32\\cmd.exe"
        val sanitized2 = FilenameSanitizer.sanitize(attack2)
        assertFalse("Không được chứa ..\\", sanitized2.contains("..\\"))
        assertFalse("Không được chứa \\", sanitized2.contains("\\"))
    }

    @Test
    fun testIllegalCharactersReplacedWithUnderscore() {
        val rawName = "report:test*file?name<is>bad|one.doc"
        val sanitized = FilenameSanitizer.sanitize(rawName)
        assertEquals("report_test_file_name_is_bad_one.doc", sanitized)
    }

    @Test
    fun testLongFilenameTruncationPreservesExtension() {
        val baseName = "A".repeat(300)
        val rawName = "$baseName.pdf"
        val sanitized = FilenameSanitizer.sanitize(rawName)

        assertTrue(sanitized.length <= 255)
        assertTrue(sanitized.endsWith(".pdf"))
    }

    @Test
    fun testEmptyOrInvalidFilenameFallsBackToDefault() {
        val blankResult = FilenameSanitizer.sanitize("   ")
        assertTrue(blankResult.startsWith("document_"))

        val dotsResult = FilenameSanitizer.sanitize("....")
        assertTrue(dotsResult.startsWith("document_"))

        val emptyResult = FilenameSanitizer.sanitize("")
        assertTrue(emptyResult.startsWith("document_"))
    }
}

