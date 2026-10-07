package com.mln.tongji_canvas.data

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 签到历史 / 诊断日志的数据模型。
 *
 * 两个目的**刻意分开**，不要混为一谈：
 * 1. **签到历史**（[SignHistoryEntry] + [AccountOutcome]）面向用户，回答「我帮谁签过到 / 签过哪几次」，
 *    只保留人话（时间、课程、每个人的结论与原因）。
 * 2. **诊断日志**（[DiagEntry]）面向排障，记录请求 URL、HTTP 状态码、命中的判定规则（evidence）、
 *    结论是否为兜底推断（inferred）、原始正文片段与异常。默认不展示，按需展开。
 *
 * 本文件**不依赖任何 Android API**（只用 java.* ），因此可以在 JVM 单元测试里直接构造、序列化与断言。
 * JSON 读写没有引入第三方库，也没有用 `org.json`（后者在 JVM 单测里会抛 "not mocked"）。
 *
 * ## 安全红线
 * 任何字段都**不得**持有凭据本体：没有 `_canvas_middle_session` 的值、没有整串 Cookie、
 * 没有 `rollCallToken`、没有鉴权头。需要指认「是哪个凭据」时只使用 [CredentialFingerprint]
 * （长度 + 前 6 位 + 短哈希，且短于 [CredentialFingerprint.MIN_HEAD_LENGTH] 的凭据连前 6 位都不留）。
 * 所有落盘/导出文本都必须再过一遍 [SecretRedactor.redact]。
 */
data class AccountOutcome(
    val sessionId: String,
    val displayName: String,
    val status: SignStatus,
    val realName: String? = null,
    val studentId: String? = null,
    val reason: String? = null,
    val httpStatus: Int? = null,
    val attempts: Int = 0,
    val elapsedMs: Long = 0L,
    /** 判定依据（命中了哪条规则），排障用 */
    val evidence: String? = null,
    /** 结论是否来自兜底推断（未匹配任何已知文案），排障用 */
    val inferred: Boolean = false,
    /** 服务端原始正文片段（已截断、已脱敏），排障用 */
    val rawSnippet: String? = null
) {
    /** 是否「未选课」——确定性失败，和一般的签到失败分开显示 */
    val isNotEnrolled: Boolean
        get() = status == SignStatus.FAILED &&
            SignResult.NOT_ENROLLED_MARKERS.any { marker -> reason?.contains(marker) == true }

    /** 是否被用户手动取消（在途请求被中断） */
    val isCancelled: Boolean
        get() = status == SignStatus.TIMEOUT && evidence == CANCELLED_EVIDENCE

    /** 对用户而言「签上了」：签到成功或本人已签过 */
    val isSuccessLike: Boolean
        get() = status.isSuccessLike

    /** 用户可读的结论文案（历史页与导出文本共用同一口径） */
    val statusLabel: String
        get() = when {
            isCancelled -> "已取消"
            isNotEnrolled -> "未选课"
            status == SignStatus.SUCCESS -> "已签到"
            status == SignStatus.ALREADY_SIGNED -> "已签过"
            status == SignStatus.FAILED -> "签到失败"
            status == SignStatus.QR_EXPIRED -> "二维码过期"
            status == SignStatus.CREDENTIAL_EXPIRED -> "凭据失效"
            status == SignStatus.NETWORK_ERROR -> "网络失败"
            status == SignStatus.TIMEOUT -> "超时"
            else -> "未知错误"
        }

    /** 「姓名(学号)」；服务端没回姓名时退回本地昵称 */
    val identityText: String
        get() = SessionIdentityLogic.displayNameFor(realName, studentId)
            .ifBlank { displayName }

    companion object {
        /** 用户取消时写在 [evidence] 里的固定标记（[AccountOutcome.statusLabel] 依赖它） */
        const val CANCELLED_EVIDENCE = "用户取消"

        /** 由引擎结果直接构造（唯一入口，保证不漏字段） */
        fun from(result: SignResult): AccountOutcome = AccountOutcome(
            sessionId = result.sessionId,
            displayName = result.displayName,
            status = result.status,
            realName = result.realName,
            studentId = result.studentId,
            reason = result.reason,
            httpStatus = result.httpStatus,
            attempts = result.attempts,
            elapsedMs = result.elapsedMs,
            evidence = result.evidence,
            inferred = result.inferred,
            rawSnippet = result.rawSnippet
        )
    }
}

/**
 * 一次批量签到的历史记录。
 *
 * 一次「批次」= 一次 [SignInEngine.signAll] 调用（进入结果页的首次全量、或用户点「全部重试」的那一批）。
 * 重试批次只包含被重试的账号，因此 [accounts] 是该批次实际覆盖到的账号，不做跨批次合并。
 *
 * @param id 批次标识，同时作为该批诊断日志的 tag 后缀（`sign:<id>`）
 * @param startedAtMs 批次开始时间（墙钟）
 * @param finishedAtMs 批次结束时间（完成或取消）
 * @param courseName 服务端正文里解析到的课程名（可能为空：签到码无效时服务端不回课程）
 * @param courseCode 二维码里的 courseCode
 * @param targetHost 实际请求的主机（不含任何查询参数）
 */
data class SignHistoryEntry(
    val id: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val courseName: String? = null,
    val courseCode: String? = null,
    val targetHost: String = "",
    val accounts: List<AccountOutcome> = emptyList()
) {
    val total: Int get() = accounts.size

    /** 「签上了」的人数（成功 + 已签过） */
    val signedCount: Int get() = accounts.count { it.isSuccessLike }

    val failedCount: Int get() = accounts.count { !it.isSuccessLike }

    /** 是否被用户取消（批次里有账号被标记为「已取消」） */
    val cancelled: Boolean get() = accounts.any { it.isCancelled }

    /** 首屏一行就能看完的结论，例如「成功 8 / 12」 */
    val summaryText: String get() = "成功 $signedCount / $total"

    /** 课程展示名：优先课程名，其次 courseCode，都没有就说「未知课程」 */
    val courseText: String
        get() = courseName?.takeIf { it.isNotBlank() }
            ?: courseCode?.takeIf { it.isNotBlank() }?.let { "课程 $it" }
            ?: "未知课程"

    val durationMs: Long get() = (finishedAtMs - startedAtMs).coerceAtLeast(0L)
}

/** 诊断日志级别（落盘为名字符串，避免依赖 Android Log 常量） */
enum class DiagLevel { DEBUG, INFO, WARN, ERROR }

/**
 * 一条诊断日志。
 *
 * @param tag 归类标签；签到批次用 `sign:<批次 id>`，凭据导入用 `import`
 * @param message 一行摘要
 * @param detail 可选细节（HTTP 状态码 / evidence / attempts / elapsed / 异常…），**必须已脱敏**
 */
data class DiagEntry(
    val atMs: Long,
    val level: DiagLevel,
    val tag: String,
    val message: String,
    val detail: String? = null
) {
    /** 导出文本里的单行形式 */
    fun formatLine(): String = buildString {
        append(HistoryFormat.time(atMs))
        append(' ')
        append(level.name.padEnd(5))
        append(' ')
        append('[').append(tag).append(']')
        append(' ')
        append(message)
        detail?.takeIf { it.isNotBlank() }?.let { append(" ｜ ").append(it) }
    }
}

/** 诊断 tag：签到批次（[SignHistoryEntry.id] 作为后缀） */
fun signDiagTag(runId: String): String = "sign:$runId"

/** 诊断 tag：凭据导入 */
const val IMPORT_DIAG_TAG: String = "import"

// ==================== 凭据指纹（不可逆） ====================

/**
 * 凭据的**不可逆**摘要，用于在日志里指认「是哪一份凭据」。
 *
 * 只保留三个形状信息：
 * - [length] 原始长度；
 * - [hash] SHA-256 的前 8 位十六进制（源串是服务端随机会话 Cookie，熵足够高，无法反推）；
 * - [head] 前 6 个字符 —— **仅在原始长度 ≥ [MIN_HEAD_LENGTH] 时才有值**。
 *   短凭据（例如手输的短测试串）连前 6 位都不留，否则「前 6 位」就等于整串。
 *
 * [toString] 只输出这个形状，永远不含凭据本体。
 */
data class CredentialFingerprint(
    val length: Int,
    val hash: String,
    val head: String
) {
    override fun toString(): String =
        "len=$length head=${head.ifEmpty { "-" }} hash=$hash"

    companion object {
        /** 只有不短于这个长度的凭据才允许保留前 6 位 */
        const val MIN_HEAD_LENGTH = 16

        /**
         * 计算指纹；[secret] 为空/空白时返回 null（没有凭据就没什么可指认的）。
         *
         * 注意：本函数是**纯函数**，不持有、不缓存入参，调用方传入的凭据不会被保存。
         */
        fun of(secret: String?): CredentialFingerprint? {
            val value = secret?.trim().orEmpty()
            if (value.isEmpty()) return null
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val hex = buildString(32) {
                digest.take(4).forEach { byte ->
                    append(HEX[(byte.toInt() shr 4) and 0x0F])
                    append(HEX[byte.toInt() and 0x0F])
                }
            }
            return CredentialFingerprint(
                length = value.length,
                hash = hex,
                head = if (value.length >= MIN_HEAD_LENGTH) value.take(6) else ""
            )
        }

        private const val HEX = "0123456789abcdef"
    }
}

/** 便捷入口：`_canvas_middle_session=…` 的指纹；取不到会话 Cookie 时退回整串的指纹。 */
fun credentialFingerprintOf(secret: String?): CredentialFingerprint? {
    val sessionValue = SessionIdentityLogic.canvasSessionCookieValue(secret)
    return CredentialFingerprint.of(sessionValue ?: secret)
}

// ==================== 脱敏 ====================

/**
 * 唯一出口级脱敏器：**任何**要落盘、要导出、要显示的自由文本都必须先过这里。
 *
 * 覆盖：
 * 1. `key=value` / `key: value` 形式的敏感键（名字里含 token / session / cookie / auth /
 *    ticket / password / secret / credential / jsessionid 的，大小写不敏感）；
 * 2. `Cookie:` / `Set-Cookie:` 整行；
 * 3. `Bearer xxx` / `Basic xxx` 鉴权头；
 * 4. 兜底：长度 ≥ [BARE_SECRET_MIN_LENGTH] 且同时含字母与数字的裸串（像令牌的东西）。
 *
 * 第 4 条是「宁可多抹一点」的取舍：它偶尔会连带抹掉正文片段里的长 ID，
 * 但代价只是排障时少看一眼原文，而漏抹的代价是别人的会话被写进日志。
 *
 * 本函数是**幂等**的（[MASK] 里不含敏感键与 `=`，重复调用结果不变），
 * 因此可以在构造字段、写文件、导出文本时各跑一次，层层设防。
 */
object SecretRedactor {
    const val MASK = "«已隐去»"

    private const val BARE_SECRET_MIN_LENGTH = 40

    /** 名字里带这些词就按敏感处理 */
    private const val SENSITIVE_KEY =
        "[A-Za-z0-9_.\\-]*(?:token|session|cookie|auth|ticket|password|passwd|secret|credential|jsessionid)[A-Za-z0-9_.\\-]*"

    private val ASSIGNMENT = Regex(
        "($SENSITIVE_KEY)\\s*[=:：]\\s*([^\\s;&,\"'|\\]\\[}{()<>]+)",
        RegexOption.IGNORE_CASE
    )
    // 末尾要求至少一个非空白字符：`Cookie:` 后面什么都没有不算凭据，这样自检不会误报
    private val COOKIE_LINE = Regex("(?i)\\b(set-)?cookie\\b\\s*[:：]\\s*[^\\r\\n]*\\S")
    private val AUTH_SCHEME = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9\\-._~+/=]{6,}")
    private val BARE_SECRET = Regex(
        "\\b(?=[A-Za-z0-9_\\-]{" + BARE_SECRET_MIN_LENGTH + ",}\\b)" +
            "(?=[A-Za-z0-9_\\-]*[0-9])(?=[A-Za-z0-9_\\-]*[A-Za-z])[A-Za-z0-9_\\-]+\\b"
    )

    /** 单行脱敏；null / 空串原样返回（保持「没有这个字段」与「空字段」的区别） */
    fun redact(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        var out = text
        out = COOKIE_LINE.replace(out) { match ->
            // 保留 `Cookie:` 这个键名本身，方便排障时知道「这里本来有 Cookie」
            val key = match.value.substringBefore(':').substringBefore('：').trim()
            "$key: $MASK"
        }
        out = AUTH_SCHEME.replace(out) { match -> "${match.groupValues[1]} $MASK" }
        out = ASSIGNMENT.replace(out) { match -> "${match.groupValues[1]}=$MASK" }
        out = BARE_SECRET.replace(out, MASK)
        return out
    }

    /** 文本里是否还残留疑似凭据（用于自检与单元测试） */
    fun containsSecretLike(text: String?): Boolean {
        val value = text ?: return false
        if (MASK in value) {
            // 已经脱敏过的文本必然含掩码；再跑一遍替换看还有没有别的命中
            val withoutMask = value.replace(MASK, "")
            return hits(withoutMask)
        }
        return hits(value)
    }

    private fun hits(text: String): Boolean =
        COOKIE_LINE.containsMatchIn(text) ||
            AUTH_SCHEME.containsMatchIn(text) ||
            ASSIGNMENT.containsMatchIn(text) ||
            BARE_SECRET.containsMatchIn(text)
}

// ==================== 展示格式（纯函数） ====================

/** 时间/耗时格式化；UI 与导出文本共用，保证两处口径一致。 */
object HistoryFormat {
    private val FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val SHORT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun time(atMs: Long): String =
        FULL.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

    fun shortTime(atMs: Long): String =
        SHORT.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

    /** 「1.2s」/「820ms」 */
    fun duration(ms: Long): String =
        if (ms >= 1_000L) String.format(java.util.Locale.US, "%.1fs", ms / 1000.0) else "${ms}ms"
}

// ==================== JSON 编解码（纯函数，可 JVM 单测） ====================

/**
 * 历史 / 诊断日志的 JSON 编解码。
 *
 * 刻意手写而不是用 `org.json`：后者在 JVM 单元测试里是 stub，会抛 "not mocked"；
 * 也不引入第三方序列化库（本项目没有该依赖）。对象键固定、顺序固定，方便人肉查看与 diff。
 */
object HistoryJson {

    // ---------- 历史（history.json：一个 JSON 数组） ----------

    fun encodeHistory(entries: List<SignHistoryEntry>): String =
        MiniJson.encode(entries.map { entryToMap(it) })

    /** 解析失败（文件被截断/手改坏）时返回空列表，绝不抛出，避免一条坏记录让历史页永远打不开 */
    fun decodeHistory(json: String?): List<SignHistoryEntry> {
        val text = json?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val root = runCatching { MiniJson.decode(text) }.getOrNull() as? List<*> ?: return emptyList()
        return root.mapNotNull { item -> (item as? Map<*, *>)?.let { mapToEntry(it) } }
    }

    private fun entryToMap(entry: SignHistoryEntry): Map<String, Any?> = linkedMapOf(
        "id" to entry.id,
        "startedAtMs" to entry.startedAtMs,
        "finishedAtMs" to entry.finishedAtMs,
        "courseName" to entry.courseName,
        "courseCode" to entry.courseCode,
        "targetHost" to entry.targetHost,
        "accounts" to entry.accounts.map { accountToMap(it) }
    )

    private fun mapToEntry(map: Map<*, *>): SignHistoryEntry? {
        val id = map.stringOrNull("id") ?: return null
        return SignHistoryEntry(
            id = id,
            startedAtMs = map.longOrNull("startedAtMs") ?: 0L,
            finishedAtMs = map.longOrNull("finishedAtMs") ?: 0L,
            courseName = map.stringOrNull("courseName"),
            courseCode = map.stringOrNull("courseCode"),
            targetHost = map.stringOrNull("targetHost").orEmpty(),
            accounts = (map["accounts"] as? List<*>)
                .orEmpty()
                .mapNotNull { (it as? Map<*, *>)?.let { raw -> mapToAccount(raw) } }
        )
    }

    private fun accountToMap(outcome: AccountOutcome): Map<String, Any?> = linkedMapOf(
        "sessionId" to outcome.sessionId,
        "displayName" to outcome.displayName,
        "status" to outcome.status.name,
        "realName" to outcome.realName,
        "studentId" to outcome.studentId,
        "reason" to outcome.reason,
        "httpStatus" to outcome.httpStatus,
        "attempts" to outcome.attempts,
        "elapsedMs" to outcome.elapsedMs,
        "evidence" to outcome.evidence,
        "inferred" to outcome.inferred,
        "rawSnippet" to outcome.rawSnippet
    )

    private fun mapToAccount(map: Map<*, *>): AccountOutcome? {
        val sessionId = map.stringOrNull("sessionId") ?: return null
        return AccountOutcome(
            sessionId = sessionId,
            displayName = map.stringOrNull("displayName").orEmpty(),
            status = map.stringOrNull("status")?.let { name ->
                SignStatus.entries.firstOrNull { it.name == name }
            } ?: SignStatus.UNKNOWN_ERROR,
            realName = map.stringOrNull("realName"),
            studentId = map.stringOrNull("studentId"),
            reason = map.stringOrNull("reason"),
            httpStatus = map.intOrNull("httpStatus"),
            attempts = map.intOrNull("attempts") ?: 0,
            elapsedMs = map.longOrNull("elapsedMs") ?: 0L,
            evidence = map.stringOrNull("evidence"),
            inferred = map.boolOrNull("inferred") ?: false,
            rawSnippet = map.stringOrNull("rawSnippet")
        )
    }

    // ---------- 诊断（diag.log：一行一条 JSON，便于按行做环形裁剪） ----------

    fun encodeDiagLine(entry: DiagEntry): String = MiniJson.encode(
        linkedMapOf(
            "atMs" to entry.atMs,
            "level" to entry.level.name,
            "tag" to entry.tag,
            "message" to entry.message,
            "detail" to entry.detail
        )
    )

    /** 单行解析失败返回 null（该行直接丢弃，不影响其余日志） */
    fun decodeDiagLine(line: String?): DiagEntry? {
        val text = line?.trim().orEmpty()
        if (text.isEmpty()) return null
        val map = runCatching { MiniJson.decode(text) }.getOrNull() as? Map<*, *> ?: return null
        val message = map.stringOrNull("message") ?: return null
        return DiagEntry(
            atMs = map.longOrNull("atMs") ?: 0L,
            level = map.stringOrNull("level")?.let { name ->
                DiagLevel.entries.firstOrNull { it.name == name }
            } ?: DiagLevel.INFO,
            tag = map.stringOrNull("tag").orEmpty(),
            message = message,
            detail = map.stringOrNull("detail")
        )
    }
}

// ==================== 环形裁剪（纯函数） ====================

/** 只保留最新的 [max] 条签到历史（[list] 按时间正序，裁掉最旧的头部） */
fun trimHistory(list: List<SignHistoryEntry>, max: Int): List<SignHistoryEntry> =
    if (max <= 0) emptyList() else if (list.size <= max) list else list.subList(list.size - max, list.size).toList()

/** 只保留最新的 [max] 行诊断日志（裁掉最旧的头部） */
fun trimDiagLines(lines: List<String>, max: Int): List<String> =
    if (max <= 0) emptyList() else if (lines.size <= max) lines else lines.subList(lines.size - max, lines.size).toList()

// ==================== 极简 JSON（无第三方依赖，纯 Kotlin） ====================

/**
 * 极简 JSON 编解码器。
 *
 * 只需要支持本文件用到的类型（对象 / 数组 / 字符串 / 数字 / 布尔 / null），
 * 因此实现得很小；不追求通用性，追求「JVM 单测里可跑、坏数据不炸」。
 */
internal object MiniJson {

    fun encode(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> appendString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Float, is Double -> {
                val d = (value as Number).toDouble()
                if (d.isFinite()) sb.append(d.toString()) else sb.append("null")
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                value.forEach { (key, item) ->
                    if (key !is String) return@forEach
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, key)
                    sb.append(':')
                    append(sb, item)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                value.forEach { item ->
                    if (!first) sb.append(',')
                    first = false
                    append(sb, item)
                }
                sb.append(']')
            }
            else -> appendString(sb, value.toString())
        }
    }

    private fun appendString(sb: StringBuilder, value: String) {
        sb.append('"')
        value.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    /** 解析失败抛 [IllegalArgumentException]，调用方自行 runCatching */
    fun decode(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "JSON 结尾存在多余内容" }
        return value
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            require(index < text.length) { "JSON 意外结束" }
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    require(c == '-' || c.isDigit()) { "非法 JSON 字符 '$c'（位置 $index）" }
                    parseNumber()
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            index++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                require(peek() == ':') { "对象缺少 ':'" }
                index++
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return map
                    }
                    else -> throw IllegalArgumentException("对象缺少 ',' 或 '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            index++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return list
            }
            while (true) {
                list += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return list
                    }
                    else -> throw IllegalArgumentException("数组缺少 ',' 或 ']'")
                }
            }
        }

        private fun parseString(): String {
            require(peek() == '"') { "字符串缺少起始引号" }
            index++
            val sb = StringBuilder()
            while (true) {
                require(index < text.length) { "字符串未闭合" }
                when (val c = text[index]) {
                    '"' -> {
                        index++
                        return sb.toString()
                    }
                    '\\' -> {
                        index++
                        require(index < text.length) { "转义未完成" }
                        when (val esc = text[index]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(index + 4 < text.length) { "\\u 转义不完整" }
                                val hex = text.substring(index + 1, index + 5)
                                sb.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("未知转义 '\\$esc'")
                        }
                        index++
                    }
                    else -> {
                        sb.append(c)
                        index++
                    }
                }
            }
        }

        private fun parseNumber(): Any {
            val start = index
            if (peek() == '-') index++
            var isFloating = false
            while (index < text.length) {
                val c = text[index]
                if (c.isDigit()) {
                    index++
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isFloating = true
                    index++
                } else {
                    break
                }
            }
            val token = text.substring(start, index)
            require(token.isNotEmpty()) { "数字为空" }
            return if (isFloating) token.toDouble() else token.toLongOrNull() ?: token.toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, index)) { "非法字面量（位置 $index）" }
            index += word.length
            return value
        }

        private fun peek(): Char {
            require(index < text.length) { "JSON 意外结束" }
            return text[index]
        }
    }
}

// ---------- Map 取值助手（容忍缺字段与类型偏差） ----------

internal fun Map<*, *>.stringOrNull(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotEmpty() }

internal fun Map<*, *>.longOrNull(key: String): Long? = when (val value = this[key]) {
    is Long -> value
    is Int -> value.toLong()
    is Double -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

internal fun Map<*, *>.intOrNull(key: String): Int? = longOrNull(key)?.toInt()

internal fun Map<*, *>.boolOrNull(key: String): Boolean? = this[key] as? Boolean
