package com.teamshare.app.presentation.ui.components

import com.teamshare.app.core.security.sanitizer.FilenameSanitizer
import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAndSecurityTest {

    @Test
    fun testTeamSearchFilter() {
        val teams = listOf(
            Team(teamId = "t1", teamName = "Nhóm Bảo Mật", description = "Dự án tốt nghiệp", ownerFingerprint = "fp1"),
            Team(teamId = "t2", teamName = "Nhóm Frontend", description = "Jetpack Compose", ownerFingerprint = "fp2"),
            Team(teamId = "t3", teamName = "DevOps Team", description = "CI/CD Pipeline", ownerFingerprint = "fp3")
        )

        // 1. Tìm theo tên nhóm (không phân biệt hoa thường)
        val searchByName = teams.filter {
            it.teamName.contains("bảo mật", ignoreCase = true) ||
            it.description.contains("bảo mật", ignoreCase = true)
        }
        assertEquals(1, searchByName.size)
        assertEquals("t1", searchByName[0].teamId)

        // 2. Tìm theo mô tả
        val searchByDesc = teams.filter {
            it.teamName.contains("compose", ignoreCase = true) ||
            it.description.contains("compose", ignoreCase = true)
        }
        assertEquals(1, searchByDesc.size)
        assertEquals("t2", searchByDesc[0].teamId)

        // 3. Tìm không khớp
        val searchNone = teams.filter {
            it.teamName.contains("nonexistent", ignoreCase = true) ||
            it.description.contains("nonexistent", ignoreCase = true)
        }
        assertTrue(searchNone.isEmpty())
    }

    @Test
    fun testDocumentAndFolderSearchFilter() {
        val docs = listOf(
            SharedDocument(docId = "d1", teamId = "t1", publisherId = "p1", fileName = "BaoCao_KMA.pdf", fileSize = 1024L, mimeType = "application/pdf", expectedSha256 = "hash1"),
            SharedDocument(docId = "d2", teamId = "t1", publisherId = "p1", fileName = "Architecture_Diagram.png", fileSize = 2048L, mimeType = "image/png", expectedSha256 = "hash2"),
            SharedDocument(docId = "d3", teamId = "t1", publisherId = "p1", fileName = "SourceCode_Backup.zip", fileSize = 4096L, mimeType = "application/zip", expectedSha256 = "hash3")
        )
        val folders = listOf(
            TeamFolder(folderId = "f1", teamId = "t1", folderName = "Tài liệu thiết kế", createdBy = "p1", createdAt = 0L),
            TeamFolder(folderId = "f2", teamId = "t1", folderName = "Biên bản họp", createdBy = "p1", createdAt = 0L)
        )

        val query = "baocao"
        val matchedDocs = docs.filter { it.fileName.contains(query, ignoreCase = true) }
        val matchedFolders = folders.filter { it.folderName.contains(query, ignoreCase = true) }

        assertEquals(1, matchedDocs.size)
        assertEquals("d1", matchedDocs[0].docId)
        assertTrue(matchedFolders.isEmpty())

        val queryFolder = "thiết kế"
        val matchedFolders2 = folders.filter { it.folderName.contains(queryFolder, ignoreCase = true) }
        assertEquals(1, matchedFolders2.size)
        assertEquals("f1", matchedFolders2[0].folderId)
    }

    @Test
    fun testFolderNameValidationAndSanitization() {
        val dangerousInput = "../../System32/Folder"
        val containsIllegal = dangerousInput.contains('/') || dangerousInput.contains('\\') || dangerousInput.contains("..")
        assertTrue(containsIllegal)

        val sanitized = FilenameSanitizer.sanitize(dangerousInput.trim()).take(50)
        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains('\\'))
        assertFalse(sanitized.contains(".."))
        assertTrue(sanitized.length <= 50)

        // Tên quá 50 ký tự được cắt ngắn an toàn
        val veryLongName = "A".repeat(100)
        val truncated = FilenameSanitizer.sanitize(veryLongName).take(50)
        assertEquals(50, truncated.length)
    }
}

