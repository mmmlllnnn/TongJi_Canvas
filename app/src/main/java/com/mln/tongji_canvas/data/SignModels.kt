package com.mln.tongji_canvas.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 批量签到的结构化结果模型。
 *
 * 设计要点：
 * - 只描述「一次 SDK 请求的结果」，不持有任何 Android / WebView / CookieManager 状态，
 *   因此可以在 JVM 单元测试里直接构造与断言。
 * - 所有字段都是不可变值，UI 只消费不修改。
 */
enum class SignStatus {
    /** 明确的「签到成功」 */
    SUCCESS,

    /** 本人已签到过（重复签到） */
    ALREADY_SIGNED,

    /** 服务端明确返回失败原因（如「未加入此课程」），原因见 [SignResult.reason] */
    FAILED,

    /** 会话凭据失效或缺失，需要用户重新登录 */
    CREDENTIAL_EXPIRED,

    /**
     * 签到二维码已过期 —— 服务端明确回「二维码已过期，请重新扫码」。
     *
     * 与 [UNKNOWN_ERROR] 分开的原因（用户视角完全不同）：
     * 二维码约每 3s 刷新一次，[SignTarget.rollCallToken] 每次都会变、旧 token 立刻作废。
     * 一旦请求发得太晚（网络慢，约 10s），服务端只会回这句过期文案 ——
     * 此时**用旧 token 重发多少次都一样**，唯一出路是重新扫一次码拿新 token。
     * 因此本状态被单独识别出来，UI 必须提示「重新扫码」，并让「全部重试」先扫码再重发。
     */
    QR_EXPIRED,

    /** 未预期的响应（未知错误文案、非预期状态码、无法识别的正文…） */
    UNKNOWN_ERROR,

    /** 网络层失败（IOException：DNS / 连接重置 / TLS…） */
    NETWORK_ERROR,

    /** 单账号总超时 */
    TIMEOUT;

    /** 是否属于「用户不必再操心」的结果 */
    val isSuccessLike: Boolean
        get() = this == SUCCESS || this == ALREADY_SIGNED
}

/**
 * 单个账号的签到结果。
 *
 * @param status 结论
 * @param httpStatus HTTP 状态码；未发出请求（凭据缺失）时为 null
 * @param reason 失败/异常原因（人类可读，中文）
 * @param studentId 解析到的学号/工号
 * @param realName 解析到的真实姓名
 * @param courseName 解析到的课程名
 * @param elapsedMs 该账号从发起到结束的墙钟耗时
 * @param attempts 实际发出的请求次数（含重试）
 * @param rawSnippet 原始正文片段（截断 ~200 字），用于日后校准成功文案
 * @param evidence 判定依据（命中了哪条规则），便于线上校准
 * @param inferred 结论是否来自兜底推断（未匹配任何已知文案），UI 应提示用户留意
 */
data class SignResult(
    val sessionId: String,
    val displayName: String,
    val status: SignStatus,
    val httpStatus: Int? = null,
    val reason: String? = null,
    val studentId: String? = null,
    val realName: String? = null,
    val courseName: String? = null,
    val elapsedMs: Long = 0L,
    val attempts: Int = 1,
    val rawSnippet: String? = null,
    val evidence: String? = null,
    val inferred: Boolean = false
) {
    /** 「未选课」是确定性失败，单独分区且不参与「只重试失败项」 */
    val isNotEnrolled: Boolean
        get() = status == SignStatus.FAILED && NOT_ENROLLED_MARKERS.any { marker ->
            reason?.contains(marker) == true
        }

    /** 是否值得自动重试 / 允许用户手动重试 */
    val isRetryable: Boolean
        get() = when (status) {
            SignStatus.SUCCESS, SignStatus.ALREADY_SIGNED -> false
            // 未选课重试多少次都一样，且会白白消耗二维码窗口
            SignStatus.FAILED -> !isNotEnrolled
            // 凭据失效需要重新登录，但用户可能刚登录完，允许手动重试
            // 二维码过期**可以**重试，但前提是重新扫码换一个新 token ——
            // 引擎不会自动重发（见 isTransientFailure），重发由「全部重试 → 扫码」这条路径驱动
            else -> true
        }

    /** 是否因为二维码过期而失败（UI 据此提示用户重新扫码） */
    val isQrExpired: Boolean
        get() = status == SignStatus.QR_EXPIRED

    /** UI 是否应该展示原始正文片段 */
    val showsRawSnippet: Boolean
        get() = rawSnippet != null &&
            (inferred || status == SignStatus.UNKNOWN_ERROR ||
                status == SignStatus.NETWORK_ERROR || status == SignStatus.TIMEOUT)

    companion object {
        val NOT_ENROLLED_MARKERS = listOf("未加入此课程", "未选课", "未加入课程")

        /**
         * 服务端「二维码已过期」的文案片段（判定口径见 [SignResponseParser]）。
         *
         * 三个都是「二维码 + 过期/失效」，因此用同一条紧凑正则匹配，既覆盖
         * 「二维码已过期」「二维码过期」「二维码已失效」，也不会误伤课程名里的普通「过期」。
         */
        val QR_EXPIRED_PATTERN = Regex("""二维码\s*(?:已)?\s*(?:过期|失效)""")
    }
}

/** 批量签到过程中向 UI 推送的事件 */
sealed interface SignEvent {
    /** 某个账号的请求已发起（进入并发闸门之前） */
    data class Started(val sessionId: String) : SignEvent

    /** 某个账号已有终态结果 */
    data class Finished(val result: SignResult) : SignEvent
}

/** 已验证的签到目标：重建为固定的主机 + 路径，只保留扫码得到的查询参数 */
data class SignTarget(
    /** 最终请求 URL（https://canvas.tongji.edu.cn/lms/mobile/forscan?...） */
    val url: String,
    val courseCode: String,
    val rollCallToken: String
) {
    companion object {
        /** 已验证的签到主机（不要改成 iam.tongji.edu.cn） */
        const val CANVAS_HOST = "canvas.tongji.edu.cn"
        private const val SIGN_PATH = "/lms/mobile/forscan"
        private const val COURSE_CODE_PARAM = "courseCode"
        private const val ROLL_CALL_TOKEN_PARAM = "rollCallToken"

        /**
         * 解析扫码结果。
         *
         * 只要 host/path 被强制指回已验证的签到端点，查询参数原样保留
         * （避免二维码里多带的参数被丢掉）。参数缺失时返回 [SignTargetParse.Invalid]，
         * 由 UI 直接提示，绝不向服务端发出无意义的请求。
         */
        fun parse(rawUrl: String?): SignTargetParse {
            val raw = rawUrl?.trim().orEmpty()
            if (raw.isEmpty()) return SignTargetParse.Invalid("未获取到签到链接")

            val parsed = raw.toHttpUrlOrNull()
                ?: return SignTargetParse.Invalid("签到链接不是合法的 URL")

            val courseCode = parsed.queryParameter(COURSE_CODE_PARAM)
                ?: parsed.queryParameter(COURSE_CODE_PARAM.lowercase())
            val rollCallToken = parsed.queryParameter(ROLL_CALL_TOKEN_PARAM)
                ?: parsed.queryParameter(ROLL_CALL_TOKEN_PARAM.lowercase())

            if (courseCode.isNullOrBlank()) {
                return SignTargetParse.Invalid("签到链接缺少 $COURSE_CODE_PARAM 参数")
            }
            if (rollCallToken.isNullOrBlank()) {
                return SignTargetParse.Invalid("签到链接缺少 $ROLL_CALL_TOKEN_PARAM 参数")
            }

            val rebuilt = parsed.newBuilder()
                .scheme("https")
                .host(CANVAS_HOST)
                .port(443)
                .encodedPath(SIGN_PATH)
                .fragment(null)
                .build()

            return SignTargetParse.Valid(
                SignTarget(
                    url = rebuilt.toString(),
                    courseCode = courseCode,
                    rollCallToken = rollCallToken
                )
            )
        }
    }
}

/** [SignTarget.parse] 的结果 */
sealed interface SignTargetParse {
    data class Valid(val target: SignTarget) : SignTargetParse
    data class Invalid(val reason: String) : SignTargetParse
}

/** 单条正文片段的截断长度（字数） */
internal const val SNIPPET_MAX_CHARS = 200

/**
 * 把服务端正文压成一行、并截断到 [maxChars] 个字，方便入库/展示/日后校准成功文案。
 * 返回 null 表示没有可用片段。
 */
internal fun truncateSnippet(text: String?, maxChars: Int = SNIPPET_MAX_CHARS): String? {
    val normalized = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (normalized.isEmpty()) return null
    return if (normalized.length <= maxChars) normalized else normalized.take(maxChars) + "…"
}
