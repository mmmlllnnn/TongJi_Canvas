package com.mln.tongji_canvas.ui

import android.Manifest
import android.annotation.SuppressLint
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
@SuppressLint("UnsafeOptInUsageError")
fun SimpleScannerScreen(onDetected: (String) -> Unit) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val lifecycleOwner = LocalLifecycleOwner.current
    val debugInfo = remember { mutableStateOf("初始化中...") }
    
    // 缩放状态
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    
    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        val newOffsetX = (offsetX + offsetChange.x).coerceIn(-1000f, 1000f)
        val newOffsetY = (offsetY + offsetChange.y).coerceIn(-1000f, 1000f)
        
        scale = newScale
        offsetX = newOffsetX
        offsetY = newOffsetY
        
        // 更新调试信息
        debugInfo.value = "缩放: ${String.format("%.1f", scale)}x"
    }

    LaunchedEffect(Unit) {
        println("SimpleScannerScreen: 开始初始化")
        debugInfo.value = "请求相机权限..."
        if (cameraPermissionState.status !is PermissionStatus.Granted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    if (cameraPermissionState.status !is PermissionStatus.Granted) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text("需要相机权限进行扫码\n状态: ${debugInfo.value}", style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
            println("SimpleScannerScreen: 创建AndroidView")
            debugInfo.value = "创建相机视图..."
            val previewView = PreviewView(context)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    println("SimpleScannerScreen: 开始相机初始化")
                    debugInfo.value = "初始化相机..."
                    val cameraProvider = cameraProviderFuture.get()
                    println("SimpleScannerScreen: 相机提供者获取成功")
                    
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    println("SimpleScannerScreen: 预览配置完成")
                    
                    val selector = CameraSelector.DEFAULT_BACK_CAMERA
                    val analysis = ImageAnalysis.Builder().build()
                    println("SimpleScannerScreen: 图像分析配置完成")
                    
                    val executor = Executors.newSingleThreadExecutor()
                    val barcodeScanner = BarcodeScanning.getClient()
                    println("SimpleScannerScreen: 条码扫描器初始化完成")
                    
                    var handled = false
                    
                    analysis.setAnalyzer(executor) { imageProxy ->
                        try {
                            val mediaImage = imageProxy.image
                            if (mediaImage != null && !handled) {
                                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                barcodeScanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        val match = barcodes.firstOrNull { it.valueType == Barcode.TYPE_TEXT || it.valueType == Barcode.TYPE_URL }
                                        val text = match?.rawValue
                                        if (!text.isNullOrBlank()) {
                                            handled = true
                                            previewView.post {
                                                debugInfo.value = "扫描成功: $text"
                                            }
                                            onDetected(text)
                                        } else {
                                            // 更新扫描状态，让用户知道扫描器在工作
                                            previewView.post {
                                                if (debugInfo.value == "相机启动成功，请扫描二维码") {
                                                    debugInfo.value = "正在扫描中..."
                                                }
                                            }
                                        }
                                    }
                                    .addOnFailureListener { exception ->
                                        println("SimpleScannerScreen: 扫描失败: ${exception.message}")
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        } catch (e: Exception) {
                            println("SimpleScannerScreen: 图像分析出错: ${e.message}")
                            imageProxy.close()
                        }
                    }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                    println("SimpleScannerScreen: 相机绑定完成")
                    
                    // 使用post确保UI更新在正确的线程上
                    previewView.post {
                        debugInfo.value = "相机启动成功，请扫描二维码"
                    }
                } catch (e: Exception) {
                    println("SimpleScannerScreen: 初始化失败: ${e.message}")
                    e.printStackTrace()
                    debugInfo.value = "初始化失败: ${e.message}"
                }
            }, ContextCompat.getMainExecutor(context))
            previewView
        }, 
        modifier = Modifier
            .fillMaxSize()
            .transformable(state = transformableState)
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
        )
        
        // 扫描框指示器
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clip(RoundedCornerShape(16.dp))
            ) {
                // 在扫描框内显示缩放比例
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.TopEnd
                ) {
                    Text(
                        text = "${String.format("%.1f", scale)}x",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
        
        // 调试信息显示
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = debugInfo.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (scale > 1f) {
                    Text(
                        text = "双指缩放已启用，可放大扫描远距离二维码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
        
        // 缩放控制UI
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                ) {
                    Text("重置视图")
                }
                
                Button(
                    onClick = {
                        scale = (scale * 1.5f).coerceIn(1f, 5f)
                    }
                ) {
                    Text("放大")
                }
                
                OutlinedButton(
                    onClick = {
                        scale = (scale / 1.5f).coerceIn(1f, 5f)
                    }
                ) {
                    Text("缩小")
                }
            }
        }
        
        // 缩放比例显示
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Text(
                text = "缩放: ${String.format("%.1f", scale)}x",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
