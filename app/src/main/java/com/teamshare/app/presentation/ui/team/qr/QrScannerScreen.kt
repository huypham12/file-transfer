package com.teamshare.app.presentation.ui.team.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.teamshare.app.domain.model.QrInvitePayload
import com.teamshare.app.presentation.ui.components.ErrorBanner
import com.teamshare.app.presentation.viewmodel.TeamViewModel
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    viewModel: TeamViewModel,
    onBackClick: () -> Unit,
    onJoinSuccess: (teamId: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.teamListUiState.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var detectedPayload by remember { mutableStateOf<QrInvitePayload?>(null) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var showManualInputDialog by remember { mutableStateOf(false) }
    var memberNameInput by remember { mutableStateOf("") }
    var manualQrInput by remember { mutableStateOf("") }
    var scannerError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quét mã QR gia nhập") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showManualInputDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Nhập mã thủ công"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        val cameraExecutor = Executors.newSingleThreadExecutor()

                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val analyzer = QrCodeAnalyzer { qrString ->
                                val parsed = parseQrPayload(qrString)
                                if (parsed != null && !showJoinDialog) {
                                    detectedPayload = parsed
                                    showJoinDialog = true
                                }
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also {
                                    it.setAnalyzer(cameraExecutor, analyzer)
                                }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                scannerError = "Không thể kết nối Camera: ${e.message}"
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Khung viền ngắm quét QR ở giữa màn hình
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .align(Alignment.Center)
                        .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Hướng Camera vào mã QR của Trưởng nhóm",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FilledTonalButton(onClick = { showManualInputDialog = true }) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Nhập mã bằng văn bản")
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Cần quyền truy cập Camera để quét mã QR",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Cấp quyền Camera")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = { showManualInputDialog = true }) {
                        Text("Nhập mã thủ công")
                    }
                }
            }

            if (scannerError != null || uiState.errorMessage != null) {
                ErrorBanner(
                    message = scannerError ?: uiState.errorMessage ?: "",
                    onDismiss = {
                        scannerError = null
                        viewModel.clearErrors()
                    },
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

            // Hộp thoại xác nhận gia nhập khi quét thành công
            if (showJoinDialog && detectedPayload != null) {
                AlertDialog(
                    onDismissRequest = {
                        if (!uiState.isLoading) showJoinDialog = false
                    },
                    title = { Text("Gia nhập Không gian Nhóm", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(
                                text = "Mã QR hợp lệ! Hãy nhập tên hiển thị của bạn trong nhóm này:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = memberNameInput,
                                onValueChange = { memberNameInput = it },
                                label = { Text("Tên của bạn (*)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !uiState.isLoading
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val name = memberNameInput.trim()
                                if (name.isNotEmpty()) {
                                    viewModel.joinTeamViaQr(
                                        qrPayload = detectedPayload!!,
                                        memberName = name,
                                        onSuccess = { member ->
                                            showJoinDialog = false
                                            onJoinSuccess(member.teamId)
                                        }
                                    )
                                }
                            },
                            enabled = memberNameInput.isNotBlank() && !uiState.isLoading
                        ) {
                            if (uiState.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Gia nhập")
                            }
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = { showJoinDialog = false },
                            enabled = !uiState.isLoading
                        ) {
                            Text("Hủy")
                        }
                    }
                )
            }

            // Hộp thoại nhập mã QR thủ công (Dự phòng cho máy ảo / Emulator)
            if (showManualInputDialog) {
                AlertDialog(
                    onDismissRequest = { showManualInputDialog = false },
                    title = { Text("Nhập chuỗi lời mời thủ công", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(
                                text = "Dán chuỗi URL lời mời (teamshare://invite?...):",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = manualQrInput,
                                onValueChange = { manualQrInput = it },
                                label = { Text("Chuỗi lời mời") },
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val parsed = parseQrPayload(manualQrInput.trim())
                                if (parsed != null) {
                                    detectedPayload = parsed
                                    showManualInputDialog = false
                                    showJoinDialog = true
                                } else {
                                    scannerError = "Định dạng chuỗi lời mời không hợp lệ"
                                }
                            },
                            enabled = manualQrInput.isNotBlank()
                        ) {
                            Text("Tiếp tục")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showManualInputDialog = false }) {
                            Text("Hủy")
                        }
                    }
                )
            }
        }
    }
}

