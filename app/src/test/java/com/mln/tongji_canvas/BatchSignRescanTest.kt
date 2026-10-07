package com.mln.tongji_canvas

import com.mln.tongji_canvas.data.SignResult
import com.mln.tongji_canvas.data.SignStatus
import com.mln.tongji_canvas.data.SignTargetParse
import com.mln.tongji_canvas.data.UserSession
import com.mln.tongji_canvas.ui.expiredQrAccountNames
import com.mln.tongji_canvas.ui.hasExpiredQrFailure
import com.mln.tongji_canvas.ui.parseRescanTarget
import com.mln.tongji_canvas.ui.retryableAccounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「页内重新扫码 + 只重发失败账号」的纯逻辑测试。
 *
 * 相机本身没法在 JVM 上跑，所以真正的决策（扫到的链接能不能用、哪些账号该重发）
 * 全部抽成了纯函数放在 [com.mln.tongji_canvas.ui.BatchSignRescan]：
 * 这些用例锁死「已签上的账号既不会被重发、也不会丢结果」这条产品红线。
 */
class BatchSignRescanTest {

    private fun session(id: String, name: String) =
        UserSession(id = id, displayName = name, accessToken = "_canvas_middle_session=t$id")

    private fun result(
        session: UserSession,
        status: SignStatus,
        reason: String? = null
    ) = SignResult(
        sessionId = session.id,
        displayName = session.displayName,
        status = status,
        httpStatus = 200,
        reason = reason
    )

    // ---------- 扫码链接解析 ----------

    @Test
    fun `扫到的新码被解析成新的 rollCallToken`() {
        val parsed = parseRescanTarget(
            "https://canvas.tongji.edu.cn/lms/mobile/forscan" +
                "?courseCode=120:2000287001001&rollCallToken=NEW-TOKEN-2"
        )
        assertTrue(parsed is SignTargetParse.Valid)
        val target = (parsed as SignTargetParse.Valid).target
        assertEquals("NEW-TOKEN-2", target.rollCallToken)
        assertEquals("120:2000287001001", target.courseCode)
        assertTrue(target.url.contains("rollCallToken=NEW-TOKEN-2"))
    }

    @Test
    fun `不是签到码的二维码被拒绝且给出可展示的原因`() {
        listOf(
            // 空扫码结果 / 随便一个网址（例如扫到了别处的二维码）
            "" to "未获取到签到链接",
            "https://www.bilibili.com/" to "签到链接缺少 courseCode 参数",
            // 是签到端点但缺 token：二维码被截断或不是签到码
            "https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=1" to
                "签到链接缺少 rollCallToken 参数",
            "not a url" to "签到链接不是合法的 URL"
        ).forEach { (raw, expectedReason) ->
            val parsed = parseRescanTarget(raw)
            assertTrue("「$raw」应被拒绝", parsed is SignTargetParse.Invalid)
            assertEquals(expectedReason, (parsed as SignTargetParse.Invalid).reason)
        }
    }

    // ---------- 只重发失败账号 ----------

    @Test
    fun `重新扫码后只重发失败账号而已签上的账号原样保留`() {
        val a = session("a", "甲")
        val b = session("b", "乙")
        val c = session("c", "丙")
        val d = session("d", "丁")
        val sessions = listOf(a, b, c, d)

        // 甲已签到、乙已签过：这两人**不能**被重发；丙超时、丁二维码过期：需要重发
        val before = mapOf(
            a.id to result(a, SignStatus.SUCCESS, reason = null),
            b.id to result(b, SignStatus.ALREADY_SIGNED),
            c.id to result(c, SignStatus.TIMEOUT, "单账号超时"),
            d.id to result(d, SignStatus.QR_EXPIRED, "二维码已过期，需要重新扫码后重试")
        )

        val retry = retryableAccounts(sessions, before)

        assertEquals(
            "只应重发失败项，且顺序与页面一致",
            listOf(c.id, d.id),
            retry.map { it.id }
        )
        assertFalse("已签到的人绝不能被重发", retry.any { it.id == a.id || it.id == b.id })

        // 重发批次不触碰成功者的结果：它们的条目仍在 map 里，UI 的行照旧渲染
        assertEquals(SignStatus.SUCCESS, before[a.id]!!.status)
        assertEquals(SignStatus.ALREADY_SIGNED, before[b.id]!!.status)
        assertEquals(4, before.size)
    }

    @Test
    fun `未选课不重发而没有结论的账号也不重发`() {
        val notEnrolled = session("n", "未选课的同学")
        val inFlight = session("f", "请求中的同学")
        val failed = session("x", "普通失败的同学")
        val sessions = listOf(notEnrolled, inFlight, failed)

        val results = mapOf(
            notEnrolled.id to result(
                notEnrolled,
                SignStatus.FAILED,
                reason = "未加入此课程"
            ),
            failed.id to result(failed, SignStatus.NETWORK_ERROR, "SocketTimeoutException")
            // inFlight 没有条目 = 还没有终态结论
        )

        val retry = retryableAccounts(sessions, results)

        assertEquals(
            "未选课重发没有意义；在途账号也不能被重复发（否则会出现互相矛盾的两份结论）",
            listOf(failed.id),
            retry.map { it.id }
        )
    }

    @Test
    fun `全部签上时没有可重试的账号`() {
        val a = session("a", "甲")
        val b = session("b", "乙")
        val results = mapOf(
            a.id to result(a, SignStatus.SUCCESS),
            b.id to result(b, SignStatus.ALREADY_SIGNED)
        )
        assertTrue(retryableAccounts(listOf(a, b), results).isEmpty())
    }

    // ---------- 过期提示 ----------

    @Test
    fun `结果里有过期项时提示重新扫码并点名到人`() {
        val a = session("a", "甲")
        val b = session("b", "乙")
        val c = session("c", "丙")
        val results = mapOf(
            a.id to result(a, SignStatus.SUCCESS),
            b.id to result(b, SignStatus.QR_EXPIRED, "二维码已过期，需要重新扫码后重试"),
            c.id to result(c, SignStatus.QR_EXPIRED, "二维码已过期，需要重新扫码后重试")
        )

        assertTrue(hasExpiredQrFailure(results.values))
        assertEquals(listOf("乙", "丙"), expiredQrAccountNames(listOf(a, b, c), results))
    }

    @Test
    fun `没有过期项时不提示重新扫码`() {
        val a = session("a", "甲")
        val results = mapOf(
            a.id to result(a, SignStatus.TIMEOUT, "单账号超时"),
            "ghost" to result(session("ghost", "幽灵"), SignStatus.UNKNOWN_ERROR, "未知错误")
        )
        assertFalse(hasExpiredQrFailure(results.values))
        assertTrue(
            "只统计页面上的账号，点名列表不应出现被筛掉的账号",
            expiredQrAccountNames(listOf(a), results).isEmpty()
        )
    }
}
