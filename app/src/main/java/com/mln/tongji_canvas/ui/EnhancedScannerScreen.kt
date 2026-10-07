package com.mln.tongji_canvas.ui

import android.Manifest
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.mln.tongji_canvas.ui.qr.CameraPermissionRationale
import com.mln.tongji_canvas.ui.qr.QrCameraPreview
import com.mln.tongji_canvas.ui.qr.rememberQrScannerController

/**
 * 全屏扫码页。
 *
 * 相机的实现**全部**在 [QrCameraPreview] 里（CameraX 预览 + ML Kit 识别 + 权限），
 * 本页只负责取景框/扫描线、手电与「重新扫描」这些**外观与交互**，
 * 因此与批量签到页顶部的页内扫码共用同一份相机代码。
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun EnhancedScannerScreen(
    onDetected: (String) -> Unit
) {
    val permissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val scannerController = rememberQrScannerController(onDetected)
    var isTorchEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(permissionState.status) {
        if (permissionState.status !is PermissionStatus.Granted) {
            permissionState.launchPermissionRequest()
        }
    }

    if (permissionState.status !is PermissionStatus.Granted) {
        CameraPermissionRationale(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            onRequest = { permissionState.launchPermissionRequest() }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        QrCameraPreview(
            modifier = Modifier.fillMaxSize(),
            onDetected = { value -> scannerController.handleDetected(value) },
            onError = { message -> scannerController.reportError(message) },
            torchEnabled = isTorchEnabled
        )

        ScannerOverlay(
            modifier = Modifier.fillMaxSize(),
            paused = scannerController.isPaused
        )

        scannerController.errorMessage?.let { message ->
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp, start = 16.dp, end = 16.dp)
            )
        }

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
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "line_progress"
    )
    val progress = if (paused) 0.5f else lineProgress
    val frameStrokeColor = Color.White.copy(alpha = 0.35f)
    val scanLineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)

    Canvas(modifier = modifier) {
        drawRect(color = Color.Black.copy(alpha = 0.35f))
        val frameSize = size.minDimension * 0.65f
        val strokeWidth = 4.dp.toPx()
        val left = (size.width - frameSize) / 2f
        val top = (size.height - frameSize) / 2.2f
        val right = left + frameSize
        val bottom = top + frameSize

        drawRoundRect(
            color = frameStrokeColor,
            topLeft = Offset(left, top),
            size = Size(frameSize, frameSize),
            cornerRadius = CornerRadius(36.dp.toPx()),
            style = Stroke(width = strokeWidth)
        )

        val lineY = top + frameSize * progress
        drawLine(
            color = scanLineColor,
            start = Offset(left + 16.dp.toPx(), lineY),
            end = Offset(right - 16.dp.toPx(), lineY),
            strokeWidth = 3.dp.toPx()
        )
    }
}
