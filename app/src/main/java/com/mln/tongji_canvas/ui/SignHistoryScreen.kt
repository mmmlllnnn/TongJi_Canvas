package com.mln.tongji_canvas.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mln.tongji_canvas.data.AccountSignRecord
import com.mln.tongji_canvas.data.SignHistoryStore
import com.mln.tongji_canvas.data.SignRun
import com.mln.tongji_canvas.data.SignStatus
import com.mln.tongji_canvas.ui.theme.CanvasPalette
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SignHistoryScreen(store: SignHistoryStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    var runs by remember { mutableStateOf<List<SignRun>>(emptyList()) }
    var showClear by remember { mutableStateOf(false) }
    var selectedRun by remember { mutableStateOf<SignRun?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runs = store.list() }

    BackHandler(enabled = selectedRun != null) {
        selectedRun = null
        showMenu = false
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (selectedRun != null) {
                    selectedRun = null
                    showMenu = false
                } else {
                    onBack()
                }
            }) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                if (selectedRun == null) "签到历史" else "签到结果",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            val run = selectedRun
            if (run == null) {
                TextButton(onClick = { showClear = true }, enabled = runs.isNotEmpty()) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("清空", modifier = Modifier.padding(start = 4.dp))
                }
            } else {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("复制诊断") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                clipboard.setText(AnnotatedString(run.diagnostics()))
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }

        val run = selectedRun
        if (run != null) {
            HistoryResultContent(
                run = run,
                timeLabel = dateFormat.format(Date(run.timeMs)),
                onCopy = { clipboard.setText(AnnotatedString(run.summary())) },
                modifier = Modifier.weight(1f)
            )
        } else if (runs.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("暂无签到记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(runs, key = { it.timeMs }) { historyRun ->
                    HistoryRunCard(
                        run = historyRun,
                        timeLabel = dateFormat.format(Date(historyRun.timeMs)),
                        onClick = { selectedRun = historyRun }
                    )
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

@Composable
private fun HistoryRunCard(run: SignRun, timeLabel: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    run.courseName ?: "课程 ${run.courseCode.ifBlank { "未知" }}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(timeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HistoryRunCounts(run)
                HistoryResultBar(run)
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = "查看签到结果",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryResultContent(run: SignRun, timeLabel: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 2.dp
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            run.courseName ?: "课程 ${run.courseCode.ifBlank { "未知" }}",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            timeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onCopy,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) { Text("复制结果") }
                }
                HistoryRunCounts(run)
                HistoryResultBar(run)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(run.accounts) { account -> HistoryAccountCard(account) }
        }
    }
}

@Composable
private fun HistoryRunCounts(run: SignRun) {
    Text(
        "共 ${run.accounts.size} 人 · 成功 ${run.signedCount} · 失败 ${run.accounts.size - run.signedCount}",
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun HistoryResultBar(run: SignRun) {
    val signed = run.signedCount
    val failed = run.accounts.size - signed
    Row(
        Modifier.fillMaxWidth().height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (signed > 0) {
            Box(Modifier.weight(signed.toFloat()).fillMaxHeight().background(CanvasPalette.Accent))
        }
        if (failed > 0) {
            Box(Modifier.weight(failed.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.error))
        }
    }
}

@Composable
private fun HistoryAccountCard(account: AccountSignRecord) {
    val statusLabel = when (account.status) {
        SignStatus.SUCCESS -> "签到成功"
        SignStatus.ALREADY_SIGNED -> "已签到过"
        else -> "签到失败"
    }
    val statusColor = if (account.signed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val statusIcon = if (account.signed) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                Text(account.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
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
            Text(
                "HTTP ${account.httpCode ?: "-"} · ${account.elapsedMs} ms",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
        }
    }
}
