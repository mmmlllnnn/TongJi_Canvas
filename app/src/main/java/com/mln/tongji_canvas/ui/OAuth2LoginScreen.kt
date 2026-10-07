package com.mln.tongji_canvas.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mln.tongji_canvas.data.AppDiagnostics
import com.mln.tongji_canvas.data.CredentialImportFlow
import com.mln.tongji_canvas.data.DiagLevel
import com.mln.tongji_canvas.data.IMPORT_DIAG_TAG
import com.mln.tongji_canvas.data.ImportDecisionReason
import com.mln.tongji_canvas.data.ImportStage
import com.mln.tongji_canvas.data.ImportUiState
import com.mln.tongji_canvas.data.SessionRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OAuth2LoginScreen(
    repository: SessionRepository,
    @Suppress("UNUSED_PARAMETER") initialUrl: String,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val currentUrl: MutableState<String> = remember { mutableStateOf("") }
    val webViewRef: MutableState<WebView?> = remember { mutableStateOf(null) }
    val isLoginDetected: MutableState<Boolean> = remember { mutableStateOf(false) }
    val isMobileUA: MutableState<Boolean> = remember { mutableStateOf(true) } // 默认使用手机UA
    val importState = CredentialImportFlow.state.value

    // 直接访问Canvas登录页面，让它自动完成OAuth2.0重定向链
    val loginUrl = "https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=2333&rollCallToken=2333"

    // 获取User-Agent字符串
    fun getUserAgent(): String {
        return if (isMobileUA.value) {
            // 手机UA - 包含Mobile标识，模拟真实Android设备
            "Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        } else {
            // 电脑UA - 不包含Mobile标识，模拟Windows桌面Chrome
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
        }
    }

    // 获取屏幕尺寸模拟的JavaScript代码
    fun getScreenSimulationJS(): String {
        return if (isMobileUA.value) {
            // 手机屏幕尺寸
            """
            (function() {
                // 设置viewport meta标签
                var viewport = document.querySelector('meta[name="viewport"]');
                if (!viewport) {
                    viewport = document.createElement('meta');
                    viewport.name = 'viewport';
                    document.head.appendChild(viewport);
                }
                viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                
                // 模拟手机屏幕尺寸
                Object.defineProperty(screen, 'width', { value: 360, writable: false });
                Object.defineProperty(screen, 'height', { value: 640, writable: false });
                Object.defineProperty(screen, 'availWidth', { value: 360, writable: false });
                Object.defineProperty(screen, 'availHeight', { value: 640, writable: false });
                Object.defineProperty(window, 'innerWidth', { value: 360, writable: false });
                Object.defineProperty(window, 'innerHeight', { value: 640, writable: false });
                Object.defineProperty(window, 'outerWidth', { value: 360, writable: false });
                Object.defineProperty(window, 'outerHeight', { value: 640, writable: false });
                Object.defineProperty(document.documentElement, 'clientWidth', { value: 360, writable: false });
                Object.defineProperty(document.documentElement, 'clientHeight', { value: 640, writable: false });
                
                // 触发resize事件
                window.dispatchEvent(new Event('resize'));
            })();
            """
        } else {
            // 电脑屏幕尺寸 - 确保innerWidth > 900
            """
            (function() {
                // 设置viewport meta标签
                var viewport = document.querySelector('meta[name="viewport"]');
                if (!viewport) {
                    viewport = document.createElement('meta');
                    viewport.name = 'viewport';
                    document.head.appendChild(viewport);
                }
                viewport.content = 'width=1920, initial-scale=1.0';
                
                // 模拟电脑屏幕尺寸 - 确保宽度大于900
                Object.defineProperty(screen, 'width', { value: 1920, writable: false });
                Object.defineProperty(screen, 'height', { value: 1080, writable: false });
                Object.defineProperty(screen, 'availWidth', { value: 1920, writable: false });
                Object.defineProperty(screen, 'availHeight', { value: 1080, writable: false });
                Object.defineProperty(window, 'innerWidth', { value: 1920, writable: false });
                Object.defineProperty(window, 'innerHeight', { value: 1080, writable: false });
                Object.defineProperty(window, 'outerWidth', { value: 1920, writable: false });
                Object.defineProperty(window, 'outerHeight', { value: 1080, writable: false });
                Object.defineProperty(document.documentElement, 'clientWidth', { value: 1920, writable: false });
                Object.defineProperty(document.documentElement, 'clientHeight', { value: 1080, writable: false });
                
                // 强制覆盖检测函数
                var originalMatch = String.prototype.match;
                String.prototype.match = function(regex) {
                    if (this === navigator.userAgent.toLowerCase()) {
                        return null; // 返回null表示不是移动设备
                    }
                    return originalMatch.call(this, regex);
                };
                
                // 触发resize事件
                window.dispatchEvent(new Event('resize'));
            })();
            """
        }
    }

    // 获取检测覆盖的JavaScript代码
    fun getDetectionOverrideJS(): String {
        return if (isMobileUA.value) {
            // 手机模式 - 确保检测为移动设备
            """
            (function() {
                // 覆盖检测逻辑，确保被识别为移动设备
                var originalMatch = String.prototype.match;
                String.prototype.match = function(regex) {
                    if (this === navigator.userAgent.toLowerCase()) {
                        return ['android']; // 返回匹配结果表示是移动设备
                    }
                    return originalMatch.call(this, regex);
                };
            })();
            """
        } else {
            // 电脑模式 - 确保检测为桌面设备
            """
            (function() {
                // 覆盖检测逻辑，确保不被识别为移动设备
                var originalMatch = String.prototype.match;
                String.prototype.match = function(regex) {
                    if (this === navigator.userAgent.toLowerCase()) {
                        return null; // 返回null表示不是移动设备
                    }
                    return originalMatch.call(this, regex);
                };
                
                // 确保window.innerWidth > 900
                Object.defineProperty(window, 'innerWidth', { 
                    value: 1920, 
                    writable: false,
                    configurable: false
                });
            })();
            """
        }
    }

    // 获取早期覆盖的JavaScript代码（在页面开始加载时注入）
    fun getEarlyOverrideJS(): String {
        return if (isMobileUA.value) {
            // 手机模式
            """
            (function() {
                console.log('AndroidOverride: 开始手机模式覆盖');
                
                // 立即覆盖检测逻辑
                var originalMatch = String.prototype.match;
                String.prototype.match = function(regex) {
                    if (this === navigator.userAgent.toLowerCase()) {
                        console.log('AndroidOverride: 手机模式UA匹配');
                        return ['android']; // 返回匹配结果表示是移动设备
                    }
                    return originalMatch.call(this, regex);
                };
                
                // 设置移动设备屏幕尺寸
                try {
                    Object.defineProperty(window, 'innerWidth', { value: 360, writable: false, configurable: false });
                    Object.defineProperty(window, 'innerHeight', { value: 640, writable: false, configurable: false });
                    console.log('AndroidOverride: 设置手机屏幕尺寸 360x640');
                } catch(e) {
                    console.log('AndroidOverride: 设置屏幕尺寸失败', e);
                }
                
                // 通知Android端
                if (typeof AndroidOverride !== 'undefined') {
                    AndroidOverride.forceOverride();
                }
            })();
            """
        } else {
            // 电脑模式
            """
            (function() {
                console.log('AndroidOverride: 开始电脑模式覆盖');
                
                // 立即覆盖检测逻辑
                var originalMatch = String.prototype.match;
                String.prototype.match = function(regex) {
                    if (this === navigator.userAgent.toLowerCase()) {
                        console.log('AndroidOverride: 电脑模式UA匹配，返回null');
                        return null; // 返回null表示不是移动设备
                    }
                    return originalMatch.call(this, regex);
                };
                
                // 设置桌面设备屏幕尺寸，确保innerWidth > 900
                try {
                    Object.defineProperty(window, 'innerWidth', { value: 1920, writable: false, configurable: false });
                    Object.defineProperty(window, 'innerHeight', { value: 1080, writable: false, configurable: false });
                    console.log('AndroidOverride: 设置电脑屏幕尺寸 1920x1080');
                } catch(e) {
                    console.log('AndroidOverride: 设置屏幕尺寸失败', e);
                }
                
                // 通知Android端
                if (typeof AndroidOverride !== 'undefined') {
                    AndroidOverride.forceOverride();
                }
            })();
            """
        }
    }

    // 初始化WebView并清除残留数据
    fun initializeWebView() {
        webViewRef.value?.let { webView ->
            println("清除WebView残留数据...")
            // 清除cookies
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()

            // 清除WebView缓存
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()

            // 重置状态
            isLoginDetected.value = false

            // 设置User-Agent
            webView.settings.userAgentString = getUserAgent()
            println("设置User-Agent: ${getUserAgent()}")

            // 强制刷新缓存以确保UA生效
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE

            // 加载登录URL
            webView.loadUrl(loginUrl)
            println("WebView初始化完成，加载URL: $loginUrl")
        }
    }

    /**
     * 检测到登录成功：交给 [CredentialImportFlow] 读取 Cookies → 验证 → 保存。
     * 没有固定倒计时；读取与验证都在应用级作用域里进行，用户返回/旋转不会丢凭据。
     */
    fun handleLoginDetected() {
        if (isLoginDetected.value) return
        isLoginDetected.value = true
        // 只记「发生了什么」；Cookie 本体由 CredentialImportFlow 读取，
        // 诊断日志里永远只有不可逆的凭据指纹（见 logCredentialImport）
        AppDiagnostics.log(
            DiagLevel.INFO,
            IMPORT_DIAG_TAG,
            "登录页检测到登录成功，开始导入凭据",
            "UA 模式=${if (isMobileUA.value) "手机" else "电脑"}"
        )
        CredentialImportFlow.begin(repository)
    }

    /** 用户选择「重新登录」：丢弃本次凭据并重新加载登录页。 */
    fun restartLogin() {
        AppDiagnostics.log(DiagLevel.INFO, IMPORT_DIAG_TAG, "用户选择重新登录，已丢弃本次凭据")
        CredentialImportFlow.discard()
        isLoginDetected.value = false
        initializeWebView()
    }

    fun applyUserAgentMode(targetMobile: Boolean) {
        if (isMobileUA.value == targetMobile) return
        isMobileUA.value = targetMobile
        AppDiagnostics.log(
            DiagLevel.INFO,
            IMPORT_DIAG_TAG,
            "切换浏览器模式并重新登录",
            "UA 模式=${if (targetMobile) "手机" else "电脑"}"
        )
        webViewRef.value?.let { webView ->
            webView.clearCache(true)
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()

            webView.settings.userAgentString = getUserAgent()
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            println("切换User-Agent: ${getUserAgent()}")

            isLoginDetected.value = false
            CredentialImportFlow.discard()

            webView.loadUrl(loginUrl)
            println("重新加载URL: $loginUrl")

            webView.postDelayed({
                val earlyOverrideJS = getEarlyOverrideJS()
                webView.evaluateJavascript(earlyOverrideJS, null)
                println("切换后立即注入覆盖JS: ${if (isMobileUA.value) "手机模式" else "电脑模式"}")
            }, 50)
        }
    }

    // 保存完成后提示并返回上一页（旋转/重建后依然会执行，不会静默丢弃结果）
    LaunchedEffect(importState) {
        val saved = importState as? ImportUiState.Saved ?: return@LaunchedEffect
        val message = if (saved.verified) {
            "已验证并保存账号：${saved.displayName}"
        } else {
            "已保存账号（未验证）：${saved.displayName}"
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        CredentialImportFlow.acknowledge()
        onSaved()
    }

    val statusMessage: String = when {
        importState is ImportUiState.Verifying -> when (importState.stage) {
            ImportStage.READING_COOKIE -> "已捕获登录，正在读取 Cookies…"
            ImportStage.PROBING -> "验证中… 正在向 Canvas 核实身份并获取真实姓名/学号（最多约 8 秒）。"
            ImportStage.SAVING -> "正在保存账号…"
        }
        importState is ImportUiState.Decision -> when (importState.reason) {
            ImportDecisionReason.TIMED_OUT -> "验证超时，等待你确认是否仍要保存。"
            ImportDecisionReason.EXPIRED -> "凭据已失效，需要重新登录或选择仍要保存。"
            ImportDecisionReason.NETWORK_ERROR -> "网络异常，未能完成验证，等待你确认是否仍要保存。"
            ImportDecisionReason.UNKNOWN -> "验证结果无法识别，等待你确认是否仍要保存。"
            ImportDecisionReason.NO_COOKIE -> "未捕获到 Cookies，请重试。"
        }
        importState is ImportUiState.Saved ->
            if (importState.verified) "验证通过：${importState.displayName}" else "已保存（未验证）：${importState.displayName}"
        isLoginDetected.value -> "已捕获登录，正在准备验证…"
        else -> "请在下方统一认证页面完成登录，系统会自动捕获 Cookies。"
    }

    val statusChipText: String = when {
        importState is ImportUiState.Verifying -> "验证中…"
        importState is ImportUiState.Decision -> "需要确认"
        importState is ImportUiState.Saved -> "已保存"
        isLoginDetected.value -> "已捕获登录"
        else -> "等待登录成功"
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.LargeTopAppBar(
                title = {
                    Column {
                        Text("统一认证登录", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = currentUrl.value.ifEmpty { loginUrl },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("操作提示", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1. 使用统一认证账号登录 Canvas，系统会自动捕获 Cookies 并验证身份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "2. 若页面显示异常，可切换下方浏览器模式以匹配移动或桌面站点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = isMobileUA.value,
                    onClick = { applyUserAgentMode(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Outlined.PhoneIphone, contentDescription = null) }
                ) {
                    Text("移动端")
                }
                SegmentedButton(
                    selected = !isMobileUA.value,
                    onClick = { applyUserAgentMode(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { Icon(Icons.Outlined.Computer, contentDescription = null) }
                ) {
                    Text("桌面端")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(
                            text = if (currentUrl.value.isBlank()) "等待加载页面..." else "地址已加载",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) }
                )
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(statusChipText) },
                    leadingIcon = {
                        Icon(
                            if (isLoginDetected.value) Icons.Outlined.Verified else Icons.Outlined.Schedule,
                            contentDescription = null
                        )
                    }
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 500.dp),
                shape = RoundedCornerShape(32.dp),
                tonalElevation = 4.dp
            ) {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewRef.value = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // 登录页只加载 https 子资源（已实测 iam/canvas 页面 http:// 子资源为 0），
                                // 因此禁止混合内容：避免中间人在 SSO 页面内注入脚本窃取会话。
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                settings.loadsImagesAutomatically = true
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.setSupportMultipleWindows(true)
                                settings.userAgentString = getUserAgent()
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                addJavascriptInterface(object {
                                    @android.webkit.JavascriptInterface
                                    fun forceOverride() {}
                                }, "AndroidOverride")
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        if (url != null) currentUrl.value = url
                                        view?.let { webView ->
                                            val earlyOverrideJS = getEarlyOverrideJS()
                                            webView.evaluateJavascript(earlyOverrideJS, null)
                                            webView.postDelayed({
                                                webView.evaluateJavascript(earlyOverrideJS, null)
                                            }, 100)
                                        }
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                        if (url == null) return false
                                        currentUrl.value = url
                                        if (url.startsWith("https://canvas.tongji.edu.cn/") &&
                                            !url.contains("login") &&
                                            !url.contains("oauth2") &&
                                            !url.contains("openid_connect")
                                        ) {
                                            println("shouldOverrideUrlLoading检测到Canvas登录成功: $url")
                                            handleLoginDetected()
                                        }
                                        return !(url.startsWith("http://") || url.startsWith("https://"))
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val u = request?.url?.toString() ?: return false
                                        currentUrl.value = u
                                        return !(u.startsWith("http://") || u.startsWith("https://"))
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        if (url != null) currentUrl.value = url
                                        view?.let { webView ->
                                            val screenSimulationJS = getScreenSimulationJS()
                                            webView.evaluateJavascript(screenSimulationJS, null)
                                            val detectionOverrideJS = getDetectionOverrideJS()
                                            val earlyOverrideJS = getEarlyOverrideJS()
                                            webView.evaluateJavascript(detectionOverrideJS, null)
                                            webView.evaluateJavascript(earlyOverrideJS, null)
                                            webView.postDelayed({
                                                webView.evaluateJavascript(earlyOverrideJS, null)
                                                webView.evaluateJavascript(detectionOverrideJS, null)
                                            }, 500)
                                        }
                                        if (url?.startsWith("https://canvas.tongji.edu.cn/") == true &&
                                            !url.contains("login") &&
                                            !url.contains("oauth2") &&
                                            !url.contains("openid_connect")
                                        ) {
                                            println("onPageFinished检测到Canvas登录成功: $url")
                                            handleLoginDetected()
                                        }
                                    }
                                }
                                initializeWebView()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (!isLoginDetected.value || importState is ImportUiState.Verifying) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                        )
                    }
                }
            }

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (isLoginDetected.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (importState is ImportUiState.Decision) {
        ImportDecisionDialog(
            state = importState,
            onSaveAnyway = { CredentialImportFlow.commitUnverified(repository) },
            onRetry = { restartLogin() },
            onDiscard = {
                CredentialImportFlow.discard()
                // 允许用户继续操作后再次触发捕获与验证
                isLoginDetected.value = false
            }
        )
    }
}

@Composable
private fun ImportDecisionDialog(
    state: ImportUiState.Decision,
    onSaveAnyway: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit
) {
    val needsCookie = state.reason == ImportDecisionReason.NO_COOKIE
    val title = when (state.reason) {
        ImportDecisionReason.TIMED_OUT -> "验证超时"
        ImportDecisionReason.EXPIRED -> "凭据已失效"
        ImportDecisionReason.NETWORK_ERROR -> "无法完成验证"
        ImportDecisionReason.UNKNOWN -> "无法完成验证"
        ImportDecisionReason.NO_COOKIE -> "未捕获到 Cookies"
    }
    val message = when (state.reason) {
        ImportDecisionReason.TIMED_OUT ->
            "验证超时，未能确认凭据是否有效。是否仍要保存该账号？保存后会标记为「未验证」。"
        ImportDecisionReason.EXPIRED ->
            "服务器要求重新登录，当前凭据已失效。是否仍要保存？保存后会标记为「未验证」。"
        ImportDecisionReason.NETWORK_ERROR ->
            "网络异常，未能验证凭据是否有效。是否仍要保存该账号？保存后会标记为「未验证」。"
        ImportDecisionReason.UNKNOWN ->
            "验证返回内容无法识别，未能确认凭据是否有效。是否仍要保存该账号？保存后会标记为「未验证」。"
        ImportDecisionReason.NO_COOKIE ->
            "未能从页面读取到 Canvas Cookies，请重新登录后再试。"
    }
    AlertDialog(
        onDismissRequest = onDiscard,
        confirmButton = {
            if (needsCookie) {
                TextButton(onClick = onRetry) { Text("重新登录") }
            } else {
                TextButton(onClick = onSaveAnyway) { Text("仍要保存") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) { Text(if (needsCookie) "放弃" else "不保存") }
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                state.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "详情：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
