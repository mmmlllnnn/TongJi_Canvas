package com.mln.tongji_canvas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mln.tongji_canvas.data.AccountOutcome
import com.mln.tongji_canvas.data.DiagEntry
import com.mln.tongji_canvas.data.HistoryFormat
import com.mln.tongji_canvas.data.HistoryStore
import com.mln.tongji_canvas.data.SignHistoryEntry
import com.mln.tongji_canvas.ui.components.EmptyStatePanel
import com.mln.tongji_canvas.ui.components.LoadingShimmerLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 签到历史页。
 *
 * 回答的是**用户的问题**：「我帮谁签过到 / 签过哪几次 / 那次成了几个」。
 * 因此默认只展示人话：时间、课程、「成功 8 / 12」、每个人的结论与原因。
 *
 * 诊断信息（HTTP 状态码、命中的判定规则 evidence、是否为兜底推断、原始正文片段）
 * 属于**排障**用途，收在每条记录的「详情/诊断」里按需展开——默认绝不出现，免得把
 * 用户真正关心的结论淹掉。
 *
 * 页面上没有任何凭据：历史与诊断在落盘时就已经脱敏（见 [HistoryStore]），
 * 「复制日志」复制的也是同一份已脱敏文本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    store: HistoryStore,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

    var entries by remember { mutableStateOf<List<SignHistoryEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** 正在展开「详情/诊断」的那一条（同时只展开一条，避免日志淹掉列表） */
    var diagRunId by remember { mutableStateOf<String?>(null) }
    var diagLines by remember { mutableStateOf<List<DiagEntry>>(emptyList()) }
    var showClearConfirm by remember { mutableStateOf(false) }

    /** 读盘一律在 IO 线程；本函数是 suspend，只能在协程里调（避免 suspend/非 suspend 混用） */
    suspend fun reload() {
        val history = withContext(Dispatchers.IO) { store.getHistory() }
        entries = history
        loaded = true
        diagRunId = null
        diagLines = emptyList()
    }

    LaunchedEffect(Unit) { reload() }

    // 展开某一条的「详情/诊断」时才去读诊断日志
    LaunchedEffect(diagRunId) {
        val runId = diagRunId ?: return@LaunchedEffect
        diagLines = withContext(Dispatchers.IO) { store.getDiagForRun(runId) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("签到历史", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (loaded) "共 ${entries.size} 次签到（最多保留 ${HistoryStore.HISTORY_MAX_ENTRIES} 次）"
                            else "正在读取…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                if (entries.isEmpty()) {
                                    snackbarHostState.showSnackbar("暂无可复制的记录")
                                    return@launch
                                }
                                val text = withContext(Dispatchers.IO) { store.exportText() }
                                clipboardManager.setText(AnnotatedString(text))
                                snackbarHostState.showSnackbar("已复制日志（已脱敏，不含任何凭据）")
                            }
                        }
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制日志")
                    }
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "清空历史")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
        ) {
            when {
                !loaded -> {
                    Spacer(Modifier.height(16.dp))
                    LoadingShimmerLines(lines = 6)
                }
                entries.isEmpty() -> {
                    Spacer(Modifier.height(24.dp))
                    EmptyStatePanel(
                        icon = Icons.Outlined.History,
                        title = "还没有签到记录",
                        subtitle = "扫描签到二维码后，这里会记下「帮谁签过、签没签上」。"
                    )
                }
                else -> {
                    Text(
                        text = "默认只显示结论；需要排障时点开某一条的「详情 / 诊断」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            HistoryEntryCard(
                                entry = entry,
                                expanded = expandedIds.contains(entry.id),
                                diagExpanded = diagRunId == entry.id,
                                diagLines = if (diagRunId == entry.id) diagLines else emptyList(),
                                onToggle = { expandedIds = expandedIds.toggle(entry.id) },
                                onToggleDiag = {
                                    if (diagRunId == entry.id) {
                                        diagRunId = null
                                    } else {
                                        // 展开诊断时顺手把账号明细也展开，否则看不到上下文
                                        expandedIds = expandedIds + entry.id
                                        diagRunId = entry.id
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空历史？") },
            text = { Text("将删除全部签到历史与诊断日志，且无法恢复。账号与凭据不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        scope.launch {
                            withContext(Dispatchers.IO) { store.clearAll() }
                            reload()
                            snackbarHostState.showSnackbar("已清空签到历史与诊断日志")
                        }
                    }
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 一条签到记录：紧凑头部（时间 / 课程 / 成功 X / Y），展开后是逐账号结论 */
@Composable
private fun HistoryEntryCard(
    entry: SignHistoryEntry,
    expanded: Boolean,
    diagExpanded: Boolean,
    diagLines: List<DiagEntry>,
    onToggle: () -> Unit,
    onToggleDiag: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 紧凑头部：一眼看完「什么时候、哪门课、成了几个」──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${HistoryFormat.shortTime(entry.startedAtMs)} · ${entry.courseText}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(entry.summaryText)
                            if (entry.cancelled) append("（已取消）")
                            append(" · ${HistoryFormat.duration(entry.durationMs)}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = summaryColor(entry),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = HistoryFormat.time(entry.startedAtMs) + " 发起",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    entry.accounts.forEach { account -> AccountRow(account) }

                    TextButton(onClick = onToggleDiag, modifier = Modifier.align(Alignment.Start)) {
                        Icon(Icons.Outlined.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (diagExpanded) "收起诊断" else "详情 / 诊断")
                    }

                    if (diagExpanded) {
                        DiagnosticPanel(entry = entry, lines = diagLines)
                    }
                }
            }
        }
    }
}

/** 一个账号一行：结论 + 原因（用户视角的唯一重点） */
@Composable
private fun AccountRow(account: AccountOutcome) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = accountIcon(account),
            contentDescription = null,
            tint = accountColor(account),
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = account.identityText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            account.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = account.statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color = accountColor(account)
        )
    }
}

/**
 * 诊断面板：默认不出现，点开才出现。
 *
 * 优先展示运行期写下的诊断行（含 HTTP 状态码、evidence、attempts、耗时、原始正文片段）；
 * 若某个批次没有诊断行（例如旧版本写入的历史），退化为直接展示该条历史里已有的字段，
 * 保证「详情/诊断」永远不会点开是空的。
 */
@Composable
private fun DiagnosticPanel(entry: SignHistoryEntry, lines: List<DiagEntry>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "诊断明细（仅供排障）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "批次 ${entry.id}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (lines.isEmpty()) {
                entry.accounts.forEach { account ->
                    Text(
                        text = "${account.displayName} · HTTP ${account.httpStatus ?: "-"} · " +
                            "${account.attempts} 次 · ${HistoryFormat.duration(account.elapsedMs)}" +
                            (account.evidence?.let { " · $it" } ?: "") +
                            (if (account.inferred) " · 推断" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    account.rawSnippet?.takeIf { it.isNotBlank() }?.let { snippet ->
                        Text(
                            text = "  原始正文：$snippet",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                lines.forEach { line ->
                    Text(
                        text = line.formatLine(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// ==================== 展示用的小工具 ====================

/** 「追加/移除」元素的小工具（保持 Set 不可变，方便 Compose 比较） */
private fun Set<String>.toggle(id: String): Set<String> =
    if (contains(id)) this - id else this + id

@Composable
private fun summaryColor(entry: SignHistoryEntry): Color = when {
    entry.cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
    entry.failedCount == 0 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun accountColor(account: AccountOutcome): Color = when {
    account.isSuccessLike -> MaterialTheme.colorScheme.primary
    account.isCancelled -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.error
}

private fun accountIcon(account: AccountOutcome): ImageVector = when {
    account.isSuccessLike -> Icons.Outlined.CheckCircle
    account.isCancelled -> Icons.Outlined.Schedule
    else -> Icons.Outlined.ErrorOutline
}
