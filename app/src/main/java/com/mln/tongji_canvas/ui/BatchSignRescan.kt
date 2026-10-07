package com.mln.tongji_canvas.ui

import com.mln.tongji_canvas.data.SignResult
import com.mln.tongji_canvas.data.SignStatus
import com.mln.tongji_canvas.data.SignTarget
import com.mln.tongji_canvas.data.SignTargetParse
import com.mln.tongji_canvas.data.UserSession

/**
 * 批量签到页「页内重新扫码」用的**纯逻辑**。
 *
 * 这些函数刻意不依赖任何 Android / Compose API，因此可以在 JVM 单元测试里直接断言 ——
 * 相机本身没法在 JVM 上测，但「扫到的是不是签到码」「该把哪些账号重发」这些**决策**
 * 必须可测（真机上排一次错要重扫无数次二维码，代价太高）。
 */

/** 扫码得到的原始文本 → 新的签到目标；失败时给出可直接展示给用户的中文原因 */
fun parseRescanTarget(rawUrl: String?): SignTargetParse = SignTarget.parse(rawUrl)

/**
 * 「全部重试」要重发的账号。
 *
 * 规则：只有**已有终态结论、且该结论允许重试**的账号才重发。
 * - 已成功 / 已签过（[SignStatus.SUCCESS]、[SignStatus.ALREADY_SIGNED]）永不重发；
 * - 还没有结论的（正在途 / 未执行）也不重发，避免同一账号出现两份互相矛盾的结论；
 * - 未选课（[SignResult.isNotEnrolled]）不重发：扫多少次都一样，只会浪费二维码窗口；
 * - 二维码过期（[SignStatus.QR_EXPIRED]）允许重发 —— 但必须配一个新的 rollCallToken（见 [needsFreshScan]）。
 *
 * @param sessions 当前批次覆盖的账号（页面上的全部账号）
 * @param results 每个账号当前的结论；缺键 = 还没结论
 */
fun retryableAccounts(
    sessions: List<UserSession>,
    results: Map<String, SignResult>
): List<UserSession> = sessions.filter { session ->
    results[session.id]?.isRetryable == true
}

/** 结果里是否有账号因「二维码已过期」失败（UI 据此提示用户必须重新扫码） */
fun hasExpiredQrFailure(results: Collection<SignResult>): Boolean =
    results.any { it.isQrExpired }

/** 因二维码过期而失败的账号名（按页面顺序去重，供提示文案点名） */
fun expiredQrAccountNames(
    sessions: List<UserSession>,
    results: Map<String, SignResult>
): List<String> = sessions
    .filter { results[it.id]?.isQrExpired == true }
    .map { it.displayName }
    .distinct()
