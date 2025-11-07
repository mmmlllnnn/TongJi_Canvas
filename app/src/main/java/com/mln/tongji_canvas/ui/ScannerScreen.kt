package com.mln.tongji_canvas.ui

import android.Manifest
import android.annotation.SuppressLint
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay

@OptIn(ExperimentalPermissionsApi::class)
@Composable
@SuppressLint("UnsafeOptInUsageError")
fun ScannerScreen(onDetected: (String) -> Unit) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val lifecycleOwner = LocalLifecycleOwner.current
    val isScanning = remember { mutableStateOf(true) }
    val scanStatus = remember { mutableStateOf("正在扫描...") }

    LaunchedEffect(Unit) {
        if (cameraPermissionState.status !is PermissionStatus.Granted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    if (cameraPermissionState.status !is PermissionStatus.Granted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("需要相机权限进行扫码", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { cameraPermissionState.launchPermissionRequest() }
            ) {
                Text("请求相机权限")
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { context ->
            val previewView = PreviewView(context)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    println("相机提供者获取成功")
                    
                    // 配置预览
                    val preview = Preview.Builder()
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    println("预览配置完成")
                    
                    // 选择后置摄像头
                    val selector = CameraSelector.DEFAULT_BACK_CAMERA

                    // 配置图像分析 - 使用最保守的设置
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    println("图像分析配置完成")

                val executor = Executors.newSingleThreadExecutor()
                
                // 配置条码扫描器 - 使用最简单的配置
                val barcodeScanner = try {
                    println("开始初始化条码扫描器")
                    val scanner = BarcodeScanning.getClient()
                    println("条码扫描器初始化成功")
                    scanner
                } catch (e: Exception) {
                    println("条码扫描器初始化失败: ${e.message}")
                    e.printStackTrace()
                    throw e
                }

                var lastScanTime = 0L
                val scanInterval = 300L // 扫描间隔300ms，提高扫描频率
                var consecutiveFailures = 0
                val maxFailures = 10 // 连续失败次数阈值

                analysis.setAnalyzer(executor) { imageProxy ->
                    try {
                        val currentTime = System.currentTimeMillis()
                        
                        // 控制扫描频率
                        if (currentTime - lastScanTime < scanInterval) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        lastScanTime = currentTime

                        val mediaImage = imageProxy.image
                        if (mediaImage != null && isScanning.value) {
                            val image = InputImage.fromMediaImage(
                                mediaImage, 
                                imageProxy.imageInfo.rotationDegrees
                            )
                            
                            barcodeScanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    try {
                                        if (isScanning.value) {
                                            val match = barcodes.firstOrNull { 
                                                it.valueType == Barcode.TYPE_TEXT || 
                                                it.valueType == Barcode.TYPE_URL 
                                            }
                                            val text = match?.rawValue
                                            if (!text.isNullOrBlank()) {
                                                consecutiveFailures = 0 // 重置失败计数
                                                scanStatus.value = "扫描成功！"
                                                isScanning.value = false
                                                onDetected(text)
                                            } else {
                                                consecutiveFailures++
                                                scanStatus.value = "正在扫描... (${consecutiveFailures}/${maxFailures})"
                                                // 如果连续失败太多次，降低扫描频率
                                                if (consecutiveFailures > maxFailures) {
                                                    consecutiveFailures = 0
                                                    scanStatus.value = "扫描中，请调整距离..."
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        println("处理扫描结果时出错: ${e.message}")
                                    }
                                }
                                .addOnFailureListener { exception ->
                                    consecutiveFailures++
                                    println("扫描失败: ${exception.message}")
                                    scanStatus.value = "扫描出错: ${exception.message}"
                                }
                                .addOnCompleteListener { 
                                    imageProxy.close() 
                                }
                        } else {
                            imageProxy.close()
                        }
                    } catch (e: Exception) {
                        println("图像分析时出错: ${e.message}")
                        imageProxy.close()
                    }
                }

                    // 绑定相机
                    println("开始绑定相机到生命周期")
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner, 
                        selector, 
                        preview, 
                        analysis
                    )
                    println("相机绑定成功")

                    // 设置相机控制
                    try {
                        // 启用连续自动对焦
                        camera.cameraControl.setLinearZoom(0.5f) // 设置合适的缩放级别
                        println("相机控制设置成功")
                    } catch (e: Exception) {
                        println("设置相机控制时出错: ${e.message}")
                    }

                } catch (e: Exception) {
                    println("相机初始化失败: ${e.message}")
                    e.printStackTrace()
                    scanStatus.value = "相机初始化失败: ${e.message}"
                }

            }, ContextCompat.getMainExecutor(context))
            previewView
        }, modifier = Modifier.fillMaxSize())

        // 扫描状态指示器
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Card(
                modifier = Modifier.padding(16.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isScanning.value) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        text = scanStatus.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isScanning.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 扫描区域指示器
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // 扫描框
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
                // 四个角的指示器
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color.Transparent)
                        .border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(2.dp)
                        )
                        .align(Alignment.TopStart)
                )
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color.Transparent)
                        .border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(2.dp)
                        )
                        .align(Alignment.TopEnd)
                )
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color.Transparent)
                        .border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(2.dp)
                        )
                        .align(Alignment.BottomStart)
                )
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color.Transparent)
                        .border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(2.dp)
                        )
                        .align(Alignment.BottomEnd)
                )
            }
        }
        
        // 底部提示文字和控制按钮
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                modifier = Modifier.padding(16.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "将二维码对准扫描框\n保持适当距离，确保二维码清晰可见",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                isScanning.value = true
                                scanStatus.value = "正在扫描..."
                            }
                        ) {
                            Text("重新扫描")
                        }
                        
                        Button(
                            onClick = {
                                // 这里可以添加手动输入URL的功能
                                // 暂时不做实现
                            }
                        ) {
                            Text("手动输入")
                        }
                    }
                }
            }
        }
    }
}


