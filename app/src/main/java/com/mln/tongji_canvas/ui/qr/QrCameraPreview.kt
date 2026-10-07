package com.mln.tongji_canvas.ui.qr

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * 可复用的二维码取景器：CameraX 预览 + ML Kit 识别 + 相机权限处理。
 *
 * 这里是**唯一**一份相机实现 —— 全屏扫码页（[com.mln.tongji_canvas.ui.EnhancedScannerScreen]）
 * 与批量签到页顶部的「页内扫码」（[com.mln.tongji_canvas.ui.BatchSignScreen]）都用它，
 * 避免出现两套行为不一致的相机代码。
 *
 * 契约：
 * - 权限未授予时**自动申请一次**，并在原位显示 [CameraPermissionRationale] 占位（不渲染相机）；
 * - 识别到非空条码后回调 [onDetected]；是否停止扫描由调用方决定 ——
 *   把本组件从组合里移除即关闭相机、释放资源；
 * - 相机/手电异常通过 [onError] 上报（不抛出，调用方可只记日志）；
 * - 离开组合时关闭 ML Kit client、解绑相机、关手电，不会留下占用摄像头的后台会话。
 *
 * @param modifier 尺寸由调用方决定：全屏用 `fillMaxSize()`，页内嵌用固定高度
 * @param onDetected 识别到条码内容（已保证非空白）
 * @param onError 相机/手电异常（消息；调用方自行决定展示或记日志）
 * @param torchEnabled 是否开启手电（状态由调用方持有，便于同步按钮文案）
 * @param hapticFeedback 识别成功时是否震动反馈（默认开）
 * @param cameraKey 相机会话的「身份」：值变化时重建相机会话。
 *   需要在同一位置「关掉再打开」相机时（页内扫码反复开启、组件进出 LazyColumn 等），
 *   改变这个 key 可确保旧会话被彻底释放、新会话干净可用，不会出现黑屏的僵尸预览。
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QrCameraPreview(
    modifier: Modifier = Modifier,
    onDetected: (String) -> Unit,
    onError: (String?) -> Unit = {},
    torchEnabled: Boolean = false,
    hapticFeedback: Boolean = true,
    cameraKey: Int = 0
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val permissionState = rememberPermissionState(Manifest.permission.CAMERA)

    // 相机控制器随 cameraKey 重建：旧实例在下面的 DisposableEffect 里已解绑释放
    val cameraController = remember(cameraKey, context) {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
    }

    val barcodeScanner = remember(cameraKey) {
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

    DisposableEffect(barcodeScanner) {
        onDispose { barcodeScanner.close() }
    }

    DisposableEffect(lifecycleOwner, cameraController) {
        cameraController.bindToLifecycle(lifecycleOwner)
        onDispose {
            // 先关手电再解绑：解绑之后 torchEnabled 的 LaunchedEffect 已无法再操作相机
            runCatching { cameraController.enableTorch(false) }
            cameraController.unbind()
        }
    }

    LaunchedEffect(permissionState.status, cameraController) {
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
                        if (hapticFeedback) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        onDetected(value)
                    }
                }
            )
        } else {
            permissionState.launchPermissionRequest()
        }
    }

    LaunchedEffect(torchEnabled, cameraController, permissionState.status) {
        if (permissionState.status is PermissionStatus.Granted) {
            try {
                cameraController.enableTorch(torchEnabled)
            } catch (e: Exception) {
                // 部分机型/场景不支持手电：只上报，不让它中断整个扫码流程
                onError("手电切换失败：${e.message}")
            }
        }
    }

    if (permissionState.status !is PermissionStatus.Granted) {
        CameraPermissionRationale(
            modifier = modifier,
            onRequest = { permissionState.launchPermissionRequest() }
        )
        return
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                controller = cameraController
            }
        },
        modifier = modifier
    )
}

/**
 * 相机权限未授予时的占位面板（页内/全屏共用同一份文案）。
 *
 * @param modifier 与 [QrCameraPreview] 相同的尺寸约束，保证「换掉的是内容而不是布局」
 */
@Composable
fun CameraPermissionRationale(
    modifier: Modifier = Modifier,
    onRequest: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
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
