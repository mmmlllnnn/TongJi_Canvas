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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mln.tongji_canvas.data.AccountOutcome
import com.mln.tongji_canvas.data.AppDiagnostics
import com.mln.tongji_canvas.data.DiagLevel
import com.mln.tongji_canvas.data.HistoryFormat
import com.mln.tongji_canvas.data.HistoryStore
import com.mln.tongji_canvas.data.SecretRedactor
import com.mln.tongji_canvas.data.SessionRepository
import com.mln.tongji_canvas.data.SignEvent
import com.mln.tongji_canvas.data.SignInEngine
import com.mln.tongji_canvas.data.SignResult
import com.mln.tongji_canvas.data.SignStatus
import com.mln.tongji_canvas.data.SignTarget
import com.mln.tongji_canvas.data.SignTargetParse
import com.mln.tongji_canvas.data.UserSession
import com.mln.tongji_canvas.data.signDiagTag
import com.mln.tongji_canvas.ui.components.EmptyStatePanel
import com.mln.tongji_canvas.ui.components.LoadingShimmerLines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * 二维码/签到码有效窗口的**估算**值 —— 已不再使用。
 * 服务端不下发窗口长度，编出来的倒计时会误导用户，改用纯事实展示后删除。
 */

/**
 * 批量签到结果页（纯 HTTP 实现）。
 *
 * 与旧实现的根本区别：这里 **没有 WebView、没有 CookieManager、没有任何全局可变状态**。
 * 每个账号由 [SignInEngine] 单独发一个带 `Cookie: _canvas_middle_session=…` 头的 HTTP GET，
 * 因此不存在「A 的请求带着 B 的会话发出」的串号问题。
 *
 * 信息层级（本页唯一的设计目标）：
 * 1. 第一眼 —— 顶部固定区一个巨大的「成功 X / Y」，不再需要往下翻；
 * 2. 第二眼 —— 紧跟其后的失败列表，每人一行且直接带原因；
 * 3. 已签到的账号收进可折叠区，默认不占地方。
 * 因此刻意去掉了标题、签到链接、耗时与窗口倒计时。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchSignScreen(
    repository: SessionRepository,
    targetUrl: String,
    selectedUserIds: Set<String> = emptySet()
) {
    val engine = remember { SignInEngine() }
    val scope = rememberCoroutineScope()

    val sessions = remember { mutableStateListOf<UserSession>() }
    val startedIds = remember { mutableStateMapOf<String, Boolean>() }
    val results = remember { mutableStateMapOf<String, SignResult>() }
    val running = remember { mutableStateOf(false) }
    val generation = remember { mutableStateOf(0) }
    val job = remember { mutableStateOf<Job?>(null) }
    val successExpanded = remember { mutableStateOf(false) }

    val targetParse = remember(targetUrl) { SignTarget.parse(targetUrl) }
    val target = (targetParse as? SignTargetParse.Valid)?.target
    val invalidReason = (targetParse as? SignTargetParse.Invalid)?.reason

    /**
     * 把一次批次的结果写进「签到历史 + 诊断日志」。
     *
     * 分工（不要混）：
     * - 历史（[HistoryStore.appendSignSession]）只留人话：时间 / 课程 / 每个人的结论与原因；
     * - 诊断（[HistoryStore.log]）留排障信息：HTTP 状态码、命中的规则（evidence）、是否兜底推断、
     *   尝试次数、耗时、**已脱敏**的请求 URL。
     *
     * 在 IO 线程执行；原子里先读快照（Compose 状态不能跨线程乱读），再交给 store。
     * 用 [NonCancellable] 是因为本函数会在「取消」路径上被调用——不加的话写盘会被立即取消。
     */
    suspend fun recordRun(
        runId: String,
        startedAtMs: Long,
        finishedAtMs: Long,
        signTarget: SignTarget,
        batch: List<UserSession>,
        cancelled: Boolean
    ) {
        val store = AppDiagnostics.current ?: return
        // 1) 先在当前线程把结果读成不可变快照
        val outcomes = batch.map { session ->
            results[session.id]?.let { AccountOutcome.from(it) }
                ?: AccountOutcome(
                    sessionId = session.id,
                    displayName = session.displayName,
                    status = SignStatus.TIMEOUT,
                    reason = if (cancelled) "已取消（请求未完成）" else "未执行（批次被新的重试接管）",
                    attempts = 0,
                    evidence = if (cancelled) AccountOutcome.CANCELLED_EVIDENCE else "未执行"
                )
        }
        val courseName = batch.firstNotNullOfOrNull { session ->
            results[session.id]?.courseName?.takeIf { it.isNotBlank() }
        }
        val signedCount = outcomes.count { it.isSuccessLike }

        // 2) 再切到 IO 线程落盘（历史 + 诊断）
        withContext(NonCancellable + Dispatchers.IO) {
            store.appendSignSession(
                runId = runId,
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs,
                courseName = courseName,
                courseCode = signTarget.courseCode,
                targetHost = signTarget.host(),
                accounts = outcomes
            )
            store.log(
                level = if (cancelled) DiagLevel.WARN else DiagLevel.INFO,
                tag = signDiagTag(runId),
                message = if (cancelled) {
                    "批次被取消：成功 $signedCount / ${outcomes.size}"
                } else {
                    "批次结束：成功 $signedCount / ${outcomes.size}"
                },
                detail = "GET ${SecretRedactor.redact(signTarget.url)} · " +
                    "并发上限 ${SignInEngine.DEFAULT_MAX_CONCURRENCY} · " +
                    "重试上限 ${SignInEngine.DEFAULT_MAX_RETRIES} · " +
                    "批次耗时 ${HistoryFormat.duration(finishedAtMs - startedAtMs)}"
            )
            outcomes.forEach { outcome ->
                store.log(
                    level = if (outcome.isSuccessLike) DiagLevel.INFO else DiagLevel.WARN,
                    tag = signDiagTag(runId),
                    message = "${outcome.displayName} → ${outcome.statusLabel}",
                    detail = outcome.diagnosticDetail()
                )
            }
        }
    }

    /**
     * 发起一批签到。
     *
     * 关键语义：**只清除 [targets] 里这些账号的结果**，其余账号的结论原样保留。
     * 所以「全部重试」只重发失败项，早已签到的账号不会被重发、也不会丢结果。
     */
    fun startBatch(targets: List<UserSession>) {
        val signTarget = target ?: return
        if (targets.isEmpty()) return

        val myGeneration = generation.value + 1
        generation.value = myGeneration
        job.value?.cancel()

        targets.forEach { results.remove(it.id) }
        running.value = true

        // 本批次的标识：历史记录与诊断日志靠它对齐（诊断 tag = "sign:<runId>"）
        val runId = HistoryStore.newRunId()
        val startedAtMs = System.currentTimeMillis()

        job.value = scope.launch {
            var wasCancelled = false
            try {
                engine.signAll(targets, signTarget).collect { event ->
                    when (event) {
                        is SignEvent.Started -> startedIds[event.sessionId] = true
                        is SignEvent.Finished -> results[event.result.sessionId] = event.result
                    }
                }
            } catch (cancelled: CancellationException) {
                wasCancelled = true
                // 手动取消（且没有新批次接管）：把在途账号标记为可重试的终态，
                // 避免卡片永远停在「签到中」
                if (generation.value == myGeneration) {
                    targets.forEach { session ->
                        if (results[session.id] == null) {
                            results[session.id] = SignResult(
                                sessionId = session.id,
                                displayName = session.displayName,
                                status = SignStatus.TIMEOUT,
                                reason = "已取消（请求未完成）",
                                attempts = 0,
                                evidence = AccountOutcome.CANCELLED_EVIDENCE
                            )
                        }
                    }
                }
                throw cancelled
            } finally {
                // 只有「仍是最新批次」才落历史：被新的重试批次接管时，
                // 这一批的结论已经被覆盖，再记一条只会让用户看到两份互相矛盾的记录
                if (generation.value == myGeneration) {
                    running.value = false
                    recordRun(
                        runId = runId,
                        startedAtMs = startedAtMs,
                        finishedAtMs = System.currentTimeMillis(),
                        signTarget = signTarget,
                        batch = targets,
                        cancelled = wasCancelled
                    )
                }
            }
        }
    }

    // 载入账号后立即开跑（进入结果页即批量签到）
    LaunchedEffect(targetUrl) {
        val all = repository.getAllSessions()
        val filtered = if (selectedUserIds.isNotEmpty()) {
            all.filter { selectedUserIds.contains(it.id) }
        } else {
            all
        }
        sessions.clear()
        sessions.addAll(filtered)
        if (target != null && filtered.isNotEmpty()) startBatch(filtered)
    }

    // 离开页面：取消所有在途请求
    DisposableEffect(Unit) {
        onDispose {
            job.value?.cancel()
            scope.cancel()
        }
    }

    val completed = sessions.mapNotNull { session -> results[session.id]?.let { session to it } }
    val inFlight = sessions.filter { results[it.id] == null }
    val retryable = sessions.filter { results[it.id]?.isRetryable == true }

    // 「签到成功」与「已签过」对用户是同一件事：这个人已经签上了
    val signedCount = completed.count {
        it.second.status == SignStatus.SUCCESS || it.second.status == SignStatus.ALREADY_SIGNED
    }
    val failedPairs = completed.filter {
        it.second.status != SignStatus.SUCCESS && it.second.status != SignStatus.ALREADY_SIGNED
    }
    val signedPairs = completed.filter {
        it.second.status == SignStatus.SUCCESS || it.second.status == SignStatus.ALREADY_SIGNED
    }
    val total = sessions.size

    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
        ) {
            if (invalidReason != null) {
                Spacer(Modifier.height(24.dp))
                EmptyStatePanel(
                    icon = Icons.Outlined.Link,
                    title = "签到链接无效",
                    subtitle = invalidReason
                )
                return@Column
            }

            if (sessions.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                EmptyStatePanel(
                    title = "没有可签到的账号",
                    subtitle = "请先在首页添加账号，再扫描签到二维码。"
                )
                return@Column
            }

            // ── 第一眼：成功数 ─────────────────────────────
            SignProgressHero(
                signed = signedCount,
                total = total,
                running = running.value,
                inFlight = inFlight.size,
                failed = failedPairs.size,
                onRetryAll = { startBatch(retryable) },
                retryableCount = retryable.size,
                onCancel = { job.value?.cancel() }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // ── 第二眼：失败列表（置顶、一行一人、带原因）──
                if (failedPairs.isNotEmpty()) {
                    item(key = "failed_header") {
                        ResultSectionHeader(
                            title = "未签上",
                            count = failedPairs.size,
                            icon = Icons.Outlined.ErrorOutline,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    items(failedPairs, key = { "fail_${it.first.id}" }) { (session, result) ->
                        FailedRow(session = session, result = result)
                    }
                }

                if (inFlight.isNotEmpty()) {
                    item(key = "inflight_header") {
                        ResultSectionHeader(
                            title = if (running.value) "进行中" else "未执行",
                            count = inFlight.size,
                            icon = Icons.Outlined.Schedule,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    items(inFlight, key = { "inflight_${it.id}" }) { session ->
                        InFlightRow(session = session, started = startedIds[session.id] == true)
                    }
                }

                // ── 已签到：默认折叠，不占地方 ──────────────
                if (signedPairs.isNotEmpty()) {
                    item(key = "signed_header") {
                        SignedSectionHeader(
                            count = signedPairs.size,
                            expanded = successExpanded.value,
                            onToggle = { successExpanded.value = !successExpanded.value }
                        )
                    }
                    if (successExpanded.value) {
                        items(signedPairs, key = { "ok_${it.first.id}" }) { (session, result) ->
                            SignedRow(session = session, result = result)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 顶部固定区：一个巨大的「成功 X / Y」。
 * 这是整页的主视觉，保证用户不用滚动就能看到结果。
 */
@Composable
private fun SignProgressHero(
    signed: Int,
    total: Int,
    running: Boolean,
    inFlight: Int,
    failed: Int,
    retryableCount: Int,
    onRetryAll: () -> Unit,
    onCancel: () -> Unit
) {
    val progress = if (total == 0) 0f else signed.toFloat() / total.toFloat()
    val allDone = !running && inFlight == 0

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$signed",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = " / $total",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = when {
                        running || inFlight > 0 -> "签到中…"
                        failed == 0 -> "全部签上"
                        else -> "$failed 人未签上"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        running || inFlight > 0 -> MaterialTheme.colorScheme.tertiary
                        failed == 0 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )

            // 只在需要时才出现的操作：未完成时给「取消」，全部结束后给「全部重试」
            when {
                running || inFlight > 0 -> {
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                        Text("取消")
                    }
                }
                retryableCount > 0 -> {
                    FilledTonalButton(
                        onClick = onRetryAll,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("全部重试（$retryableCount）")
                    }
                }
                allDone -> Unit
            }
        }
    }
}

/** 未签上的账号：一行一人，直接带原因 */
@Composable
private fun FailedRow(session: UserSession, result: SignResult) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = result.reason ?: "签到失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 在途/未执行的账号：一行一人 */
@Composable
private fun InFlightRow(session: UserSession, started: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = session.displayName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (started) "请求中…" else "等待中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 已签到的账号：一行一人，展开后才有细节 */
@Composable
private fun SignedRow(session: UserSession, result: SignResult) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = result.realName?.takeIf { it.isNotBlank() }
                    ?.let { name -> result.studentId?.takeIf { it.isNotBlank() }?.let { "$name · $it" } ?: name }
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = if (result.status == SignStatus.ALREADY_SIGNED) "已签过" else "已签到",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 已签到分组的可折叠表头 —— 默认折叠，不占用首屏 */
@Composable
private fun SignedSectionHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "已签到 $count 人",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}


@Composable
private fun ResultSectionHeader(title: String, count: Int, icon: ImageVector, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(count.toString(), style = MaterialTheme.typography.titleMedium, color = color)
    }
}

// ==================== 历史 / 诊断（纯函数，只给 [recordRun] 用） ====================

/** 请求主机（历史里只留主机，查询参数一律不进历史） */
private fun SignTarget.host(): String = url.toHttpUrlOrNull()?.host ?: SignTarget.CANVAS_HOST

/**
 * 单账号的诊断明细。写进 diag.log 前还会由 [HistoryStore.log] 统一脱敏，
 * 因此这里可以放心带上原始正文片段（引擎侧已截断到 ~200 字）。
 */
private fun AccountOutcome.diagnosticDetail(): String = buildString {
    append("HTTP ").append(httpStatus?.toString() ?: "-")
    evidence?.takeIf { it.isNotBlank() }?.let { append(" · evidence=").append(it) }
    append(" · attempts=").append(attempts)
    append(" · elapsed=").append(elapsedMs).append("ms")
    if (inferred) append(" · inferred=true（兜底推断，仅供校准）")
    reason?.takeIf { it.isNotBlank() }?.let { append(" · reason=").append(it) }
    rawSnippet?.takeIf { it.isNotBlank() }?.let { append(" · 原始正文=").append(it) }
}


