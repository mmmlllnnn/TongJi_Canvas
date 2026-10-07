package com.mln.tongji_canvas.data

import android.webkit.CookieManager
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 凭据（Canvas Cookie）探测结果。
 */
sealed interface ProbeResult {
    /** 凭据有效，并拿到真实身份。学号可能为空串（服务端只回了姓名）。 */
    data class Valid(val realName: String, val studentId: String) : ProbeResult

    /**
     * 服务端明确拒绝鉴权（401 / 403）：凭据已失效。
     *
     * 注意：302 → iam.tongji.edu.cn **不**归入这里。实测同一份有效凭据会在
     * 200 与 302 之间抖动（中间件限流/压力下先跳登录页），302 无法证明凭据无效，
     * 只归为 [Unknown]。权威的失效判定由实际签到请求给出。
     */
    data object Expired : ProbeResult

    /** 超过预算时间仍未拿到结论。 */
    data object Timeout : ProbeResult

    /** 网络异常（DNS / 连接 / TLS 等）。 */
    data object NetworkError : ProbeResult

    /** 请求有响应，但内容无法判定（例如既没有身份信息也不是明确的失效信号）。 */
    data object Unknown : ProbeResult
}

/**
 * 轻量身份探测：用「已知 courseCode + 伪造 rollCallToken」请求 forscan 接口。
 *
 * 实测（HTTP 200）正文形如：
 * `点名签到 未知错误 学号/工号：2551830 签到人员：姚董毅 签到课程：无 ...`
 * 凭据失效时返回 302 → https://iam.tongji.edu.cn/idp/oauth2/authorize...
 *
 * 该对象里参与单元测试的成员（[parseIdentity] / [normalizeCookieHeader] / [hasSessionCookie]）
 * 全部是纯函数，类初始化不触碰任何 Android API。
 */
object CredentialProbe {
    /** 整体探测预算：用户最多等待约 8 秒。 */
    const val DEFAULT_TIMEOUT_MS = 8_000L

    private const val CANVAS_ORIGIN = "https://canvas.tongji.edu.cn"
    private const val PROBE_PATH = "/lms/mobile/forscan"
    private const val FAKE_ROLL_CALL_TOKEN = "0000000000"
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

    /** 已知可用的 courseCode：117029（已选课）与 102714（未选课）实测都能返回身份页。 */
    private val PROBE_COURSE_CODES = listOf("117029", "102714")

    private val STUDENT_ID_REGEXES = listOf(
        Regex("""学号\s*/\s*工号\s*[:：]\s*([0-9A-Za-z]{4,20})"""),
        Regex("""学号\s*[:：]\s*([0-9A-Za-z]{4,20})""")
    )
    private val NAME_REGEX = Regex("""签到人员\s*[:：]\s*([^\s<&"'，,;；]+)""")

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 需要自己看到 302 → iam，才能判定凭据失效
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    data class Identity(val realName: String, val studentId: String)

    /** 粗略清洗用户粘贴的 Cookie：去掉 `Cookie:` 前缀、折叠空白。 */
    fun normalizeCookieHeader(raw: String?): String {
        var value = raw?.trim().orEmpty()
        if (value.regionMatches(0, "cookie:", 0, 7, ignoreCase = true)) {
            value = value.substring(7).trim()
        }
        return value.replace(Regex("""\s+"""), " ").trim().trimEnd(';').trim()
    }

    /** 是否包含 Canvas 会话 Cookie（没有它探测必然失败）。 */
    fun hasSessionCookie(cookieHeader: String?): Boolean =
        SessionIdentityLogic.canvasSessionCookieValue(cookieHeader) != null

    /** 从 forscan 返回正文里解析身份；解析不到返回 null（纯函数）。 */
    fun parseIdentity(body: String?): Identity? {
        val html = body?.trim().orEmpty()
        if (html.isEmpty()) return null
        val text = html
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&colon;", ":")
        val studentId = STUDENT_ID_REGEXES
            .firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.trim() }
            ?.takeIf { it.isNotEmpty() }
        val realName = NAME_REGEX.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            studentId != null && realName != null -> Identity(realName, studentId)
            studentId != null -> Identity(studentId, studentId)
            realName != null -> Identity(realName, "")
            else -> null
        }
    }

    /**
     * 探测凭据。[cookieHeader] 为完整 Cookie 头（至少包含 `_canvas_middle_session`）。
     * [timeoutMs] 是整体预算：先试 117029，未得出结论且仍有剩余预算时再试 102714。
     */
    suspend fun probe(cookieHeader: String?, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ProbeResult =
        withContext(Dispatchers.IO) {
            val cookie = normalizeCookieHeader(cookieHeader)
            if (cookie.isEmpty()) return@withContext ProbeResult.Unknown

            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1_000L)
            // 一个都没来得及尝试 → 超时；否则保留最后一次的确切结论
            var last: ProbeResult = ProbeResult.Timeout
            for (courseCode in PROBE_COURSE_CODES) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining < 500L) break
                when (val result = attempt(cookie, courseCode, remaining)) {
                    is ProbeResult.Valid -> return@withContext result
                    ProbeResult.Expired -> return@withContext ProbeResult.Expired
                    ProbeResult.Timeout -> {
                        last = ProbeResult.Timeout
                        break
                    }
                    ProbeResult.NetworkError -> {
                        last = ProbeResult.NetworkError
                        break
                    }
                    ProbeResult.Unknown -> last = ProbeResult.Unknown
                }
            }
            last
        }

    private fun attempt(cookie: String, courseCode: String, timeoutMs: Long): ProbeResult {
        val request = Request.Builder()
            .url("$CANVAS_ORIGIN$PROBE_PATH?courseCode=$courseCode&rollCallToken=$FAKE_ROLL_CALL_TOKEN")
            .get()
            .header("Cookie", cookie)
            .header("User-Agent", MOBILE_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val client = baseClient.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs.coerceAtMost(4_000L), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            return ProbeResult.Timeout
        } catch (e: InterruptedIOException) {
            return ProbeResult.Timeout
        } catch (e: IOException) {
            return ProbeResult.NetworkError
        }

        return response.use { resp ->
            val isRedirect = resp.code in 300..399
            when {
                // 探测阶段【不能】把 302→IAM 当作「凭据失效」。
                // 实测教训：同一份有效凭据在数十秒内会在 200 与 302→IAM 之间抖动
                // （/lms/mobile/forscan 的中间件在限流/压力下会先跳登录页），
                // 因此 302 只说明「中间件要求走 OAuth」，不能证明凭据无效。
                // 真正的失效结论改由实际签到请求给出（那时 302 才是权威信号）。
                isRedirect -> ProbeResult.Unknown
                // 401/403 是服务端明确的鉴权拒绝，语义比 302 强得多
                resp.code == 401 || resp.code == 403 -> ProbeResult.Expired
                !resp.isSuccessful -> ProbeResult.Unknown
                else -> {
                    val body = try {
                        resp.body?.string().orEmpty()
                    } catch (e: IOException) {
                        return@use ProbeResult.NetworkError
                    }
                    parseIdentity(body)?.let { ProbeResult.Valid(it.realName, it.studentId) }
                        ?: ProbeResult.Unknown
                }
            }
        }
    }
}

/** 导入流程阶段（用于登录页与手动录入两条路径的等待反馈）。 */
enum class ImportStage { READING_COOKIE, PROBING, SAVING }

/** 凭据导入需要用户拍板的原因。 */
enum class ImportDecisionReason { TIMED_OUT, EXPIRED, NETWORK_ERROR, UNKNOWN, NO_COOKIE }

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Verifying(val stage: ImportStage) : ImportUiState
    data class Decision(val reason: ImportDecisionReason, val detail: String? = null) : ImportUiState
    data class Saved(val displayName: String, val verified: Boolean) : ImportUiState
}

/**
 * 登录页凭据导入流程。
 *
 * 状态挂在应用级单例上（而不是 Compose 作用域），因此：
 * - 用户在「验证中…」时返回或旋转屏幕，探测/保存协程不会被取消，凭据不会悄悄丢失；
 * - 结果会保留在 [state] 里，登录页重新组合后会继续提示或导航，而不是静默丢弃。
 */
object CredentialImportFlow {
    private const val CANVAS_URL = "https://canvas.tongji.edu.cn"
    private const val COOKIE_WAIT_MS = 5_000L
    private const val PROBE_TIMEOUT_MS = CredentialProbe.DEFAULT_TIMEOUT_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = mutableStateOf<ImportUiState>(ImportUiState.Idle)

    /** Compose 可观察状态。 */
    val state: State<ImportUiState> get() = _state

    // 这三个字段由探测协程（IO 线程）写入、由界面线程读取，用 @Volatile 保证可见性
    @Volatile private var pendingCookies: String? = null
    @Volatile private var pendingDisplayName: String? = null
    @Volatile private var pendingStudentId: String? = null
    private var job: Job? = null

    /**
     * 导入代次：每次 begin/discard 自增。已在进行中的旧协程发现代次不匹配时
     * 不会再改写状态、也不会保存——否则用户「放弃/切换模式」后旧结果会诈尸。
     */
    @Volatile private var generation = 0

    /** 未经验证时的兜底昵称（沿用旧版命名，保证唯一、不误合并）。 */
    fun fallbackDisplayName(): String = "用户${System.currentTimeMillis()}"

    private fun publish(expectedGeneration: Int, state: ImportUiState) {
        if (generation == expectedGeneration) _state.value = state
    }

    /**
     * 登录页检测到登录成功后调用：读取 Cookies → 探测 → 有效则直接保存。
     * 已有进行中/待决策/已保存的结果时忽略重复触发。
     */
    fun begin(repository: SessionRepository) {
        if (_state.value !is ImportUiState.Idle) return
        val myGeneration = ++generation
        job?.cancel()
        pendingCookies = null
        pendingDisplayName = null
        pendingStudentId = null
        _state.value = ImportUiState.Verifying(ImportStage.READING_COOKIE)
        job = scope.launch {
            try {
                val cookies = awaitCanvasCookies()
                if (generation != myGeneration) return@launch
                if (cookies.isNullOrBlank()) {
                    publish(myGeneration, ImportUiState.Decision(ImportDecisionReason.NO_COOKIE))
                    return@launch
                }
                pendingCookies = cookies
                publish(myGeneration, ImportUiState.Verifying(ImportStage.PROBING))
                when (val result = CredentialProbe.probe(cookies, PROBE_TIMEOUT_MS)) {
                    is ProbeResult.Valid -> {
                        val displayName = SessionIdentityLogic
                            .displayNameFor(result.realName, result.studentId)
                            .ifBlank { fallbackDisplayName() }
                        pendingDisplayName = displayName
                        pendingStudentId = result.studentId.takeIf { it.isNotBlank() }
                        persist(
                            expectedGeneration = myGeneration,
                            repository = repository,
                            cookieHeader = cookies,
                            displayName = displayName,
                            studentId = pendingStudentId,
                            verifiedAt = System.currentTimeMillis()
                        )
                    }
                    ProbeResult.Expired ->
                        publish(myGeneration, ImportUiState.Decision(ImportDecisionReason.EXPIRED))
                    ProbeResult.Timeout ->
                        publish(myGeneration, ImportUiState.Decision(ImportDecisionReason.TIMED_OUT))
                    ProbeResult.NetworkError ->
                        publish(myGeneration, ImportUiState.Decision(ImportDecisionReason.NETWORK_ERROR))
                    ProbeResult.Unknown ->
                        publish(myGeneration, ImportUiState.Decision(ImportDecisionReason.UNKNOWN))
                }
            } catch (t: Throwable) {
                publish(myGeneration, ImportUiState.Decision(ImportDecisionReason.UNKNOWN, t.message))
            }
        }
    }

    /** 用户在超时/失效等提示里选择「仍要保存」：凭据照存，但标记为未验证。 */
    fun commitUnverified(repository: SessionRepository) {
        val cookies = pendingCookies
        if (cookies.isNullOrBlank()) {
            _state.value = ImportUiState.Decision(ImportDecisionReason.NO_COOKIE)
            return
        }
        if (_state.value !is ImportUiState.Decision) return
        val myGeneration = generation
        _state.value = ImportUiState.Verifying(ImportStage.SAVING)
        job = scope.launch {
            persist(
                expectedGeneration = myGeneration,
                repository = repository,
                cookieHeader = cookies,
                displayName = pendingDisplayName?.takeIf { it.isNotBlank() } ?: fallbackDisplayName(),
                studentId = pendingStudentId,
                verifiedAt = null
            )
        }
    }

    /** 用户放弃保存 / 关闭提示 / 切换浏览器模式：作废本次导入，回到初始状态。 */
    fun discard() {
        generation++
        job?.cancel()
        pendingCookies = null
        pendingDisplayName = null
        pendingStudentId = null
        _state.value = ImportUiState.Idle
    }

    /** 保存结果已被界面消费（已提示并导航），回到初始状态。 */
    fun acknowledge() {
        generation++
        job?.cancel()
        pendingCookies = null
        pendingDisplayName = null
        pendingStudentId = null
        _state.value = ImportUiState.Idle
    }

    private suspend fun persist(
        expectedGeneration: Int,
        repository: SessionRepository,
        cookieHeader: String,
        displayName: String,
        studentId: String?,
        verifiedAt: Long?
    ) {
        if (generation != expectedGeneration) return
        val saved = try {
            repository.upsertSession(
                displayName = displayName,
                tokens = OAuth2Tokens(
                    accessToken = cookieHeader,
                    refreshToken = null,
                    expiresIn = 3600L
                ),
                studentId = studentId,
                verifiedAt = verifiedAt
            )
        } catch (t: Throwable) {
            publish(expectedGeneration, ImportUiState.Decision(ImportDecisionReason.UNKNOWN, t.message))
            return
        }
        pendingCookies = null
        pendingDisplayName = null
        pendingStudentId = null
        publish(expectedGeneration, ImportUiState.Saved(saved.displayName, verifiedAt != null))
    }

    /**
     * 读取 Canvas Cookies：登录跳转与 Set-Cookie 落盘可能有毫秒级竞态，
     * 因此短轮询等待（总预算 [COOKIE_WAIT_MS]），而不是固定倒计时 5 秒。
     */
    private suspend fun awaitCanvasCookies(): String? {
        val deadline = System.currentTimeMillis() + COOKIE_WAIT_MS
        var fallback: String? = null
        var waited = 0L
        while (true) {
            if (waited > 0L) delay(waited)
            val cookies = readCanvasCookieHeader()
            if (cookies != null && CredentialProbe.hasSessionCookie(cookies)) return cookies
            if (cookies != null) fallback = cookies
            if (System.currentTimeMillis() >= deadline) return fallback
            waited = if (waited == 0L) 250L else (waited * 2).coerceAtMost(1_000L)
        }
    }

    private suspend fun readCanvasCookieHeader(): String? = withContext(Dispatchers.Main) {
        try {
            CookieManager.getInstance().getCookie(CANVAS_URL)?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            null
        }
    }
}
