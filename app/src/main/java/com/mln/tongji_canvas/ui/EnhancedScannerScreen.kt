package com.mln.tongji_canvas.ui

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.mln.tongji_canvas.ui.qr.rememberQrScannerController

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun EnhancedScannerScreen(
    onDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val permissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val scannerController = rememberQrScannerController(onDetected)
    var isTorchEnabled by remember { mutableStateOf(false) }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
    }

    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_DATA_MATRIX
                )
                .build()
        )
    }

    DisposableEffect(Unit) {
        onDispose { barcodeScanner.close() }
    }

    DisposableEffect(lifecycleOwner) {
        cameraController.bindToLifecycle(lifecycleOwner)
        onDispose { cameraController.unbind() }
    }

    LaunchedEffect(permissionState.status) {
        if (permissionState.status is PermissionStatus.Granted) {
            val executor = ContextCompat.getMainExecutor(context)
            cameraController.setImageAnalysisAnalyzer(
                executor,
                MlKitAnalyzer(
                    listOf(barcodeScanner),
                    CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                    executor
                ) { result ->
                    val matches = result?.getValue(barcodeScanner).orEmpty()
                    val candidate = matches.firstOrNull { !it.rawValue.isNullOrBlank() }
                    val value = candidate?.rawValue
                    if (!value.isNullOrBlank()) {
                        scannerController.handleDetected(value)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            )
        } else {
            permissionState.launchPermissionRequest()
        }
    }

    LaunchedEffect(isTorchEnabled) {
        if (permissionState.status is PermissionStatus.Granted) {
            try {
                cameraController.enableTorch(isTorchEnabled)
            } catch (e: Exception) {
                println("Torch toggle failed: ${e.message}")
                isTorchEnabled = false
            }
        }
    }

    if (permissionState.status !is PermissionStatus.Granted) {
        CameraPermissionRationale(
            onRequest = { permissionState.launchPermissionRequest() }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    controller = cameraController
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        ScannerOverlay(
            modifier = Modifier.fillMaxSize(),
            paused = scannerController.isPaused
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("对准签到二维码", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                "自动支持小尺寸、倾斜二维码",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            scannerController.lastResult?.let { last ->
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(
                            text = last,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) }
                )
            }

            Surface(
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (scannerController.isPaused) "识别完成，可重新扫描" else "将二维码置于取景框中",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                isTorchEnabled = !isTorchEnabled
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isTorchEnabled) Icons.Outlined.FlashlightOff else Icons.Outlined.FlashlightOn,
                                contentDescription = null
                            )
                            Text(if (isTorchEnabled) "关闭手电" else "开启手电")
                        }
                        FilledTonalButton(
                            onClick = {
                                scannerController.resume()
                                isTorchEnabled = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("重新扫描")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerOverlay(
    modifier: Modifier,
    paused: Boolean
) {
    val transition = rememberInfiniteTransition(label = "scan_line")
    val lineProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "line_progress"
    )
    val progress = if (paused) 0.5f else lineProgress
    val frameStrokeColor = Color.White.copy(alpha = 0.35f)
    val scanLineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)

    androidx.compose.foundation.Canvas(modifier = modifier) {
        drawRect(color = Color.Black.copy(alpha = 0.35f))
        val frameSize = size.minDimension * 0.65f
        val strokeWidth = 4.dp.toPx()
        val left = (size.width - frameSize) / 2f
        val top = (size.height - frameSize) / 2.2f
        val right = left + frameSize
        val bottom = top + frameSize

        drawRoundRect(
            color = frameStrokeColor,
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(frameSize, frameSize),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(36.dp.toPx()),
            style = Stroke(width = strokeWidth)
        )

        val lineY = top + frameSize * progress
        drawLine(
            color = scanLineColor,
            start = androidx.compose.ui.geometry.Offset(left + 16.dp.toPx(), lineY),
            end = androidx.compose.ui.geometry.Offset(right - 16.dp.toPx(), lineY),
            strokeWidth = 3.dp.toPx()
        )
    }
}

@Composable
private fun CameraPermissionRationale(
    onRequest: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        shape = RoundedCornerShape(32.dp),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text("需要相机权限以扫描签到二维码", style = MaterialTheme.typography.titleMedium)
            Text(
                "请授予相机使用权限，以便快速识别签到码。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRequest) {
                Text("授予权限")
            }
        }
    }
}

