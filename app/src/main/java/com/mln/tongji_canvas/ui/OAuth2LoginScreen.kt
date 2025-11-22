package com.mln.tongji_canvas.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mln.tongji_canvas.data.SessionRepository
import com.mln.tongji_canvas.data.OAuth2Tokens
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLDecoder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OAuth2LoginScreen(
    repository: SessionRepository,
    @Suppress("UNUSED_PARAMETER") initialUrl: String,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentUrl: MutableState<String> = remember { mutableStateOf("") }
    val webViewRef: MutableState<WebView?> = remember { mutableStateOf(null) }
    val isLoginDetected: MutableState<Boolean> = remember { mutableStateOf(false) }
    val countdown: MutableState<Int> = remember { mutableStateOf(0) }
    val isMobileUA: MutableState<Boolean> = remember { mutableStateOf(true) } // 默认使用手机UA
    
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
            countdown.value = 0
            
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
    
    // 延迟提取cookies的函数
    fun delayedExtractCookies() {
        if (!isLoginDetected.value) {
            isLoginDetected.value = true
            scope.launch {
                println("登录成功，等待5秒后提取cookies...")
                // 倒计时显示
                for (i in 5 downTo 1) {
                    countdown.value = i
                    kotlinx.coroutines.delay(1000) // 等待1秒
                }
                countdown.value = 0
                extractCookiesAndSave(repository, onSaved, webViewRef.value!!)
            }
        }
    }

    fun applyUserAgentMode(targetMobile: Boolean) {
        if (isMobileUA.value == targetMobile) return
        isMobileUA.value = targetMobile
        webViewRef.value?.let { webView ->
            webView.clearCache(true)
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()

            webView.settings.userAgentString = getUserAgent()
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            println("切换User-Agent: ${getUserAgent()}")

            isLoginDetected.value = false
            countdown.value = 0

            webView.loadUrl(loginUrl)
            println("重新加载URL: $loginUrl")

            webView.postDelayed({
                val earlyOverrideJS = getEarlyOverrideJS()
                webView.evaluateJavascript(earlyOverrideJS, null)
                println("切换后立即注入覆盖JS: ${if (isMobileUA.value) "手机模式" else "电脑模式"}")
            }, 50)
        }
    }

    val statusMessage = if (isLoginDetected.value) {
        if (countdown.value > 0) {
            "登录成功！系统将在 ${countdown.value} 秒后自动提取认证信息。"
        } else {
            "正在提取认证信息..."
        }
    } else {
        "请在下方统一认证页面完成登录，系统会自动捕获 Cookies。"
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("操作提示", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "1. 使用统一认证账号登录 Canvas，系统会自动捕获 Cookies。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "2. 若页面显示异常，可切换下方浏览器模式以匹配移动或桌面站点。",
                        style = MaterialTheme.typography.bodyMedium,
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
                    label = {
                        Text(
                            if (isLoginDetected.value) {
                                if (countdown.value > 0) "自动保存倒计时 ${countdown.value}s" else "已捕获，正在保存"
                            } else {
                                "等待登录成功"
                            }
                        )
                    },
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
                    .heightIn(min = 360.dp),
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
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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
                                            delayedExtractCookies()
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
                                            delayedExtractCookies()
                                        }
                                    }
                                }
                                initializeWebView()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (!isLoginDetected.value) {
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
}

// 提取cookies并保存的函数
private suspend fun extractCookiesAndSave(
    repository: SessionRepository,
    onSaved: () -> Unit,
    @Suppress("UNUSED_PARAMETER") webView: WebView
) {
    try {
        println("开始提取cookies...")
        // 使用WebView的CookieManager获取cookies
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        
        // 获取Canvas域名的cookies
        val canvasCookies = cookieManager.getCookie("https://canvas.tongji.edu.cn")
        val iamCookies = cookieManager.getCookie("https://iam.tongji.edu.cn")
        
        println("Canvas cookies: $canvasCookies")
        println("IAM cookies: $iamCookies")
        
        val allCookies = mutableListOf<String>()
        canvasCookies?.let { allCookies.add(it) }
        //iamCookies?.let { allCookies.add(it) } // 暂时不使用IAM cookies
        
        println("所有cookies: $allCookies")
        
        if (allCookies.isNotEmpty()) {
            // 将cookies转换为简单的认证标识
            val cookieString = allCookies.joinToString("; ")
            val display = "用户${System.currentTimeMillis()}"
            
            println("保存用户: $display, cookies: $cookieString")
            
            // 创建一个模拟的OAuth2Tokens，实际存储cookies
            val tokens = OAuth2Tokens(
                accessToken = cookieString, // 将cookies作为accessToken存储
                refreshToken = null,
                expiresIn = 3600L
            )
            
            repository.addOrUpdateSession(display, tokens)
            onSaved()
            println("用户保存成功!")
            
            // 清除WebView的cookies和缓存，为下次登录做准备
            // 注意：这里不调用initializeWebView，因为会重新加载URL
            // 只是清除数据，让下次进入时重新初始化
        } else {
            println("没有找到cookies")
        }
    } catch (e: Exception) {
        // 处理错误
        println("提取cookies时出错: ${e.message}")
        e.printStackTrace()
    }
}


private fun extractCodeFromUrl(url: String): String? {
    return try {
        val query = url.substringAfter("?")
        val params = query.split("&").associate { param ->
            val (key, value) = param.split("=", limit = 2)
            key to URLDecoder.decode(value, "UTF-8")
        }
        params["code"]
    } catch (e: Exception) {
        null
    }
}

private suspend fun exchangeCodeForToken(
    code: String,
    clientId: String,
    clientSecret: String,
    redirectUri: String
): OAuth2Tokens? {
    return try {
        val httpClient = OkHttpClient()
        val formBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("redirect_uri", redirectUri)
            .build()
        
        val request = Request.Builder()
            .url("https://iam.tongji.edu.cn/idp/oauth2/getToken")
            .post(formBody)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()
        
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string() ?: ""
            val json = JSONObject(responseBody)
            
            val accessToken = json.optString("access_token")
            val refreshToken = json.optString("refresh_token")
            val expiresIn = json.optLong("expires_in", 3600L)
            
            if (accessToken.isNotEmpty()) {
                OAuth2Tokens(
                    accessToken = accessToken,
                    refreshToken = if (refreshToken.isNotEmpty()) refreshToken else null,
                    expiresIn = expiresIn
                )
            } else {
                null
            }
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}
