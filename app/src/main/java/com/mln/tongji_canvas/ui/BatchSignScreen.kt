package com.mln.tongji_canvas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mln.tongji_canvas.data.SessionRepository
import com.mln.tongji_canvas.data.SignHistoryStore
import com.mln.tongji_canvas.data.SignInClient
import com.mln.tongji_canvas.data.SignResult
import com.mln.tongji_canvas.data.SignStatus
import com.mln.tongji_canvas.data.UserSession
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

    suspend fun sign(url: String, targets: List<UserSession>) {
        running = true
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
    val retryable = accounts.filter { results[it.id]?.retryable == true }

    if (scanningAgain) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("重新扫码后补签失败账号", style = MaterialTheme.typography.titleMedium)
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
        Text("批量签到", style = MaterialTheme.typography.headlineSmall)
        Text("已签到 $signed / ${accounts.size}")
        if (running) CircularProgressIndicator()
        if (!running && results.isNotEmpty()) {
            OutlinedButton(onClick = {
                val summary = buildString {
                    append("已签到 $signed / ${accounts.size}")
                    accounts.forEach { account ->
                        append("\n${account.displayName}：${results[account.id]?.message ?: "未完成"}")
                    }
                }
                clipboard.setText(AnnotatedString(summary))
            }) { Text("复制结果") }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            items(accounts, key = { it.id }) { account ->
                val result = results[account.id]
                Surface(
                    Modifier.fillMaxWidth(),
                    tonalElevation = 2.dp,
                    color = when {
                        result == null -> MaterialTheme.colorScheme.surface
                        result.signed -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                result == null -> "签到中"
                                result.status == SignStatus.ALREADY_SIGNED -> "已签到过（成功）"
                                result.status == SignStatus.NOT_ENROLLED -> "未选课"
                                else -> result.message
                            }
                        )
                        if (result != null) {
                            Text("HTTP ${result.httpCode ?: "-"} · ${result.elapsedMs} ms",
                                style = MaterialTheme.typography.bodySmall)
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
            ) { Text("重新扫码补签（${retryable.size}）") }
        }
    }
}
