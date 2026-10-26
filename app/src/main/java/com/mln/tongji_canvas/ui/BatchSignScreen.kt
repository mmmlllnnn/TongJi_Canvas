package com.mln.tongji_canvas.ui

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mln.tongji_canvas.data.SessionRepository
import com.mln.tongji_canvas.data.SignHistoryStore
import com.mln.tongji_canvas.data.SignInClient
import com.mln.tongji_canvas.data.SignResult
import com.mln.tongji_canvas.data.SignStatus
import com.mln.tongji_canvas.data.UserSession
import com.mln.tongji_canvas.ui.theme.CanvasPalette
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

@Composable
fun BatchSignScreen(
    repository: SessionRepository,
    targetUrl: String,
    selectedUserIds: Set<String> = emptySet()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val history = remember { SignHistoryStore(context) }
    val clipboard = LocalClipboardManager.current
    val results = remember { mutableStateMapOf<String, SignResult>() }
    var accounts by remember { mutableStateOf<List<UserSession>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var scanningAgain by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var requestUrl by remember(targetUrl) { mutableStateOf(targetUrl) }

    suspend fun sign(url: String, targets: List<UserSession>) {
        running = true
        requestUrl = url
        targets.forEach { results.remove(it.id) }
        coroutineScope {
            targets.map { account ->
                launch { results[account.id] = SignInClient.sign(account, url) }
            }.joinAll()
        }
        history.add(url, targets.mapNotNull { account ->
            results[account.id]?.let { account to it }
        })
        running = false
    }

    LaunchedEffect(targetUrl, selectedUserIds) {
        val all = repository.getAllSessions()
        accounts = if (selectedUserIds.isEmpty()) all else all.filter { it.id in selectedUserIds }
        sign(targetUrl, accounts)
    }

    val signed = accounts.count { results[it.id]?.signed == true }
    val completed = accounts.count { results[it.id] != null }
    val retryable = accounts.filter { results[it.id]?.retryable == true }

    if (scanningAgain) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "重新扫码后补签失败账号",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                OutlinedButton(onClick = { scanningAgain = false }) { Text("取消") }
            }
            scanError?.let { Text(it, Modifier.padding(horizontal = 16.dp)) }
            Box(Modifier.weight(1f)) {
                EnhancedScannerScreen { freshUrl ->
                    if (SignInClient.validTarget(freshUrl)) {
                        scanningAgain = false
                        scanError = null
                        scope.launch { sign(freshUrl, retryable) }
                    } else {
                        scanError = "这不是同济 Canvas 签到码，请重新扫描"
                    }
                }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        Text(
            "签到结果",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 2.dp
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "完成 $completed/${accounts.size} · 成功 $signed · 失败 ${completed - signed}",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    Modifier.fillMaxWidth().height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (signed > 0) {
                        Box(Modifier.weight(signed.toFloat()).fillMaxHeight().background(CanvasPalette.Accent))
                    }
                    if (completed > signed) {
                        Box(Modifier.weight((completed - signed).toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.error))
                    }
                    if (accounts.size > completed) {
                        Box(Modifier.weight((accounts.size - completed).toFloat()).fillMaxHeight())
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { clipboard.setText(AnnotatedString(requestUrl)) },
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                requestUrl,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = "复制链接",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val summary = buildString {
                                append("已签到 $signed / ${accounts.size}")
                                accounts.forEach { account ->
                                    append("\n${account.displayName}：${results[account.id]?.message ?: "未完成"}")
                                }
                            }
                            clipboard.setText(AnnotatedString(summary))
                        },
                        modifier = Modifier.height(36.dp),
                        enabled = !running && results.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) { Text("复制结果") }
                }
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            items(accounts, key = { it.id }) { account ->
                val result = results[account.id]
                var expanded by remember(result?.html) { mutableStateOf(false) }
                val (statusLabel, statusColor, statusIcon) = when {
                    result == null -> Triple("签到中", MaterialTheme.colorScheme.tertiary, Icons.Outlined.Schedule)
                    result.signed -> Triple(
                        if (result.status == SignStatus.ALREADY_SIGNED) "已签到过" else "签到成功",
                        MaterialTheme.colorScheme.primary,
                        Icons.Outlined.CheckCircle
                    )
                    else -> Triple("签到失败", MaterialTheme.colorScheme.error, Icons.Outlined.ErrorOutline)
                }
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(32.dp),
                    tonalElevation = 2.dp
                ) {
                    Column(
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                account.displayName,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = statusColor.copy(alpha = 0.12f),
                                contentColor = statusColor
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(statusIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(statusLabel, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        if (result != null) {
                            val html = result.html
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!html.isNullOrBlank()) {
                                    FilledTonalButton(
                                        onClick = { expanded = !expanded },
                                        modifier = Modifier.height(32.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Description,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            if (expanded) "收起详情" else "查看详情",
                                            modifier = Modifier.padding(start = 4.dp),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                Text(
                                    "HTTP ${result.httpCode ?: "-"} · ${result.elapsedMs} ms",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.End
                                )
                            }
                            if (!html.isNullOrBlank()) {
                                if (expanded) {
                                    AndroidView(
                                        factory = { ctx ->
                                            WebView(ctx).apply {
                                                settings.javaScriptEnabled = false
                                                settings.blockNetworkLoads = true
                                                webViewClient = object : WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(
                                                        view: WebView,
                                                        request: WebResourceRequest
                                                    ): Boolean = true
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(24.dp)),
                                        onRelease = { it.destroy() },
                                        update = { webView ->
                                            if (webView.tag != html) {
                                                webView.loadDataWithBaseURL(
                                                    null, html, "text/html", "UTF-8", null
                                                )
                                                webView.tag = html
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        if (!running && retryable.isNotEmpty()) {
            Button(
                onClick = { scanningAgain = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 24.dp)
            ) {
                Icon(
                    Icons.Outlined.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp).size(18.dp)
                )
                Text("重新扫码补签（${retryable.size}）")
            }
        }
    }
}
