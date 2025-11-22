package com.mln.tongji_canvas.ui.qr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class QrScannerController(
    private val onDetected: (String) -> Unit
) {
    var lastResult: String? by mutableStateOf(null)
        private set
    var isPaused: Boolean by mutableStateOf(false)
        private set
    var errorMessage: String? by mutableStateOf(null)
        private set

    fun handleDetected(result: String) {
        if (isPaused) return
        lastResult = result
        onDetected(result)
        isPaused = true
    }

    fun simulate(result: String) {
        resume()
        handleDetected(result)
    }

    fun reportError(message: String?) {
        errorMessage = message
    }

    fun resume() {
        isPaused = false
    }

    fun pause() {
        isPaused = true
    }
}

@Composable
fun rememberQrScannerController(
    onDetected: (String) -> Unit
): QrScannerController {
    val controller = remember { QrScannerController(onDetected) }
    LaunchedEffect(onDetected) {
        controller.resume()
    }
    return controller
}

