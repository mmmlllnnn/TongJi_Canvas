package com.mln.tongji_canvas.ui

import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mln.tongji_canvas.data.SessionRepository
import com.mln.tongji_canvas.data.UserSession
import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import com.mln.tongji_canvas.ui.components.LoadingShimmerLines

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchSignScreen(
    repository: SessionRepository,
    targetUrl: String,
    selectedUserIds: Set<String> = emptySet()
) {
    val sessions = remember { mutableStateMapOf<String, UserSession>() }
    val states = remember { mutableStateMapOf<String, String>() } // loading/success/fail
    val webViewInstances = remember { mutableStateMapOf<String, WebView>() } // 持久化WebView实例
    val cookieSetFlags = remember { mutableStateMapOf<String, Boolean>() } // 标记cookie是否已设置
    val cookieQueue = remember { mutableListOf<String>() } // cookie设置队列
    val isProcessingQueue = remember { mutableStateOf(false) } // 是否正在处理队列
    val forceLoadTrigger = remember { mutableStateOf(0) } // 强制加载触发器
    val sessionHashes = remember { mutableStateMapOf<String, String>() } // 存储用户认证信息的哈希值
    val expandedCards = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        val allSessions = repository.getAllSessions()
        // 如果指定了选中的用户列表，则只处理这些用户；否则处理所有用户
        val filteredSessions = if (selectedUserIds.isNotEmpty()) {
            allSessions.filter { selectedUserIds.contains(it.id) }
        } else {
            allSessions
        }
        
        filteredSessions.forEach { s ->
            sessions[s.id] = s
            states[s.id] = "loading"
            cookieSetFlags[s.id] = false
            // 计算认证信息的哈希值，用于检测变化
            sessionHashes[s.id] = (s.accessToken ?: "").hashCode().toString()
        }
        // 按顺序添加用户到cookie设置队列
        cookieQueue.clear()
        cookieQueue.addAll(filteredSessions.map { it.id })
        println("初始化cookie设置队列: ${cookieQueue.size} 个用户 (选中用户: ${selectedUserIds.size})")
        
        // 延迟触发强制加载，确保所有WebView都已创建
        kotlinx.coroutines.delay(500)
        forceLoadTrigger.value++
    }

    fun triggerRetry(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val webView = webViewInstances[sessionId] ?: return
        states[sessionId] = "loading"
        cookieSetFlags[sessionId] = false
        applyCookiesAndLoad(webView, session, targetUrl)
    }

    // 监听用户认证信息变化，强制重新创建WebView
    LaunchedEffect(sessions) {
        sessions.forEach { (id, session) ->
            val currentHash = (session.accessToken ?: "").hashCode().toString()
            val previousHash = sessionHashes[id]
            
            if (previousHash != null && previousHash != currentHash) {
                println("检测到用户 ${session.displayName} 认证信息变化，强制重新创建WebView")
                // 清除旧的WebView实例
                webViewInstances[id]?.let { oldWebView ->
                    try {
                        oldWebView.destroy()
                    } catch (e: Exception) {
                        println("销毁旧WebView时出错: ${e.message}")
                    }
                }
                webViewInstances.remove(id)
                cookieSetFlags[id] = false
                states[id] = "loading"
                sessionHashes[id] = currentHash
            }
        }
    }

    val successCount = states.values.count { it == "success" }
    val failureCount = states.values.count { it == "fail" }
    val scrollState = rememberScrollState()
    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topBarState)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            androidx.compose.material3.LargeTopAppBar(
                title = {
                    Column {
                        Text("批量签到", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = targetUrl,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BatchSummaryCard(
                total = sessions.size,
                success = successCount,
                failure = failureCount,
                targetUrl = targetUrl
            )

            sessions.values.toList().forEach { session ->
                val expanded = expandedCards.getOrPut(session.id) { false }
                SessionSignCard(
                    session = session,
                    status = states[session.id] ?: "loading",
                    expanded = expanded,
                    onToggleExpanded = { expandedCards[session.id] = !(expandedCards[session.id] ?: false) },
                    onRetry = { triggerRetry(session.id) }
                ) { modifier ->
                SessionWebView(
                    session = session,
                    modifier = modifier,
                    webViewInstances = webViewInstances,
                    cookieSetFlags = cookieSetFlags,
                    sessionHashes = sessionHashes,
                    states = states,
                    targetUrl = targetUrl,
                    cookieQueue = cookieQueue,
                    isProcessingQueue = isProcessingQueue
                )
                }
            }
        }
    }
}

@Composable
private fun BatchSummaryCard(
    total: Int,
    success: Int,
    failure: Int,
    targetUrl: String
) {
    val progress = if (total == 0) 0f else success.toFloat() / total.toFloat()
    Surface(
        shape = RoundedCornerShape(32.dp),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("签到进度", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("总计 $total", style = MaterialTheme.typography.bodyMedium)
                Text("成功 $success", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                if (failure > 0) {
                    Text("失败 $failure", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        text = targetUrl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) }
            )
        }
    }
}

@Composable
private fun SessionSignCard(
    session: UserSession,
    status: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRetry: () -> Unit,
    webContent: @Composable (Modifier) -> Unit
) {
    val (statusLabel, statusColor, statusIcon) = when (status) {
        "success" -> Triple("签到成功", MaterialTheme.colorScheme.primary, Icons.Outlined.CheckCircle)
        "fail" -> Triple("签到失败", MaterialTheme.colorScheme.error, Icons.Outlined.ErrorOutline)
        else -> Triple("签到中", MaterialTheme.colorScheme.tertiary, Icons.Outlined.Schedule)
    }
    val cookieStatus = if (session.accessToken.isNullOrEmpty()) "未保存认证信息" else "已保存认证信息"
    val webViewHeight by animateDpAsState(
        targetValue = if (expanded) 320.dp else 1.dp,
        label = "${session.id}_webHeight"
    )
    val webViewAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        label = "${session.id}_webAlpha"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text(session.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(cookieStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(statusLabel) },
                    leadingIcon = { Icon(statusIcon, contentDescription = null, tint = statusColor) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = statusColor.copy(alpha = 0.12f),
                        labelColor = statusColor
                    )
                )
            }

            if (status == "loading") {
                LoadingShimmerLines(lines = 3, modifier = Modifier.fillMaxWidth())
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(onClick = onToggleExpanded) {
                    Text(if (expanded) "收起调试" else "查看调试")
                }
                if (status == "fail") {
                    OutlinedButton(onClick = onRetry) {
                        Text("重试")
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(webViewHeight)
                    .clip(RoundedCornerShape(24.dp))
                    .alpha(webViewAlpha)
            ) {
                webContent(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun SessionWebView(
    session: UserSession,
    modifier: Modifier,
    webViewInstances: MutableMap<String, WebView>,
    cookieSetFlags: MutableMap<String, Boolean>,
    sessionHashes: MutableMap<String, String>,
    states: MutableMap<String, String>,
    targetUrl: String,
    cookieQueue: MutableList<String>,
    isProcessingQueue: MutableState<Boolean>
) {
    AndroidView(
        factory = { ctx ->
            webViewInstances[session.id]?.let { existingWebView ->
                println("复用现有WebView实例 for ${session.displayName}")
                return@AndroidView existingWebView
            }
            println("为用户 ${session.displayName} 创建新的WebView实例")
            val newWebView = createCleanWebView(
                ctx,
                session,
                targetUrl,
                states,
                webViewInstances,
                cookieSetFlags,
                sessions = mutableMapOf(),
                cookieQueue = cookieQueue,
                isProcessingQueue = isProcessingQueue
            )
            webViewInstances[session.id] = newWebView
            newWebView
        },
        update = { webView ->
            val currentHash = (session.accessToken ?: "").hashCode().toString()
            val previousHash = sessionHashes[session.id]
            if (cookieSetFlags[session.id] != true || previousHash != currentHash) {
                println("更新WebView时重新设置cookies for ${session.displayName} (认证信息变化: ${previousHash != currentHash})")
                applyCookiesAndLoad(webView, session, targetUrl)
                cookieSetFlags[session.id] = true
                sessionHashes[session.id] = currentHash
            }
        },
        modifier = modifier
    )
}

// 创建干净的WebView
private fun createCleanWebView(
    context: Context,
    session: UserSession,
    targetUrl: String,
    states: MutableMap<String, String>,
    @Suppress("UNUSED_PARAMETER") webViewInstances: MutableMap<String, WebView>,
    cookieSetFlags: MutableMap<String, Boolean>,
    @Suppress("UNUSED_PARAMETER") sessions: MutableMap<String, UserSession>,
    @Suppress("UNUSED_PARAMETER") cookieQueue: MutableList<String>,
    @Suppress("UNUSED_PARAMETER") isProcessingQueue: MutableState<Boolean>
): WebView {
    println("创建新的WebView并加载URL for ${session.displayName}")
    return WebView(context).apply {
        // 先清除所有数据
        clearWebViewData(this)
        
        // 配置WebView设置
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        
        // 设置WebViewClient
        webViewClient = createWebViewClient(session, states)
        
        // 立即开始加载，不等待队列
        post {
            println("立即为用户 ${session.displayName} 开始加载WebView")
            applyCookiesAndLoad(this, session, targetUrl)
            cookieSetFlags[session.id] = true
        }
    }
}


// 处理cookie设置队列
private fun processCookieQueue(
    webView: WebView,
    session: UserSession,
    targetUrl: String,
    cookieSetFlags: MutableMap<String, Boolean>,
    cookieQueue: MutableList<String>,
    isProcessingQueue: MutableState<Boolean>
) {
    // 检查是否轮到当前用户
    if (cookieQueue.isNotEmpty() && cookieQueue[0] == session.id && cookieSetFlags[session.id] != true) {
        isProcessingQueue.value = true
        println("开始为用户 ${session.displayName} 设置cookies (队列位置: 1/${cookieQueue.size})")
        
        webView.post {
            applyCookiesAndLoad(webView, session, targetUrl)
            cookieSetFlags[session.id] = true
            cookieQueue.removeAt(0)
            isProcessingQueue.value = false
            println("用户 ${session.displayName} cookies设置完成，队列剩余: ${cookieQueue.size}")
        }
    } else if (cookieSetFlags[session.id] != true) {
        // 等待轮到当前用户
        webView.postDelayed({
            processCookieQueue(webView, session, targetUrl, cookieSetFlags, cookieQueue, isProcessingQueue)
        }, 200)
    }
}

// 清除WebView数据
private fun clearWebViewData(webView: WebView) {
    try {
        println("清除WebView残留数据...")
        
        // 停止加载
        webView.stopLoading()
        webView.loadUrl("about:blank")
        
        // 清除WebView缓存
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
        
        // 注意：不在这里清除全局cookies，因为会影响其他WebView
        // 每个WebView的cookies将在applyCookiesAndLoad中单独设置
        
        println("WebView数据清除完成")
    } catch (e: Exception) {
        println("清除WebView数据时出错: ${e.message}")
        e.printStackTrace()
    }
}

// 创建WebViewClient
private fun createWebViewClient(
    session: UserSession,
    states: MutableMap<String, String>
): WebViewClient {
    return object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            states[session.id] = "loading"
        }
        
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.let { webView ->
                webView.evaluateJavascript("(function() { return document.body.innerText || document.body.textContent || ''; })();") { content ->
                    val pageText = content?.removeSurrounding("\"")?.replace("\\n", " ") ?: ""
                    val isSuccess = pageText.contains("签到成功") || pageText.contains("已签过")
                    states[session.id] = if (isSuccess) "success" else "fail"
                }
            } ?: run {
                states[session.id] = "fail"
            }
        }
        
        @Deprecated("Deprecated in Java")
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            @Suppress("DEPRECATION")
            super.onReceivedError(view, errorCode, description, failingUrl)
            states[session.id] = "fail"
        }
        
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            return false
        }
        
        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
            return false
        }
    }
}

// 应用cookies并加载URL
private fun applyCookiesAndLoad(webView: WebView, session: UserSession, targetUrl: String) {
    println("为用户 ${session.displayName} 设置cookies并加载URL: $targetUrl")
    
    val cookies = session.accessToken // 这里存储的是cookies字符串
    if (cookies?.isNotEmpty() ?: false) {
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        
        try {
            // 先清除相关域名的旧cookies，确保使用最新的认证信息
            try {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                println("清除所有现有cookies以确保使用最新认证信息")
            } catch (e: Exception) {
                println("清除cookies时出错: ${e.message}")
            }
            
            // 解析并设置cookies
            val cookieList = cookies?.split("; ")?.filter { it.isNotEmpty() } ?: emptyList()
            println("为用户 ${session.displayName} 设置 ${cookieList.size} 个cookies")
            
            cookieList.forEach { cookie ->
                try {
                    // 为Canvas域名设置cookie
                    cookieManager.setCookie("https://canvas.tongji.edu.cn", cookie)
                    // 为IAM域名设置cookie
                    cookieManager.setCookie("https://iam.tongji.edu.cn", cookie)
                    println("设置cookie: $cookie")
                } catch (e: Exception) {
                    println("设置cookie失败: $cookie, 错误: ${e.message}")
                }
            }
            
            // 强制刷新cookies
            cookieManager.flush()
            
            // 验证cookies是否设置成功
            val canvasCookies = cookieManager.getCookie("https://canvas.tongji.edu.cn")
            val iamCookies = cookieManager.getCookie("https://iam.tongji.edu.cn")
            println("用户 ${session.displayName} Canvas cookies: $canvasCookies")
            println("用户 ${session.displayName} IAM cookies: $iamCookies")
        } catch (e: Exception) {
            println("设置cookies时出错: ${e.message}")
        }
    } else {
        println("用户 ${session.displayName} 没有cookies，直接加载URL")
    }
    
    // 加载目标URL
    webView.loadUrl(targetUrl)
}



