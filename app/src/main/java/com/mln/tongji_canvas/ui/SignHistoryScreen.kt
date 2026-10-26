package com.mln.tongji_canvas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mln.tongji_canvas.data.SignHistoryStore
import com.mln.tongji_canvas.data.SignRun
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SignHistoryScreen(store: SignHistoryStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var runs by remember { mutableStateOf<List<SignRun>>(emptyList()) }
    var showClear by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { runs = store.list() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Text("签到历史")
            TextButton(onClick = { showClear = true }, enabled = runs.isNotEmpty()) { Text("清空") }
        }
        if (runs.isEmpty()) Text("暂无签到记录")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(runs, key = { it.timeMs }) { run ->
                Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(DateFormat.getDateTimeInstance().format(Date(run.timeMs)))
                        Text("${run.courseName ?: "课程 ${run.courseCode.ifBlank { "未知" }}"} · 已签到 ${run.signedCount}/${run.accounts.size}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { expanded = if (expanded == run.timeMs) null else run.timeMs }) {
                                Text(if (expanded == run.timeMs) "收起" else "查看结果")
                            }
                            Button(onClick = { clipboard.setText(AnnotatedString(run.summary())) }) { Text("复制汇总") }
                        }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(run.diagnostics())) }) { Text("复制诊断") }
                        if (expanded == run.timeMs) {
                            run.accounts.forEach { account ->
                                Text("${account.name}：${account.message} · HTTP ${account.httpCode ?: "-"} · ${account.elapsedMs} ms")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClear) AlertDialog(
        onDismissRequest = { showClear = false },
        title = { Text("清空签到历史？") },
        text = { Text("此操作会删除本机保存的签到记录。") },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { store.clear(); runs = emptyList(); showClear = false }
            }) { Text("清空") }
        },
        dismissButton = { TextButton(onClick = { showClear = false }) { Text("取消") } }
    )
}
