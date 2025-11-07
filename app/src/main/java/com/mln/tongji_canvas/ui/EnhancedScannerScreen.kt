package com.mln.tongji_canvas.ui

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
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
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.Result
import java.util.EnumMap

@OptIn(ExperimentalPermissionsApi::class)
@Composable
@SuppressLint("UnsafeOptInUsageError")
fun EnhancedScannerScreen(onDetected: (String) -> Unit) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val lifecycleOwner = LocalLifecycleOwner.current
    val debugInfo = remember { mutableStateOf("初始化中...") }
    val recognitionEngine = remember { mutableStateOf("ML Kit") }
    val scanCount = remember { mutableStateOf(0) }
    val lastScale = remember { mutableFloatStateOf(1f) }
    val cameraControl = remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    val cameraInfo = remember { mutableStateOf<androidx.camera.core.CameraInfo?>(null) }
    val currentZoomRatio = remember { mutableFloatStateOf(1f) }
    val maxZoomRatio = remember { mutableFloatStateOf(1f) }
    
    // 缩放状态
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    
    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        // 计算新的变焦比例
        val newZoomRatio = (currentZoomRatio.value * zoomChange).coerceIn(1f, maxZoomRatio.value)
        val newOffsetX = (offsetX + offsetChange.x).coerceIn(-1000f, 1000f)
        val newOffsetY = (offsetY + offsetChange.y).coerceIn(-1000f, 1000f)
        
        // 更新相机变焦
        cameraControl.value?.let { control ->
            try {
                control.setZoomRatio(newZoomRatio)
                currentZoomRatio.value = newZoomRatio
                println("相机变焦: ${String.format("%.1f", newZoomRatio)}x")
            } catch (e: Exception) {
                println("相机变焦失败: ${e.message}")
            }
        }
        
        // 更新UI缩放（用于预览效果）
        scale = newZoomRatio
        offsetX = newOffsetX
        offsetY = newOffsetY
        
        debugInfo.value = "变焦: ${String.format("%.1f", newZoomRatio)}x | 引擎: ${recognitionEngine.value}"
    }
    
    // 监听变焦变化，触发自动对焦
    LaunchedEffect(currentZoomRatio.value) {
        if (kotlin.math.abs(currentZoomRatio.value - lastScale.value) > 0.1f) {
            lastScale.value = currentZoomRatio.value
            println("变焦变化: ${String.format("%.1f", currentZoomRatio.value)}x")
            
            // 延迟一点时间后触发对焦
            kotlinx.coroutines.delay(300)
            cameraControl.value?.let { control ->
                try {
                    // 取消之前的对焦，让相机重新对焦
                    control.cancelFocusAndMetering()
                    println("变焦后触发对焦: ${String.format("%.1f", currentZoomRatio.value)}x")
                    debugInfo.value = "变焦: ${String.format("%.1f", currentZoomRatio.value)}x | 对焦中..."
                } catch (e: Exception) {
                    println("变焦后对焦失败: ${e.message}")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        println("EnhancedScannerScreen: 开始初始化")
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
                println("EnhancedScannerScreen: 创建AndroidView")
                debugInfo.value = "创建相机视图..."
                val previewView = PreviewView(context)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    try {
                        println("EnhancedScannerScreen: 开始相机初始化")
                        debugInfo.value = "初始化相机..."
                        val cameraProvider = cameraProviderFuture.get()
                        println("EnhancedScannerScreen: 相机提供者获取成功")
                        
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        println("EnhancedScannerScreen: 预览配置完成")
                        
                        val selector = CameraSelector.DEFAULT_BACK_CAMERA

                        // 配置图像分析 - 使用更高分辨率
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        println("EnhancedScannerScreen: 图像分析配置完成")
                        
                        val executor = Executors.newSingleThreadExecutor()
                        
                        // 初始化ML Kit扫描器
                        val mlKitScanner = BarcodeScanning.getClient()
                        println("EnhancedScannerScreen: ML Kit扫描器初始化完成")
                        
                        // 初始化ZXing扫描器
                        val zxingReader = MultiFormatReader()
                        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
                        hints[DecodeHintType.TRY_HARDER] = true
                        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(
                            com.google.zxing.BarcodeFormat.QR_CODE,
                            com.google.zxing.BarcodeFormat.AZTEC,
                            com.google.zxing.BarcodeFormat.DATA_MATRIX,
                            com.google.zxing.BarcodeFormat.PDF_417
                        )
                        zxingReader.setHints(hints)
                        println("EnhancedScannerScreen: ZXing扫描器初始化完成")
                        
                        var handled = false
                        var lastScanTime = 0L
                        var consecutiveFailures = 0
                        var adaptiveScanInterval = 200L
                        
                        analysis.setAnalyzer(executor) { imageProxy ->
                            try {
                                val currentTime = System.currentTimeMillis()
                                
                                // 自适应扫描频率控制
                                if (currentTime - lastScanTime < adaptiveScanInterval) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                lastScanTime = currentTime
                                
                                // 根据失败次数调整扫描频率
                                adaptiveScanInterval = when {
                                    consecutiveFailures < 3 -> 200L
                                    consecutiveFailures < 6 -> 400L
                                    else -> 600L
                                }
                                
                                val mediaImage = imageProxy.image
                                if (mediaImage != null && !handled) {
                                    scanCount.value++
                                    
                                    // 首先尝试ML Kit
                                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                    
                                    mlKitScanner.process(image)
                                        .addOnSuccessListener { barcodes ->
                                            if (!handled) {
                                                val match = barcodes.firstOrNull { 
                                                    it.valueType == Barcode.TYPE_TEXT || 
                                                    it.valueType == Barcode.TYPE_URL 
                                                }
                                                val text = match?.rawValue
                                                if (!text.isNullOrBlank()) {
                                                    handled = true
                                                    consecutiveFailures = 0
                                                    adaptiveScanInterval = 200L
                                                    recognitionEngine.value = "ML Kit"
                                                    previewView.post {
                                                        debugInfo.value = "ML Kit识别成功: $text"
                                                    }
                                                    onDetected(text)
                                                } else {
                                                    consecutiveFailures++
                                                    // ML Kit失败，尝试ZXing
                                                    tryZXingRecognition(mediaImage, zxingReader, previewView, debugInfo, onDetected, recognitionEngine, handled)
                                                }
                                            }
                                        }
                                        .addOnFailureListener { exception ->
                                            println("ML Kit扫描失败: ${exception.message}")
                                            consecutiveFailures++
                                            // ML Kit失败，尝试ZXing
                                            tryZXingRecognition(mediaImage, zxingReader, previewView, debugInfo, onDetected, recognitionEngine, handled)
                                        }
                                        .addOnCompleteListener { 
                                            imageProxy.close() 
                                        }
                                } else {
                                    imageProxy.close()
                                }
                            } catch (e: Exception) {
                                println("EnhancedScannerScreen: 图像分析出错: ${e.message}")
                                imageProxy.close()
                            }
                        }

                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                        cameraControl.value = camera.cameraControl
                        cameraInfo.value = camera.cameraInfo
                        
                        // 获取最大变焦比例
                        val zoomState = camera.cameraInfo.zoomState.value
                        maxZoomRatio.value = zoomState?.maxZoomRatio ?: 1f
                        currentZoomRatio.value = zoomState?.zoomRatio ?: 1f
                        
                        println("EnhancedScannerScreen: 相机绑定完成，最大变焦: ${String.format("%.1f", maxZoomRatio.value)}x")
                        
                        previewView.post {
                            debugInfo.value = "相机启动成功，请扫描二维码"
                        }
                    } catch (e: Exception) {
                        println("EnhancedScannerScreen: 初始化失败: ${e.message}")
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
        
        // 扫描线动画（全屏）
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            ScanLineAnimation(
                modifier = Modifier.fillMaxSize()
            )
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
                Text(
                    text = "变焦: ${String.format("%.1f", currentZoomRatio.value)}x | 引擎: ${recognitionEngine.value}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        
        // 扫描统计信息
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Text(
                text = "扫描次数: ${scanCount.value}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        
        // 变焦控制按钮
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 放大按钮
                androidx.compose.material3.FloatingActionButton(
                    onClick = {
                        cameraControl.value?.let { control ->
                            try {
                                val newZoom = (currentZoomRatio.value * 1.2f).coerceAtMost(maxZoomRatio.value)
                                control.setZoomRatio(newZoom)
                                currentZoomRatio.value = newZoom
                                scale = newZoom
                                debugInfo.value = "变焦: ${String.format("%.1f", newZoom)}x"
                                println("手动放大: ${String.format("%.1f", newZoom)}x")
                            } catch (e: Exception) {
                                println("放大失败: ${e.message}")
                            }
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Text("+", style = MaterialTheme.typography.titleLarge)
                }
                
                // 缩小按钮
                androidx.compose.material3.FloatingActionButton(
                    onClick = {
                        cameraControl.value?.let { control ->
                            try {
                                val newZoom = (currentZoomRatio.value / 1.2f).coerceAtLeast(1f)
                                control.setZoomRatio(newZoom)
                                currentZoomRatio.value = newZoom
                                scale = newZoom
                                debugInfo.value = "变焦: ${String.format("%.1f", newZoom)}x"
                                println("手动缩小: ${String.format("%.1f", newZoom)}x")
                            } catch (e: Exception) {
                                println("缩小失败: ${e.message}")
                            }
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Text("-", style = MaterialTheme.typography.titleLarge)
                }
                
                // 对焦按钮
                androidx.compose.material3.FloatingActionButton(
                    onClick = {
                        cameraControl.value?.let { control ->
                            try {
                                control.cancelFocusAndMetering()
                                debugInfo.value = "手动对焦中..."
                                println("手动触发对焦")
                            } catch (e: Exception) {
                                println("手动对焦失败: ${e.message}")
                            }
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    androidx.compose.material.icons.Icons.Filled.CenterFocusStrong
                }
            }
        }
        
    }
}

// ZXing识别函数
private fun tryZXingRecognition(
    mediaImage: android.media.Image,
    zxingReader: MultiFormatReader,
    previewView: PreviewView,
    debugInfo: androidx.compose.runtime.MutableState<String>,
    onDetected: (String) -> Unit,
    recognitionEngine: androidx.compose.runtime.MutableState<String>,
    handled: Boolean
) {
    try {
        // 将Image转换为Bitmap
        val bitmap = imageToBitmap(mediaImage)
        if (bitmap != null) {
            // 创建多个增强版本
            val enhancedVersions = createMultipleEnhancedVersions(bitmap)
            
            // 尝试每个版本
            for ((index, enhancedBitmap) in enhancedVersions.withIndex()) {
                if (handled) break
                
                try {
                    // 转换为ZXing格式
                    val width = enhancedBitmap.width
                    val height = enhancedBitmap.height
                    val pixels = IntArray(width * height)
                    enhancedBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    
                    val source = RGBLuminanceSource(width, height, pixels)
                    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                    
                    // 尝试识别
                    val result: Result? = try {
                        zxingReader.decode(binaryBitmap)
                    } catch (e: Exception) {
                        null
                    }
                    
                    if (result != null && !handled) {
                        val text = result.text
                        if (!text.isNullOrBlank()) {
                            recognitionEngine.value = "ZXing-${when(index) {
                                0 -> "原始"
                                1 -> "增强"
                                2 -> "灰度"
                                3 -> "反色"
                                else -> "其他"
                            }}"
                            previewView.post {
                                debugInfo.value = "ZXing识别成功: $text"
                            }
                            onDetected(text)
                            break
                        }
                    }
                } catch (e: Exception) {
                    println("ZXing版本${index}识别出错: ${e.message}")
                }
            }
        }
    } catch (e: Exception) {
        println("ZXing识别出错: ${e.message}")
    }
}

// 图像转Bitmap
private fun imageToBitmap(image: android.media.Image): Bitmap? {
    return try {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        
        if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        }
    } catch (e: Exception) {
        println("图像转换失败: ${e.message}")
        null
    }
}

// 图像增强处理
private fun enhanceImage(bitmap: Bitmap): Bitmap {
    val enhancedBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(enhancedBitmap)
    val paint = Paint()
    
    // 多种增强策略
    val colorMatrix = ColorMatrix()
    
    // 1. 对比度增强
    colorMatrix.setSaturation(1.3f)
    
    // 2. 亮度调整
    colorMatrix.setScale(1.2f, 1.2f, 1.2f, 1f)
    
    // 3. 锐化处理
    val sharpenMatrix = floatArrayOf(
        0f, -1f, 0f, 0f, 0f,
        -1f, 5f, -1f, 0f, 0f,
        0f, -1f, 0f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
    val sharpenColorMatrix = ColorMatrix(sharpenMatrix)
    colorMatrix.postConcat(sharpenColorMatrix)
    
    paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    
    return enhancedBitmap
}

// 创建多个增强版本的图像用于识别
private fun createMultipleEnhancedVersions(bitmap: Bitmap): List<Bitmap> {
    val versions = mutableListOf<Bitmap>()
    
    // 原始图像
    versions.add(bitmap)
    
    // 高对比度版本
    val highContrast = enhanceImage(bitmap)
    versions.add(highContrast)
    
    // 灰度版本
    val grayBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val grayCanvas = Canvas(grayBitmap)
    val grayPaint = Paint()
    val grayMatrix = ColorMatrix()
    grayMatrix.setSaturation(0f) // 去饱和度，转为灰度
    grayPaint.colorFilter = ColorMatrixColorFilter(grayMatrix)
    grayCanvas.drawBitmap(bitmap, 0f, 0f, grayPaint)
    versions.add(grayBitmap)
    
    // 反色版本（有时二维码是反色的）
    val invertedBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val invertedCanvas = Canvas(invertedBitmap)
    val invertedPaint = Paint()
    // 使用简化的反色矩阵
    val invertMatrix = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )
    val finalMatrix = ColorMatrix(invertMatrix)
    invertedPaint.colorFilter = ColorMatrixColorFilter(finalMatrix)
    invertedCanvas.drawBitmap(bitmap, 0f, 0f, invertedPaint)
    versions.add(invertedBitmap)
    
    return versions
}

// 扫描线动画组件
@Composable
private fun ScanLineAnimation(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan_line")
    val scanLinePosition by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan_line_position"
    )
    
    // 添加闪烁效果
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan_line_alpha"
    )
    
    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val lineY = canvasHeight * scanLinePosition
        
        // 绘制扫描线
        val scanLineColor = androidx.compose.ui.graphics.Color.Red.copy(alpha = alpha * 0.9f)
        val strokeWidth = 3.dp.toPx()
        
        // 主扫描线
        drawLine(
            color = scanLineColor,
            start = Offset(0f, lineY),
            end = Offset(canvasWidth, lineY),
            strokeWidth = strokeWidth
        )
        
        // 扫描线两端的光点
        val dotRadius = 6.dp.toPx()
        drawCircle(
            color = scanLineColor,
            radius = dotRadius,
            center = Offset(0f, lineY)
        )
        drawCircle(
            color = scanLineColor,
            radius = dotRadius,
            center = Offset(canvasWidth, lineY)
        )
        
        // 扫描线发光效果
        val glowColor = scanLineColor.copy(alpha = alpha * 0.4f)
        val glowWidth = strokeWidth * 2
        drawLine(
            color = glowColor,
            start = Offset(0f, lineY),
            end = Offset(canvasWidth, lineY),
            strokeWidth = glowWidth
        )
        
        // 扫描线阴影效果
        val shadowColor = scanLineColor.copy(alpha = alpha * 0.2f)
        drawLine(
            color = shadowColor,
            start = Offset(0f, lineY - strokeWidth),
            end = Offset(canvasWidth, lineY - strokeWidth),
            strokeWidth = strokeWidth * 0.5f
        )
        drawLine(
            color = shadowColor,
            start = Offset(0f, lineY + strokeWidth),
            end = Offset(canvasWidth, lineY + strokeWidth),
            strokeWidth = strokeWidth * 0.5f
        )
    }
}
