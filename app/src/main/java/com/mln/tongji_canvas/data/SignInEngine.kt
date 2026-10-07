package com.mln.tongji_canvas.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * HTTP 签到引擎。
 *
 * 设计契约（均已实测验证）：
 * - 每个账号 **一个 HTTP GET**：`https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=…&rollCallToken=…`
 * - 唯一的认证方式是请求头 `Cookie: _canvas_middle_session=<value>`
 * - 不使用 WebView、不使用 CookieManager、不写任何全局状态 —— 这是消除「Cookie 串号」的关键
 * - 302 → iam.tongji.edu.cn 表示凭据失效
 * - 不轮换也不写回 Cookie（原凭据不会被作废）
 *
 * 可靠性：
 * - 共享单个 [OkHttpClient]；连接/读超时各 10s；单账号总预算 15s（[withTimeoutOrNull]）
 * - 并发上限 [maxConcurrency]（默认 4）
 * - 只对「瞬时失败」指数退避重试，最多 [maxRetries] 次（默认 2）；业务性失败不重试
 * - 协程取消即停：取消收集 [signAll] 返回的 Flow 会取消所有在途请求（OkHttp call 会被 cancel）
 */
class SignInEngine(
    private val client: OkHttpClient = defaultSignClient(),
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    private val perAccountTimeoutMs: Long = DEFAULT_PER_ACCOUNT_TIMEOUT_MS
) {

    /**
     * 并发地对 [sessions] 逐个签到，边完成边推送 [SignEvent]。
     *
     * 返回的 Flow 在收集结束时（或收集方取消时）保证所有子任务都已完成/已取消。
     */
    fun signAll(sessions: List<UserSession>, target: SignTarget): Flow<SignEvent> = channelFlow {
        // 每个批次一个闸门：绝不允许一次性放 N 个请求出去
        val gate = Semaphore(maxConcurrency.coerceAtLeast(1))
        sessions.forEach { session ->
            launch(Dispatchers.IO) {
                val result = try {
                    gate.withPermit {
                        // 拿到闸门才广播 Started：UI 的「已发起」= 真正发出去的请求数
                        send(SignEvent.Started(session.id))
                        signOne(session, target)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    SignResult(
                        sessionId = session.id,
                        displayName = session.displayName,
                        status = SignStatus.UNKNOWN_ERROR,
                        reason = "内部错误：${t.javaClass.simpleName}"
                    )
                }
                send(SignEvent.Finished(result))
            }
        }
    }

    /**
     * 单个账号签到（含重试），永不抛出（除协程取消外）。
     */
    suspend fun signOne(session: UserSession, target: SignTarget): SignResult {
        val cookie = extractSessionCookie(session.accessToken)
            ?: return SignResult(
                sessionId = session.id,
                displayName = session.displayName,
                status = SignStatus.CREDENTIAL_EXPIRED,
                reason = "本地凭据缺失或无效（未找到 $SESSION_COOKIE_NAME），请重新登录",
                attempts = 0,
                evidence = "本地凭据校验"
            )

        val startedNs = System.nanoTime()
        var attempts = 0

        val outcome = withTimeoutOrNull(perAccountTimeoutMs) {
            var last = SignResult(
                sessionId = session.id,
                displayName = session.displayName,
                status = SignStatus.UNKNOWN_ERROR,
                reason = "未发出请求"
            )
            while (true) {
                attempts++
                last = attemptOnce(session, target, cookie, attempts)
                if (!shouldRetry(last) || attempts > maxRetries) break
                delay(backoffMs(attempts))
            }
            last
        }

        val elapsed = elapsedMsSince(startedNs)
        return outcome?.copy(elapsedMs = elapsed, attempts = attempts)
            ?: SignResult(
                sessionId = session.id,
                displayName = session.displayName,
                status = SignStatus.TIMEOUT,
                reason = "单账号超时（${perAccountTimeoutMs / 1000}s）",
                elapsedMs = elapsed,
                attempts = attempts.coerceAtLeast(1),
                evidence = "withTimeout"
            )
    }

    private suspend fun attemptOnce(
        session: UserSession,
        target: SignTarget,
        cookieHeader: String,
        attemptNo: Int
    ): SignResult {
        val startedNs = System.nanoTime()
        // 每账号固定且各不相同的 UA（按 sessionId 稳定哈希取），避免整批同一指纹
        val request = Request.Builder()
            .url(target.url)
            .header("Cookie", cookieHeader)
            .header("User-Agent", userAgentFor(session.id))
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
            .get()
            .build()

        return try {
            client.newCall(request).awaitResponse().use { response ->
                val bodyText = try {
                    response.body?.bytes()?.let { decodeUtf8(it) }
                } catch (io: IOException) {
                    null
                }
                val parsed = SignResponseParser.parse(
                    httpStatus = response.code,
                    location = response.header("Location"),
                    body = bodyText
                )
                SignResult(
                    sessionId = session.id,
                    displayName = session.displayName,
                    status = parsed.status,
                    httpStatus = response.code,
                    reason = parsed.reason,
                    studentId = parsed.studentId,
                    realName = parsed.realName,
                    courseName = parsed.courseName,
                    elapsedMs = elapsedMsSince(startedNs),
                    attempts = attemptNo,
                    rawSnippet = parsed.rawSnippet,
                    evidence = parsed.evidence,
                    inferred = parsed.inferred
                )
            }
        } catch (io: IOException) {
            SignResult(
                sessionId = session.id,
                displayName = session.displayName,
                status = SignStatus.NETWORK_ERROR,
                reason = "${io.javaClass.simpleName}：${io.message ?: "网络请求失败"}",
                elapsedMs = elapsedMsSince(startedNs),
                attempts = attemptNo,
                evidence = "IOException"
            )
        }
    }

    /** 只重试瞬时失败；业务性结论（未选课 / 未知错误 / 凭据失效 / 成功）重试没有意义 */
    private fun shouldRetry(result: SignResult): Boolean = isTransientFailure(result)

    private fun backoffMs(attempt: Int): Long {
        // 400ms, 800ms, … 上限 2000ms（默认最多 2 次重试，总预算仍在 15s 内）
        val exp = BACKOFF_BASE_MS shl (attempt - 1).coerceIn(0, 10)
        return exp.coerceAtMost(BACKOFF_MAX_MS)
    }

    companion object {
        /** 唯一必需的会话 Cookie 名（已实测验证） */
        const val SESSION_COOKIE_NAME = "_canvas_middle_session"

        /** 失败重试次数上限（用户已确认：最多 2 次，之后由用户手动重试） */
        const val DEFAULT_MAX_RETRIES = 2

        /** 并发上限（用户已确认：Semaphore(4)） */
        const val DEFAULT_MAX_CONCURRENCY = 4

        /** 单账号总预算 */
        const val DEFAULT_PER_ACCOUNT_TIMEOUT_MS = 15_000L

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 10_000L
        private const val WRITE_TIMEOUT_MS = 10_000L
        private const val BACKOFF_BASE_MS = 400L
        private const val BACKOFF_MAX_MS = 2_000L

        private val SESSION_COOKIE_REGEX =
            Regex("(?:^|[;\\s])${Regex.escape(SESSION_COOKIE_NAME)}=([^;\\s]+)")

        /** 从本地凭据串里定位唯一必需的会话 Cookie */
        internal fun sessionCookieRegex(): Regex = SESSION_COOKIE_REGEX

        /**
         * ~8 个真实感的 Android 手机 UA 池。
         * 每个账号按 sessionId 稳定哈希取一个 —— 固定、互不相同、绝不是同一个。
         */
        internal val UA_POOL: List<String> = listOf(
            androidUa("Android 14; SM-S9110", "124.0.0.0"),
            androidUa("Android 13; V2218A", "119.0.0.0"),
            androidUa("Android 14; PGT-AN10", "122.0.0.0"),
            androidUa("Android 12; M2102J20C", "117.0.0.0"),
            androidUa("Android 13; 2201123C", "118.0.0.0"),
            androidUa("Android 14; PLB110", "125.0.0.0"),
            androidUa("Android 12; SM-A5260", "114.0.0.0"),
            androidUa("Android 13; FNE-AN00", "120.0.0.0")
        )

        private fun androidUa(platform: String, chromeVersion: String): String =
            "Mozilla/5.0 (Linux; $platform) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$chromeVersion Mobile Safari/537.36"

        /** 按 sessionId 稳定分配 UA（同一账号每次都是同一个 UA） */
        fun userAgentFor(sessionId: String): String =
            UA_POOL[sessionId.hashCode().mod(UA_POOL.size)]

        /** 共享单个 OkHttpClient：10s 连接/读超时，且不自动跟随重定向（需要观察 302 → IAM） */
        fun defaultSignClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // 自行统计重试次数，避免 OkHttp 悄悄重发导致 attempts 失真
            .retryOnConnectionFailure(false)
            // 302 → iam 是「凭据失效」的判据，必须由我们自己看到
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/**
 * 从本地保存的凭据串里取出 `_canvas_middle_session`。
 *
 * 本地存的是 canvas.tongji.edu.cn 的整串 Cookie（形如 `a=1; b=2`），
 * 我们只把真正必需的那一个放进请求头。
 *
 * @return 可直接用作 `Cookie:` 头的字符串；null 表示凭据缺失/无效
 */
internal fun extractSessionCookie(accessToken: String?): String? {
    val raw = accessToken?.trim().orEmpty()
    if (raw.isEmpty()) return null

    val cleaned = raw
        .removePrefix("Cookie:")
        .removePrefix("cookie:")
        .trim()
    if (cleaned.isEmpty()) return null

    val matched = SignInEngine.sessionCookieRegex().find(cleaned)
    if (matched != null) {
        val value = matched.groupValues[1].trim()
        return if (value.isEmpty()) null else "${SignInEngine.SESSION_COOKIE_NAME}=$value"
    }

    // 兜底：整串就是一个裸 session 值（既没有 cookie 名也没有分号/空白分隔）
    val looksLikeBareValue = !cleaned.contains('=') &&
        cleaned.none { it == ';' || it.isWhitespace() }
    if (looksLikeBareValue) return "${SignInEngine.SESSION_COOKIE_NAME}=$cleaned"

    // 有 cookie 串但没有会话 Cookie ⇒ 这次登录其实没有成功
    return null
}

/**
 * 该结果是否属于「瞬时失败」，值得自动重试（最多 [SignInEngine.DEFAULT_MAX_RETRIES] 次）。
 *
 * 刻意不重试的情况：
 * - 未选课、未知错误（签到码无效）等业务性结论 —— 重试结果不变，只会浪费二维码窗口；
 * - 凭据失效 —— 需要人重新登录，重试没有意义；
 * - HTTP 200 的未知错误（token 伪造）同样是确定性的。
 */
internal fun isTransientFailure(result: SignResult): Boolean = when (result.status) {
    SignStatus.NETWORK_ERROR, SignStatus.TIMEOUT -> true
    SignStatus.UNKNOWN_ERROR -> result.httpStatus == null || result.httpStatus >= 500
    else -> false
}

/** 必须显式按 UTF-8 解码，不能依赖平台默认编码 */
internal fun decodeUtf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

/**
 * 把 HTML 正文压成纯文本，再交给各字段正则。
 *
 * 实测问题：服务端返回的 `签到人员：骆欣畅</div>` 里，姓名与闭合标签之间
 * **没有空格**，直接对原始 HTML 用 `\S+` 会把 `</div>` 一起吃进姓名
 * （真机卡片上出现过「骆欣畅</div>」）。统一先剥标签可让所有字段都干净。
 */
internal fun htmlToPlainText(body: String): String {
    var t = body
    // script/style 整块丢掉，避免其中的符号干扰
    t = t.replace(Regex("""(?is)<script\b.*?</script>"""), " ")
    t = t.replace(Regex("""(?is)<style\b.*?</style>"""), " ")
    // 换行类标签先变成空格，保证字段之间仍有分隔
    t = t.replace(Regex("""(?i)<\s*br\s*/?\s*>"""), " ")
    t = t.replace(Regex("""(?i)</\s*(div|p|td|tr|li|h[1-6])\s*>"""), " ")
    // 其余标签一律删掉
    t = t.replace(Regex("""<[^>]*>"""), "")
    // 常见实体（&amp; 必须最后处理，避免二次解码）
    t = t.replace("&nbsp;", " ")
        .replace("&colon;", "：")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
    return t.replace(Regex("""[ \t\u00a0]+"""), " ").trim()
}

private fun elapsedMsSince(startedNs: Long): Long =
    ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L)

/** [Call.execute] 的可取消版本：协程取消时取消底层 HTTP call */
private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
}

/** 解析后的响应结论（引擎内部使用，纯函数、可单测） */
internal data class ParsedResponse(
    val status: SignStatus,
    val reason: String? = null,
    val studentId: String? = null,
    val realName: String? = null,
    val courseName: String? = null,
    val rawSnippet: String? = null,
    val evidence: String,
    val inferred: Boolean = false
)

/**
 * 签到响应解析器：**纯文本**处理，不依赖 Android 的 org.json（在 JVM 单测里会抛 "not mocked"）。
 *
 * 判定顺序（用户/Lead 已确认）：
 * 1. 3xx → IAM  ⇒ CREDENTIAL_EXPIRED
 * 2. 正文含「签到失败：」 ⇒ FAILED(原因)
 * 3. 正文含「未知错误」或 课程=「无」 ⇒ UNKNOWN_ERROR
 * 4. 正文含「签到成功」 ⇒ SUCCESS；含「已签过」等 ⇒ ALREADY_SIGNED
 * 5. 200 + HTML 登录页 ⇒ CREDENTIAL_EXPIRED；其他 HTML ⇒ UNKNOWN_ERROR
 * 6. 其余 200 普通文本 ⇒ SUCCESS（兜底，`inferred=true`，并把原始片段留在 rawSnippet 供日后校准）
 */
internal object SignResponseParser {

    private const val FAIL_PREFIX = "签到失败："
    private const val NO_COURSE = "无"

    private val STUDENT_ID_REGEX = Regex("学号/工号：([0-9A-Za-z]+)")
    /** 姓名：不能吃掉 HTML 标签（页面里姓名后紧跟 </div>，且中间没有空格） */
    private val REAL_NAME_REGEX = Regex("""签到人员\s*[:：]\s*([^\s<>&"']+)""")
    private val COURSE_REGEX = Regex("签到课程：([^0-9]+)")
    private val FAIL_REASON_REGEX = Regex("""签到失败\s*[:：]\s*([^\s<>&"']+)""")

    /** 课程名后面的页面尾巴，取到就截断 */
    private val COURSE_TAIL_MARKERS =
        listOf(" 若显示", " 学号", " 签到", " 点击", " 课程", "\n", "\r")

    private val SUCCESS_MARKERS = listOf("签到成功")
    private val ALREADY_MARKERS = listOf("已签过", "已签到", "已经签到", "重复签到")
    private val UNKNOWN_MARKERS = listOf("未知错误")

    /** 302/3xx 指向统一认证 */
    private val IAM_MARKERS = listOf("iam.tongji.edu.cn", "oauth2/authorize", "idp/oauth2")

    /** 200 的 HTML 登录页判据 */
    private val LOGIN_PAGE_MARKERS =
        listOf("iam.tongji.edu.cn", "oauth2/authorize", "统一身份", "idp/", "loginform", "cas/login", "登录")

    fun parse(httpStatus: Int, location: String?, body: String?): ParsedResponse {
        val snippet = truncateSnippet(body)

        // 1) 重定向
        if (httpStatus in 300..399) {
            if (location.isNullOrBlank()) {
                return ParsedResponse(
                    status = SignStatus.UNKNOWN_ERROR,
                    reason = "HTTP $httpStatus 重定向但缺少 Location",
                    rawSnippet = snippet,
                    evidence = "3xx 无 Location"
                )
            }
            val lowerLocation = location.lowercase()
            return if (IAM_MARKERS.any { lowerLocation.contains(it) }) {
                ParsedResponse(
                    status = SignStatus.CREDENTIAL_EXPIRED,
                    reason = "会话已失效（被重定向到统一认证）",
                    rawSnippet = snippet,
                    evidence = "302→IAM"
                )
            } else {
                ParsedResponse(
                    status = SignStatus.UNKNOWN_ERROR,
                    reason = "未预期的重定向（HTTP $httpStatus）",
                    rawSnippet = snippet,
                    evidence = "3xx→其他"
                )
            }
        }

        // 2) 鉴权被拒
        if (httpStatus == 401 || httpStatus == 403) {
            return ParsedResponse(
                status = SignStatus.CREDENTIAL_EXPIRED,
                reason = "服务端拒绝访问（HTTP $httpStatus）",
                rawSnippet = snippet,
                evidence = "HTTP $httpStatus"
            )
        }

        // 3) 服务端错误 / 未预期状态码
        if (httpStatus >= 500) {
            return ParsedResponse(
                status = SignStatus.UNKNOWN_ERROR,
                reason = "服务端错误（HTTP $httpStatus）",
                rawSnippet = snippet,
                evidence = "HTTP $httpStatus"
            )
        }
        if (httpStatus !in 200..299) {
            return ParsedResponse(
                status = SignStatus.UNKNOWN_ERROR,
                reason = "未预期的 HTTP 状态码 $httpStatus",
                rawSnippet = snippet,
                evidence = "HTTP $httpStatus"
            )
        }

        // 4) 正文判定
        val text = htmlToPlainText(body.orEmpty())
        if (text.isBlank()) {
            return ParsedResponse(
                status = SignStatus.UNKNOWN_ERROR,
                reason = "响应正文为空",
                rawSnippet = null,
                evidence = "空正文"
            )
        }

        val studentId = STUDENT_ID_REGEX.find(text)?.groupValues?.get(1)
        val realName = REAL_NAME_REGEX.find(text)?.groupValues?.get(1)
        val courseName = cleanCourseName(COURSE_REGEX.find(text)?.groupValues?.get(1))
        val failReason = FAIL_REASON_REGEX.find(text)?.groupValues?.get(1)

        if (text.contains(FAIL_PREFIX)) {
            return ParsedResponse(
                status = SignStatus.FAILED,
                reason = failReason ?: "签到失败",
                studentId = studentId,
                realName = realName,
                courseName = courseName,
                rawSnippet = snippet,
                evidence = "匹配「签到失败：」"
            )
        }

        if (UNKNOWN_MARKERS.any { text.contains(it) }) {
            return ParsedResponse(
                status = SignStatus.UNKNOWN_ERROR,
                reason = "页面返回「未知错误」（通常是签到码无效或已被伪造）",
                studentId = studentId,
                realName = realName,
                courseName = courseName,
                rawSnippet = snippet,
                evidence = "匹配「未知错误」"
            )
        }

        if (courseName == NO_COURSE) {
            return ParsedResponse(
                status = SignStatus.UNKNOWN_ERROR,
                reason = "页面显示课程为「无」（签到码无效或已过期）",
                studentId = studentId,
                realName = realName,
                courseName = courseName,
                rawSnippet = snippet,
                evidence = "课程=无"
            )
        }

        if (SUCCESS_MARKERS.any { text.contains(it) }) {
            return ParsedResponse(
                status = SignStatus.SUCCESS,
                studentId = studentId,
                realName = realName,
                courseName = courseName,
                rawSnippet = snippet,
                evidence = "匹配「签到成功」"
            )
        }

        val alreadyMarker = ALREADY_MARKERS.firstOrNull { text.contains(it) }
        if (alreadyMarker != null) {
            return ParsedResponse(
                status = SignStatus.ALREADY_SIGNED,
                studentId = studentId,
                realName = realName,
                courseName = courseName,
                rawSnippet = snippet,
                evidence = "匹配「$alreadyMarker」"
            )
        }

        // 注意：必须用【原始正文】判断，不能用已剥离标签的 text ——
        // 否则 <html>/<body> 都被删掉了，这里永远判不出 HTML，登录页防线失效。
        if (looksLikeHtml(body.orEmpty())) {
            val lower = body.orEmpty().lowercase()
            return if (LOGIN_PAGE_MARKERS.any { lower.contains(it) }) {
                ParsedResponse(
                    status = SignStatus.CREDENTIAL_EXPIRED,
                    reason = "返回登录页，会话可能已失效",
                    rawSnippet = snippet,
                    evidence = "200+HTML 登录页"
                )
            } else {
                ParsedResponse(
                    status = SignStatus.UNKNOWN_ERROR,
                    reason = "返回 HTML 页面而非签到结果",
                    rawSnippet = snippet,
                    evidence = "200+HTML"
                )
            }
        }

        // 6) 兜底：普通文本但没有命中任何已知文案。
        //    成功文案【未获得真实样本】，因此按约定归为 SUCCESS，并把原始片段留下以便日后校准。
        return ParsedResponse(
            status = SignStatus.SUCCESS,
            studentId = studentId,
            realName = realName,
            courseName = courseName,
            rawSnippet = snippet,
            evidence = "兜底：未匹配任何已知文案",
            inferred = true
        )
    }

    private fun cleanCourseName(raw: String?): String? {
        var value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        COURSE_TAIL_MARKERS.forEach { marker -> value = value.substringBefore(marker) }
        return value.trim().ifEmpty { null }
    }

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.take(3000).lowercase()
        return head.contains("<html") ||
            head.contains("<!doctype") ||
            (head.contains("<head") && head.contains("<body"))
    }
}
