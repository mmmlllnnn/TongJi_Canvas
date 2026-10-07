package com.mln.tongji_canvas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
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
import com.mln.tongji_canvas.data.SessionRepository
import com.mln.tongji_canvas.data.SignEvent
import com.mln.tongji_canvas.data.SignInEngine
import com.mln.tongji_canvas.data.SignResult
import com.mln.tongji_canvas.data.SignStatus
import com.mln.tongji_canvas.data.SignTarget
import com.mln.tongji_canvas.data.SignTargetParse
import com.mln.tongji_canvas.data.UserSession
import com.mln.tongji_canvas.ui.components.EmptyStatePanel
import com.mln.tongji_canvas.ui.components.LoadingShimmerLines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 二维码/签到码有效窗口的**估算**值，仅用于顶部倒计时提示。
 * 服务端没有下发窗口长度，这里 180s 只是保守估计，不做任何自动取消。
 */
private const val ESTIMATED_WINDOW_SECONDS = 180L

/**
 * 批量签到结果页（纯 HTTP 实现）。
 *
 * 与旧实现的根本区别：这里 **没有 WebView、没有 CookieManager、没有任何全局可变状态**。
 * 每个账号由 [SignInEngine] 单独发一个带 `Cookie: _canvas_middle_session=…` 头的 HTTP GET，
 * 因此不存在「A 的请求带着 B 的会话发出」的串号问题。
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
    val startedAtMs = remember { mutableStateOf<Long?>(null) }
    val frozenElapsedMs = remember { mutableStateOf<Long?>(null) }
    val nowMs = remember { mutableStateOf(System.currentTimeMillis()) }

    val targetParse = remember(targetUrl) { SignTarget.parse(targetUrl) }
    val target = (targetParse as? SignTargetParse.Valid)?.target
    val invalidReason = (targetParse as? SignTargetParse.Invalid)?.reason

    fun startBatch(targets: List<UserSession>) {
        val signTarget = target ?: return
        if (targets.isEmpty()) return

        val myGeneration = generation.value + 1
        generation.value = myGeneration
        job.value?.cancel()

        targets.forEach { results.remove(it.id) }
        if (startedAtMs.value == null) startedAtMs.value = System.currentTimeMillis()
        frozenElapsedMs.value = null
        running.value = true

        job.value = scope.launch {
            try {
                engine.signAll(targets, signTarget).collect { event ->
                    when (event) {
                        is SignEvent.Started -> startedIds[event.sessionId] = true
                        is SignEvent.Finished -> results[event.result.sessionId] = event.result
                    }
                }
            } catch (cancelled: CancellationException) {
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
                                evidence = "用户取消"
                            )
                        }
                    }
                }
                throw cancelled
            } finally {
                if (generation.value == myGeneration) {
                    frozenElapsedMs.value = startedAtMs.value?.let {
                        (System.currentTimeMillis() - it).coerceAtLeast(0L)
                    }
                    running.value = false
                }
            }
        }
    }

    // 载入账号后立即开跑（与旧行为一致：进入结果页即批量签到）
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

    // 秒级时钟，用于「耗时」与「窗口倒计时」
    LaunchedEffect(Unit) {
        while (true) {
            nowMs.value = System.currentTimeMillis()
            delay(1_000L)
        }
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

    val startedCount = sessions.count { startedIds[it.id] == true }
    val successCount = completed.count { it.second.status == SignStatus.SUCCESS }
    val alreadyCount = completed.count { it.second.status == SignStatus.ALREADY_SIGNED }
    val failureCount = completed.size - successCount - alreadyCount

    val startedAt = startedAtMs.value
    val liveElapsedMs = startedAt?.let { (nowMs.value - it).coerceAtLeast(0L) } ?: 0L
    val displayElapsedMs = if (running.value) liveElapsedMs else (frozenElapsedMs.value ?: liveElapsedMs)
    val windowRemainingMs = (ESTIMATED_WINDOW_SECONDS * 1_000L - liveElapsedMs).coerceAtLeast(0L)

    val sections = listOf(
        ResultSection(
            "签到成功", Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary,
            completed.filter { it.second.status == SignStatus.SUCCESS }
        ),
        ResultSection(
            "已签过", Icons.Outlined.DoneAll, MaterialTheme.colorScheme.tertiary,
            completed.filter { it.second.status == SignStatus.ALREADY_SIGNED }
        ),
        ResultSection(
            "未选课", Icons.Outlined.School, MaterialTheme.colorScheme.secondary,
            completed.filter { it.second.isNotEnrolled }
        ),
        ResultSection(
            "签到失败", Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error,
            completed.filter { it.second.status == SignStatus.FAILED && !it.second.isNotEnrolled }
        ),
        ResultSection(
            "凭据失效", Icons.Outlined.Lock, MaterialTheme.colorScheme.error,
            completed.filter { it.second.status == SignStatus.CREDENTIAL_EXPIRED }
        ),
        ResultSection(
            "未知错误", Icons.AutoMirrored.Outlined.HelpOutline, MaterialTheme.colorScheme.secondary,
            completed.filter { it.second.status == SignStatus.UNKNOWN_ERROR }
        ),
        ResultSection(
            "网络失败", Icons.Outlined.WifiOff, MaterialTheme.colorScheme.error,
            completed.filter {
                it.second.status == SignStatus.NETWORK_ERROR || it.second.status == SignStatus.TIMEOUT
            }
        )
    ).filter { it.items.isNotEmpty() }

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
                            text = targetUrl.ifBlank { "未获取到签到链接" },
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
            if (invalidReason != null) {
                EmptyStatePanel(
                    icon = Icons.Outlined.Link,
                    title = "签到链接无效",
                    subtitle = invalidReason
                )
            } else {
                BatchWindowCard(
                    total = sessions.size,
                    started = startedCount,
                    completed = completed.size,
                    success = successCount,
                    already = alreadyCount,
                    failure = failureCount,
                    elapsedMs = displayElapsedMs,
                    windowRemainingMs = windowRemainingMs,
                    running = running.value,
                    hasStarted = startedAtMs.value != null,
                    targetUrl = targetUrl
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { startBatch(retryable) },
                        enabled = !running.value && retryable.isNotEmpty()
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("只重试失败项 (${retryable.size})")
                    }
                    if (running.value) {
                        TextButton(onClick = { job.value?.cancel() }) {
                            Text("取消")
                        }
                    }
                }

                if (inFlight.isNotEmpty()) {
                    ResultSectionHeader(
                        title = if (running.value) "进行中" else "未执行",
                        count = inFlight.size,
                        icon = Icons.Outlined.Schedule,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    inFlight.forEach { session ->
                        SignResultCard(
                            session = session,
                            result = null,
                            started = startedIds[session.id] == true,
                            onRetry = null
                        )
                    }
                }

                if (sessions.isEmpty()) {
                    EmptyStatePanel(
                        title = "没有可签到的账号",
                        subtitle = "请先在首页添加账号，再扫描签到二维码。"
                    )
                }

                sections.forEach { section ->
                    ResultSectionHeader(
                        title = section.title,
                        count = section.items.size,
                        icon = section.icon,
                        color = section.color
                    )
                    section.items.forEach { (session, result) ->
                        SignResultCard(
                            session = session,
                            result = result,
                            started = true,
                            onRetry = if (result.isRetryable && !running.value) {
                                { startBatch(listOf(session)) }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }
}

private data class ResultSection(
    val title: String,
    val icon: ImageVector,
    val color: Color,
    val items: List<Pair<UserSession, SignResult>>
)

@Composable
private fun BatchWindowCard(
    total: Int,
    started: Int,
    completed: Int,
    success: Int,
    already: Int,
    failure: Int,
    elapsedMs: Long,
    windowRemainingMs: Long,
    running: Boolean,
    hasStarted: Boolean,
    targetUrl: String
) {
    val progress = if (total == 0) 0f else completed.toFloat() / total.toFloat()
    Surface(
        shape = RoundedCornerShape(32.dp),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("签到进度", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when {
                        running -> "进行中"
                        hasStarted -> "已结束"
                        else -> "准备中"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (running) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatText("已发起", started.toString())
                StatText("已完成", completed.toString())
                StatText("成功", (success + already).toString(), MaterialTheme.colorScheme.primary)
                StatText("失败", failure.toString(), MaterialTheme.colorScheme.error)
            }
            if (already > 0) {
                Text(
                    text = "其中 $success 人本次签到、$already 人此前已签过",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "耗时 ${formatDuration(elapsedMs)}　·　" +
                    if (windowRemainingMs > 0) {
                        "窗口剩余 ${formatCountdown(windowRemainingMs)}（估算）"
                    } else {
                        "窗口可能已结束"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(text = targetUrl, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) }
            )
        }
    }
}

@Composable
private fun StatText(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

@Composable
private fun SignResultCard(
    session: UserSession,
    result: SignResult?,
    started: Boolean,
    onRetry: (() -> Unit)?
) {
    val (statusLabel, statusColor, statusIcon) = if (result == null) {
        Triple(
            if (started) "签到中" else "排队中",
            MaterialTheme.colorScheme.tertiary,
            Icons.Outlined.Schedule
        )
    } else {
        statusVisual(result)
    }

    val identity = listOfNotNull(
        result?.realName,
        result?.studentId?.let { "学号 $it" }
    ).joinToString("　")

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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(session.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = identity.ifEmpty {
                            if (session.accessToken.isNullOrEmpty()) "未保存会话凭据" else "已保存会话凭据"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

            if (result == null) {
                LoadingShimmerLines(lines = 2, modifier = Modifier.fillMaxWidth())
            } else {
                result.reason?.let { reason ->
                    Text(
                        text = "原因：$reason",
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor
                    )
                }
                result.courseName?.let { course ->
                    Text(
                        text = "课程：$course",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "耗时 ${formatDuration(result.elapsedMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (result.attempts > 1) {
                        Text(
                            text = "重试 ${result.attempts - 1} 次",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    result.httpStatus?.let { code ->
                        Text(
                            text = "HTTP $code",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (result.inferred) {
                    Text(
                        text = "兜底判定：未匹配到已知文案，请以教务系统为准",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (result.showsRawSnippet) {
                    Text(
                        text = "原始响应：${result.rawSnippet}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onRetry != null) {
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun statusVisual(result: SignResult): Triple<String, Color, ImageVector> = when {
    result.status == SignStatus.SUCCESS ->
        Triple("签到成功", MaterialTheme.colorScheme.primary, Icons.Outlined.CheckCircle)

    result.status == SignStatus.ALREADY_SIGNED ->
        Triple("已签过", MaterialTheme.colorScheme.tertiary, Icons.Outlined.DoneAll)

    result.isNotEnrolled ->
        Triple("未选课", MaterialTheme.colorScheme.secondary, Icons.Outlined.School)

    result.status == SignStatus.FAILED ->
        Triple("签到失败", MaterialTheme.colorScheme.error, Icons.Outlined.ErrorOutline)

    result.status == SignStatus.CREDENTIAL_EXPIRED ->
        Triple("凭据失效", MaterialTheme.colorScheme.error, Icons.Outlined.Lock)

    result.status == SignStatus.UNKNOWN_ERROR ->
        Triple("未知错误", MaterialTheme.colorScheme.secondary, Icons.AutoMirrored.Outlined.HelpOutline)

    else ->
        Triple("网络失败", MaterialTheme.colorScheme.error, Icons.Outlined.WifiOff)
}

private fun formatDuration(ms: Long): String =
    if (ms < 10_000L) {
        String.format(Locale.ROOT, "%.1fs", ms / 1000.0)
    } else {
        String.format(Locale.ROOT, "%.0fs", ms / 1000.0)
    }

private fun formatCountdown(ms: Long): String {
    val totalSeconds = ms / 1_000L
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
