package com.teamshare.app.presentation.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoFile
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.domain.model.TransferProgress
import com.teamshare.app.presentation.ui.transfer.formatEta
import com.teamshare.app.presentation.viewmodel.TransferUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FileTypeIconHelperTest {

    @Test
    fun testGetFileIconExtensions() {
        assertEquals(Icons.Default.PictureAsPdf, getFileIcon("report.pdf"))
        assertEquals(Icons.Default.Image, getFileIcon("avatar.PNG"))
        assertEquals(Icons.Default.Image, getFileIcon("photo.jpg"))
        assertEquals(Icons.Default.VideoFile, getFileIcon("clip.mp4"))
        assertEquals(Icons.Default.AudioFile, getFileIcon("song.mp3"))
        assertEquals(Icons.Default.FolderZip, getFileIcon("archive.zip"))
        assertEquals(Icons.Default.Code, getFileIcon("Main.kt"))
        assertEquals(Icons.Default.Code, getFileIcon("script.py"))
        assertEquals(Icons.Default.TableChart, getFileIcon("sheet.xlsx"))
        assertEquals(Icons.Default.Slideshow, getFileIcon("presentation.pptx"))
        assertEquals(Icons.Default.Description, getFileIcon("readme.md"))
        assertEquals(Icons.AutoMirrored.Filled.InsertDriveFile, getFileIcon("unknown.bin"))
    }

    @Test
    fun testGetFileIconMimeTypes() {
        assertEquals(Icons.Default.PictureAsPdf, getFileIcon("file", "application/pdf"))
        assertEquals(Icons.Default.Image, getFileIcon("file", "image/jpeg"))
        assertEquals(Icons.Default.VideoFile, getFileIcon("file", "video/mp4"))
        assertEquals(Icons.Default.AudioFile, getFileIcon("file", "audio/mpeg"))
        assertEquals(Icons.Default.FolderZip, getFileIcon("file", "application/zip"))
    }

    @Test
    fun testFormatEta() {
        assertEquals("Đang tính toán...", formatEta(0L))
        assertEquals("Đang tính toán...", formatEta(-5L))
        assertEquals("45 giây", formatEta(45L))
        assertEquals("1 phút 05 giây", formatEta(65L))
        assertEquals("2 phút 00 giây", formatEta(120L))
    }

    @Test
    fun testTransferUiStateEtaCalculation() {
        // total = 100 MB, transferred = 50 MB (remaining 50 MB), speed = 10 MB/s => ETA = 5s
        val progress = TransferProgress(
            sessionId = "s1",
            state = TransferEngineState.TRANSFERRING,
            bytesTransferred = 50 * 1024 * 1024L,
            totalBytes = 100 * 1024 * 1024L,
            speedBytesPerSec = 10 * 1024 * 1024L,
            progressPercent = 0.5f
        )
        val uiState = TransferUiState.fromProgress(progress, fileName = "test.dat")
        assertEquals(5L, uiState.etaSeconds)
        assertEquals("5 giây", formatEta(uiState.etaSeconds))
    }

    @Test
    fun testTransferUiStateZeroSpeedEta() {
        val progress = TransferProgress(
            sessionId = "s2",
            state = TransferEngineState.TRANSFERRING,
            bytesTransferred = 0L,
            totalBytes = 100 * 1024 * 1024L,
            speedBytesPerSec = 0L,
            progressPercent = 0f
        )
        val uiState = TransferUiState.fromProgress(progress)
        assertEquals(0L, uiState.etaSeconds)
        assertEquals("Đang tính toán...", formatEta(uiState.etaSeconds))
    }
}

