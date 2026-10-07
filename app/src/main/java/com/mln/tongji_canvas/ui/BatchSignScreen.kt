package com.mln.tongji_canvas.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Warning
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.mln.tongji_canvas.ui.qr.QrCameraPreview
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

    // ── 页内重新扫码 ────────────────────────────────────────────────────
    // 二维码约 3s 刷一次、rollCallToken 每次都不一样，所以「全部重试」**不能**复用扫描时抓到的
    // 旧 URL：旧 token 早已作废，照着旧 URL 重发只会再拿一次「二维码已过期」。
    // 这里把相机直接嵌在页面顶部：点「全部重试」→ 就地出取景框 → 扫到新码立刻收起并用新 token 重发。
    var isScanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var cameraSession by remember { mutableStateOf(0) }
    // 纯 Kotlin 守卫（刻意不用 Compose 状态）：检测回调到重组生效之间会有几帧延迟，
    // 同一帧内的重复检测必须被同步挡掉，否则一次扫码可能触发多次重发。
    var scanHandled = remember { booleanArrayOf(false) }

    /** 打开取景框，等待用户扫一个新码 */
    fun beginRescan() {
        scanError = null
        scanHandled[0] = false
        cameraSession += 1
        isScanning = true
    }

    /** 收起取景框（用户取消 / 扫码完成） */
    fun endRescan() {
        isScanning = false
        scanError = null
        scanHandled[0] = false
    }

    /**
     * 发起一批签到。
     *
     * 关键语义：**只清除 [targets] 里这些账号的结果**，其余账号的结论原样保留。
     * 所以「全部重试」只重发失败项，早已签到的账号不会被重发、也不会丢结果。
     *
     * [signTarget] 由调用方给出：首次进页用扫码带进来的目标，
     * 「全部重试」用**刚刚重新扫到**的新目标（新 rollCallToken）。
     */
    fun startBatch(targets: List<UserSession>, signTarget: SignTarget) {
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

    /**
     * 扫到新码：解析 → 校验课程 → **只重发失败账号**。
     *
     * 已经签上的账号其 [results] 条目原封不动，所以它们的行、结论、姓名学号都不会变，
     * 也不会被重新发一次请求。
     */
    fun handleScanResult(rawUrl: String) {
        if (scanHandled[0]) return
        val scanned = when (val parsed = parseRescanTarget(rawUrl)) {
            is SignTargetParse.Valid -> parsed.target
            is SignTargetParse.Invalid -> {
                scanError = "${parsed.reason}，请对准签到二维码重试"
                return
            }
        }

        // 扫到别的课的码：用错 token 重发没有意义，提示用户对准本堂课的二维码
        val original = target
        if (original != null && scanned.courseCode != original.courseCode) {
            scanError = "扫到的是其他课程的二维码，请重新扫描本堂课的签到码"
            return
        }

        val retryTargets = retryableAccounts(sessions, results)
        scanHandled[0] = true
        endRescan()
        if (retryTargets.isEmpty()) {
            scanError = "没有需要重试的账号"
            return
        }
        // 新二维码 + 新 rollCallToken：这一批才算「有效重试」，
        // 历史照常记录本批**实际重发**的账号（不是全班），诊断日志同样只覆盖这些账号。
        startBatch(retryTargets, scanned)
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
        val signTarget = target
        if (signTarget != null && filtered.isNotEmpty()) startBatch(filtered, signTarget)
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
    // 可重试 = 已有终态结论且允许重试（已签上 / 未选课 / 在途都不在内）—— 纯逻辑见 BatchSignRescan.kt
    val retryable = retryableAccounts(sessions, results)
    val expiredNames = expiredQrAccountNames(sessions, results)

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

            // ── 页内扫码：点「全部重试」后就地弹出取景框 ──────
            // 只有扫到**新**二维码（新 rollCallToken）才会重发失败账号，详见 handleScanResult
            InlineRescanPanel(
                visible = isScanning,
                accountCount = retryable.size,
                errorMessage = scanError,
                cameraKey = cameraSession,
                onDetected = { value -> handleScanResult(value) },
                onCancel = { endRescan() }
            )

            // ── 第一眼：成功数 ─────────────────────────────
            SignProgressHero(
                signed = signedCount,
                total = total,
                running = running.value,
                inFlight = inFlight.size,
                failed = failedPairs.size,
                // 旧 rollCallToken 已作废：必须先扫一个新码，再用新 token 重发失败项
                onRetryAll = { beginRescan() },
                retryableCount = retryable.size,
                onCancel = { job.value?.cancel() },
                actionDisabled = isScanning
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // ── 二维码过期的专门提示（普通失败行看不出「必须重扫」）──
                if (expiredNames.isNotEmpty()) {
                    item(key = "qr_expired_hint") {
                        QrExpiredHint(names = expiredNames)
                    }
                }

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
 *
 * @param actionDisabled 取景框已打开（正在等扫码）时置 true：避免用户重复点「重试」再开一次相机，
 *   同时把「取消」让给取景框里的按钮，不同时出现两个取消入口
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
    onCancel: () -> Unit,
    actionDisabled: Boolean = false
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

            // 只在需要时才出现的操作：未完成时给「取消」，全部结束后给「重新扫码并重试」
            when {
                // 取景框开着：操作都在取景框里，这里让位，避免两个「取消」
                actionDisabled -> Unit
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
                        // 图标用扫码而不是刷新：按下去先开相机，扫到新码才会重发
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("重新扫码并重试（$retryableCount）")
                    }
                }
                allDone -> Unit
            }
        }
    }
}

/**
 * 二维码过期的专门提示。
 *
 * 这类失败不能和「未选课」这种确定性失败混在一起：用户唯一该做的事是**重新扫一次码**，
 * 用旧码重试永远签不上。
 */
@Composable
private fun QrExpiredHint(names: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "二维码已过期，需要重新扫码",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (names.isEmpty()) {
                        "签到二维码每约 3 秒刷新一次，旧码的 rollCallToken 已作废。"
                    } else {
                        "${names.joinToString("、")} 因二维码过期未签上：旧码的 rollCallToken 已作废。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "点上方按钮会打开相机，扫到新二维码后自动重发这些账号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 页内取景框：扫码拿**新**的 rollCallToken。
 *
 * 用 [AnimatedVisibility] 而不是新开一个页面，是因为产品要求「在页面内完成扫码」：
 * 结果列表始终留在原位，用户不会丢上下文。扫到码就自动收起（[visible] 变 false），
 * 相机随组件一起离开组合并释放（见 [QrCameraPreview] 的 DisposableEffect）。
 */
@Composable
private fun InlineRescanPanel(
    visible: Boolean,
    accountCount: Int,
    errorMessage: String?,
    cameraKey: Int,
    onDetected: (String) -> Unit,
    onCancel: () -> Unit
) {
    var torchEnabled by remember { mutableStateOf(false) }

    // 收起时顺手关手电：下次打开是干净状态
    LaunchedEffect(visible) {
        if (!visible) torchEnabled = false
    }

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text("重新扫描签到二维码", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "扫到新码后自动重发 $accountCount 个未签上的账号",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    // 相机本体是全站共用实现：全屏扫码页用的是同一个 QrCameraPreview
                    QrCameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        onDetected = onDetected,
                        torchEnabled = torchEnabled,
                        cameraKey = cameraKey
                    )
                }

                Text(
                    text = errorMessage
                        ?: "对准屏幕上的签到二维码；旧码已失效，必须扫当前这一张。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (errorMessage != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { torchEnabled = !torchEnabled },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (torchEnabled) "关闭手电" else "开启手电")
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("取消重试")
                    }
                }
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


