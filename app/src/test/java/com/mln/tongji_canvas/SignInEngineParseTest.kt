package com.mln.tongji_canvas

import com.mln.tongji_canvas.data.SignEvent
import com.mln.tongji_canvas.data.SignInEngine
import com.mln.tongji_canvas.data.SignResponseParser
import com.mln.tongji_canvas.data.SignResult
import com.mln.tongji_canvas.data.SignStatus
import com.mln.tongji_canvas.data.SignTarget
import com.mln.tongji_canvas.data.SignTargetParse
import com.mln.tongji_canvas.data.UserSession
import com.mln.tongji_canvas.data.decodeUtf8
import com.mln.tongji_canvas.data.extractSessionCookie
import com.mln.tongji_canvas.data.htmlToPlainText
import com.mln.tongji_canvas.data.isTransientFailure
import com.mln.tongji_canvas.data.truncateSnippet
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签到响应解析器的单元测试。
 *
 * 前两个样本是**已实测的真实服务端正文**（HTTP 200，UTF-8），
 * 第三个（签到成功）**没有真实样本**，因此除了「已知文案」路径，
 * 还专门覆盖了兜底路径与「日后拿到真实文案即可校准」的字段。
 *
 * 这些测试全部在 JVM 上运行，不依赖 Android（也正因如此解析器刻意不使用 org.json，
 * 否则在单测里会抛 "not mocked"）。
 */
class SignInEngineParseTest {

    /** 真实样本 1：已登录但未选课 */
    private val notEnrolledSample =
        "点名签到 签到失败：未加入此课程 学号/工号：2551830 签到人员：姚董毅 " +
            "签到课号：120:2000287001001 签到课程：软件工程形式化方法 2000287001001 " +
            "若显示学号错误,点击这里进行重置."

    /** 真实样本 2：token 无效/伪造 */
    private val unknownErrorSample =
        "点名签到 未知错误 学号/工号：2551830 签到人员：姚董毅 签到课程：无 " +
            "若显示学号错误,点击这里进行重置."

    // ---------- 真实样本 ----------

    @Test
    fun `未选课样本被判为 FAILED 且解析出全部字段`() {
        val parsed = SignResponseParser.parse(httpStatus = 200, location = null, body = notEnrolledSample)

        assertEquals(SignStatus.FAILED, parsed.status)
        assertEquals("未加入此课程", parsed.reason)
        assertEquals("2551830", parsed.studentId)
        assertEquals("姚董毅", parsed.realName)
        assertEquals("软件工程形式化方法", parsed.courseName)
        assertEquals("匹配「签到失败：」", parsed.evidence)
        assertFalse("已知文案判定不应标记为兜底", parsed.inferred)
        assertNotNull(parsed.rawSnippet)
        assertTrue(parsed.rawSnippet!!.contains("未加入此课程"))
        assertTrue(
            "rawSnippet 应截断到 ~200 字",
            parsed.rawSnippet!!.length <= 201
        )
    }

    @Test
    fun `未知错误样本被判为 UNKNOWN_ERROR 且课程为无`() {
        val parsed = SignResponseParser.parse(httpStatus = 200, location = null, body = unknownErrorSample)

        assertEquals(SignStatus.UNKNOWN_ERROR, parsed.status)
        assertEquals("2551830", parsed.studentId)
        assertEquals("姚董毅", parsed.realName)
        assertEquals("无", parsed.courseName)
        assertTrue(parsed.reason!!.contains("未知错误"))
        assertTrue(parsed.evidence == "匹配「未知错误」" || parsed.evidence == "课程=无")
    }

    @Test
    fun `未选课结果不可重试而未知错误可重试`() {
        val notEnrolled = SignResult(
            sessionId = "s",
            displayName = "d",
            status = SignStatus.FAILED,
            httpStatus = 200,
            reason = "未加入此课程"
        )
        assertTrue(notEnrolled.isNotEnrolled)
        assertFalse("未选课重试没有意义，还会浪费二维码窗口", notEnrolled.isRetryable)

        val otherFailure = notEnrolled.copy(reason = "签到码已过期")
        assertFalse(otherFailure.isNotEnrolled)
        assertTrue(otherFailure.isRetryable)

        assertTrue(
            SignResult("s", "d", SignStatus.UNKNOWN_ERROR, httpStatus = 200).isRetryable
        )
        assertTrue(
            SignResult("s", "d", SignStatus.NETWORK_ERROR, httpStatus = null).isRetryable
        )
        assertTrue(SignResult("s", "d", SignStatus.TIMEOUT).isRetryable)
        assertFalse(SignResult("s", "d", SignStatus.SUCCESS, httpStatus = 200).isRetryable)
        assertFalse(SignResult("s", "d", SignStatus.ALREADY_SIGNED, httpStatus = 200).isRetryable)
        // 凭据失效不参与「自动」重试（isTransientFailure=false），但允许用户手动重试
        // （用户可能刚重新登录过），符合「只重试失败项」的语义。
        assertTrue(SignResult("s", "d", SignStatus.CREDENTIAL_EXPIRED, httpStatus = 302).isRetryable)
        assertFalse(
            isTransientFailure(SignResult("s", "d", SignStatus.CREDENTIAL_EXPIRED, httpStatus = 302))
        )
    }

    // ---------- 成功 / 已签过 ----------

    @Test
    fun `签到成功文案被明确识别`() {
        val body = "点名签到 签到成功 学号/工号：2551830 签到人员：姚董毅 " +
            "签到课号：120:2000287001001 签到课程：软件工程形式化方法 2000287001001 " +
            "若显示学号错误,点击这里进行重置."

        val parsed = SignResponseParser.parse(httpStatus = 200, location = null, body = body)

        assertEquals(SignStatus.SUCCESS, parsed.status)
        assertEquals("2551830", parsed.studentId)
        assertEquals("姚董毅", parsed.realName)
        assertEquals("软件工程形式化方法", parsed.courseName)
        assertFalse("命中已知文案时不应标记为兜底", parsed.inferred)
        assertEquals("匹配「签到成功」", parsed.evidence)
    }

    @Test
    fun `已签过文案被识别为 ALREADY_SIGNED`() {
        val body = "点名签到 已签过 学号/工号：2551830 签到人员：姚董毅 " +
            "签到课程：软件工程形式化方法 2000287001001"

        val parsed = SignResponseParser.parse(httpStatus = 200, location = null, body = body)

        assertEquals(SignStatus.ALREADY_SIGNED, parsed.status)
        assertEquals("姚董毅", parsed.realName)
        assertFalse(parsed.inferred)
    }

    // ---------- 成功文案未知 → 兜底 ----------

    @Test
    fun `未匹配任何已知文案的 200 正文按约定兜底为成功并保留原始片段`() {
        // 这是一个「真实成功文案尚未获得」时最可能出现的形态：
        // 普通文本、含页面标题与课程信息、但没有任何已知关键字。
        val body = "点名签到 签到已完成 学号/工号：2551830 签到人员：姚董毅 " +
            "签到课程：软件工程形式化方法 2000287001001"

        val parsed = SignResponseParser.parse(httpStatus = 200, location = null, body = body)

        assertEquals(SignStatus.SUCCESS, parsed.status)
        assertTrue("兜底结论必须被标记，UI 会提示用户留意", parsed.inferred)
        assertEquals("兜底：未匹配任何已知文案", parsed.evidence)
        assertEquals("2551830", parsed.studentId)
        assertEquals("姚董毅", parsed.realName)
        assertEquals("软件工程形式化方法", parsed.courseName)

        val result = SignResult(
            sessionId = "s",
            displayName = "d",
            status = parsed.status,
            httpStatus = 200,
            rawSnippet = parsed.rawSnippet,
            inferred = parsed.inferred
        )
        assertNotNull("校准用的原始片段必须保留", result.rawSnippet)
        assertTrue(result.showsRawSnippet)
    }

    @Test
    fun `课程名为无属于确定性错误而不是成功`() {
        // 样本 2 的「课程：无」必须优先于兜底判定
        val body = "点名签到 学号/工号：2551830 签到人员：姚董毅 签到课程：无"
        val parsed = SignResponseParser.parse(httpStatus = 200, location = null, body = body)
        assertEquals(SignStatus.UNKNOWN_ERROR, parsed.status)
        assertEquals("课程=无", parsed.evidence)
    }

    // ---------- HTTP 层 ----------

    @Test
    fun `302 指向 IAM 判为凭据失效`() {
        val parsed = SignResponseParser.parse(
            httpStatus = 302,
            location = "https://iam.tongji.edu.cn/idp/oauth2/authorize?client_id=canvas&response_type=code",
            body = ""
        )
        assertEquals(SignStatus.CREDENTIAL_EXPIRED, parsed.status)
        assertEquals("302→IAM", parsed.evidence)
    }

    @Test
    fun `302 指向其他地址不算凭据失效`() {
        val parsed = SignResponseParser.parse(
            httpStatus = 302,
            location = "https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=1",
            body = ""
        )
        assertEquals(SignStatus.UNKNOWN_ERROR, parsed.status)
        assertTrue(parsed.reason!!.contains("重定向"))
    }

    @Test
    fun `302 缺少 Location 不崩溃`() {
        val parsed = SignResponseParser.parse(httpStatus = 302, location = null, body = null)
        assertEquals(SignStatus.UNKNOWN_ERROR, parsed.status)
    }

    @Test
    fun `401 与 403 判为凭据失效`() {
        assertEquals(
            SignStatus.CREDENTIAL_EXPIRED,
            SignResponseParser.parse(401, null, "Unauthorized").status
        )
        assertEquals(
            SignStatus.CREDENTIAL_EXPIRED,
            SignResponseParser.parse(403, null, "Forbidden").status
        )
    }

    @Test
    fun `5xx 与空正文不会被误判为成功`() {
        val serverError = SignResponseParser.parse(500, null, "Internal Server Error")
        assertEquals(SignStatus.UNKNOWN_ERROR, serverError.status)
        assertEquals("HTTP 500", serverError.evidence)

        val empty = SignResponseParser.parse(200, null, "")
        assertEquals(SignStatus.UNKNOWN_ERROR, empty.status)
        assertEquals("空正文", empty.evidence)

        val blank = SignResponseParser.parse(200, null, "   \n  ")
        assertEquals(SignStatus.UNKNOWN_ERROR, blank.status)
    }

    @Test
    fun `200 返回 HTML 登录页判为凭据失效而其他 HTML 判为未知错误`() {
        val loginPage = """
            <!DOCTYPE html><html><head><title>统一身份认证</title></head>
            <body><form id="loginForm" action="https://iam.tongji.edu.cn/idp/oauth2/authorize"></form></body></html>
        """.trimIndent()
        assertEquals(
            SignStatus.CREDENTIAL_EXPIRED,
            SignResponseParser.parse(200, null, loginPage).status
        )

        val otherHtml = "<html><head><title>404</title></head><body>not found</body></html>"
        assertEquals(
            SignStatus.UNKNOWN_ERROR,
            SignResponseParser.parse(200, null, otherHtml).status
        )
    }

    @Test
    fun `重试策略只覆盖瞬时失败`() {
        assertTrue(isTransientFailure(SignResult("s", "d", SignStatus.NETWORK_ERROR)))
        assertTrue(isTransientFailure(SignResult("s", "d", SignStatus.TIMEOUT)))
        assertTrue(
            isTransientFailure(SignResult("s", "d", SignStatus.UNKNOWN_ERROR, httpStatus = 503))
        )
        assertFalse(
            "HTTP 200 的未知错误是确定性的（token 伪造），重试无意义",
            isTransientFailure(SignResult("s", "d", SignStatus.UNKNOWN_ERROR, httpStatus = 200))
        )
        assertFalse(isTransientFailure(SignResult("s", "d", SignStatus.FAILED, httpStatus = 200)))
        assertFalse(isTransientFailure(SignResult("s", "d", SignStatus.CREDENTIAL_EXPIRED, httpStatus = 302)))
        assertFalse(isTransientFailure(SignResult("s", "d", SignStatus.SUCCESS, httpStatus = 200)))
    }

    // ---------- 编码 ----------

    @Test
    fun `正文必须按 UTF-8 解码而不是平台默认编码`() {
        val bytes = notEnrolledSample.toByteArray(Charsets.UTF_8)
        val decoded = decodeUtf8(bytes)
        assertEquals(notEnrolledSample, decoded)

        // 用 UTF-8 字节走完整链路，中文字段不能变成乱码
        val parsed = SignResponseParser.parse(200, null, decodeUtf8(bytes))
        assertEquals("姚董毅", parsed.realName)
        assertEquals("软件工程形式化方法", parsed.courseName)
    }

    // ---------- Cookie 提取 ----------

    @Test
    fun `从整串 cookie 中只取会话 cookie`() {
        assertEquals(
            "_canvas_middle_session=abc123",
            extractSessionCookie("_canvas_middle_session=abc123")
        )
        assertEquals(
            "_canvas_middle_session=abc123",
            extractSessionCookie("k1=v1; _canvas_middle_session=abc123; k2=v2")
        )
        assertEquals(
            "_canvas_middle_session=abc123",
            extractSessionCookie("k1=v1;_canvas_middle_session=abc123")
        )
        assertEquals(
            "_canvas_middle_session=abc123",
            extractSessionCookie("Cookie: k1=v1; _canvas_middle_session=abc123")
        )
        assertEquals(
            "_canvas_middle_session=abc123",
            extractSessionCookie("  _canvas_middle_session=abc123  ")
        )
        // 裸 token（可能来自手工导入）
        assertEquals(
            "_canvas_middle_session=plainToken",
            extractSessionCookie("plainToken")
        )
    }

    @Test
    fun `缺少会话 cookie 的凭据视为无效`() {
        assertNull(extractSessionCookie(null))
        assertNull(extractSessionCookie(""))
        assertNull(extractSessionCookie("   "))
        assertNull(extractSessionCookie("_canvas_middle_session="))
        assertNull("只有别的 cookie 说明这次登录其实没成功", extractSessionCookie("k1=v1; k2=v2"))
    }

    // ---------- UA 策略 ----------

    @Test
    fun `UA 池至少 8 个且互不相同`() {
        val pool = SignInEngine.UA_POOL
        assertTrue("UA 池应有 ~8 个不同指纹", pool.size >= 8)
        assertEquals(pool.size, pool.toSet().size)
        assertTrue(pool.all { it.contains("Mozilla/5.0") && it.contains("Linux; Android") })
        assertFalse("不允许所有账号共用硬编码机型", pool.any { it.contains("SM-G998B") })
    }

    @Test
    fun `每个账号按 sessionId 稳定取到 UA 且全池都会被用到`() {
        val first = SignInEngine.userAgentFor("session-A")
        val second = SignInEngine.userAgentFor("session-A")
        assertEquals("同一账号每次必须取到同一个 UA", first, second)

        val used = (0 until 512)
            .map { SignInEngine.userAgentFor("session-$it") }
            .toSet()
        assertEquals("全池应被稳定散列覆盖", SignInEngine.UA_POOL.size, used.size)
        assertFalse(used.any { it.contains("SM-G998B") })
    }

    // ---------- 目标链接解析 ----------

    @Test
    fun `扫码链接被重建为已验证的端点和主机`() {
        val parsed = SignTarget.parse(
            "https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=120:2000287001001&rollCallToken=abcDEF123"
        )
        assertTrue(parsed is SignTargetParse.Valid)
        val target = (parsed as SignTargetParse.Valid).target
        assertEquals("canvas.tongji.edu.cn", SignTarget.CANVAS_HOST)
        assertTrue(target.url.startsWith("https://canvas.tongji.edu.cn/lms/mobile/forscan?"))
        assertTrue(target.url.contains("courseCode=120%3A2000287001001") || target.url.contains("courseCode=120:2000287001001"))
        assertTrue(target.url.contains("rollCallToken=abcDEF123"))
        assertEquals("120:2000287001001", target.courseCode)
        assertEquals("abcDEF123", target.rollCallToken)
    }

    @Test
    fun `额外查询参数被保留且不会指向 IAM`() {
        val parsed = SignTarget.parse(
            "http://iam.tongji.edu.cn/whatever?courseCode=2333&rollCallToken=2333&extra=1#frag"
        ) as SignTargetParse.Valid
        // 强制回到已验证主机（二维码必须指向 canvas，不能把 cookie 发到 IAM）
        assertTrue(parsed.target.url.startsWith("https://canvas.tongji.edu.cn/lms/mobile/forscan?"))
        assertTrue(parsed.target.url.contains("extra=1"))
        assertFalse(parsed.target.url.contains("frag"))
    }

    @Test
    fun `缺少参数的链接被拒绝且不会发请求`() {
        assertTrue(SignTarget.parse("") is SignTargetParse.Invalid)
        assertTrue(SignTarget.parse(null) is SignTargetParse.Invalid)
        assertTrue(SignTarget.parse("not a url") is SignTargetParse.Invalid)
        assertTrue(
            SignTarget.parse("https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=1")
                is SignTargetParse.Invalid
        )
        assertTrue(
            SignTarget.parse("https://canvas.tongji.edu.cn/lms/mobile/forscan?rollCallToken=1")
                is SignTargetParse.Invalid
        )
        val invalid = SignTarget.parse(
            "https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=1"
        ) as SignTargetParse.Invalid
        assertTrue(invalid.reason.contains("rollCallToken"))
    }

    // ---------- 片段截断 ----------

    @Test
    fun `rawSnippet 折叠空白并截断到约 200 字`() {
        val long = "点名签到\n" + "签到成功 ".repeat(60)
        val snippet = truncateSnippet(long)
        assertNotNull(snippet)
        assertTrue(snippet!!.length <= 201)
        assertFalse(snippet.contains("\n"))
        assertTrue(snippet.endsWith("…"))

        assertNull(truncateSnippet(null))
        assertNull(truncateSnippet("   \n\t "))
        assertEquals("短正文", truncateSnippet("短正文"))
    }

    // ---------- 引擎（不联网的路径） ----------

    @Test
    fun `缺少凭据的账号立刻失败且不发出任何请求`() = runBlocking {
        val engine = SignInEngine()
        val target = validTarget()

        val noCredential = engine.signOne(
            UserSession(id = "u1", displayName = "用户1", accessToken = null),
            target
        )
        assertEquals(SignStatus.CREDENTIAL_EXPIRED, noCredential.status)
        assertEquals(0, noCredential.attempts)
        assertNull("没有发出请求就不该有 HTTP 状态码", noCredential.httpStatus)

        // 有 cookie 串但没有 _canvas_middle_session ⇒ 这次登录其实没成功
        val wrongCookie = engine.signOne(
            UserSession(id = "u2", displayName = "用户2", accessToken = "k1=v1; k2=v2"),
            target
        )
        assertEquals(SignStatus.CREDENTIAL_EXPIRED, wrongCookie.status)
        assertEquals(0, wrongCookie.attempts)
    }

    @Test
    fun `signAll 为每个账号推送 Started 与属于它自己的终态`() = runBlocking {
        val engine = SignInEngine()
        val target = validTarget()
        val sessions = (1..6).map {
            UserSession(id = "u$it", displayName = "用户$it", accessToken = null)
        }

        val events = engine.signAll(sessions, target).toList()

        assertEquals(6, events.filterIsInstance<SignEvent.Started>().size)
        val finished = events.filterIsInstance<SignEvent.Finished>().map { it.result }
        assertEquals(6, finished.size)
        assertEquals(
            "结果必须一个账号一份、且归属正确",
            sessions.map { it.id }.toSet(),
            finished.map { it.sessionId }.toSet()
        )
        assertTrue(finished.all { it.status == SignStatus.CREDENTIAL_EXPIRED })
    }

    private fun validTarget(): SignTarget {
        val parsed = SignTarget.parse(
            "https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=1&rollCallToken=2"
        )
        return (parsed as SignTargetParse.Valid).target
    }

    // ---------- 真实 HTML 正文（回归测试）----------
    //
    // 之前所有样本都是「纯文本」，字段之间带空格，因此测试全绿；
    // 但服务端真实返回的是 HTML，姓名与标签之间**没有空格**：
    //   <div>签到人员：骆欣畅</div>
    // 于是在真机上姓名被解析成「骆欣畅</div>」，卡片直接把它显示了出来。
    // 这两个用例用真实 HTML 结构锁死该回归。

    private val realHtmlSample = """
        <html lang="zh-CN"><head><title>点名签到</title><meta charset=utf-8></head>
        <body><div id="app" data-v-1d9b105c="">
        <div class="title"> 签到失败：未加入此课程 </div>
        <div>学号/工号：2551830</div> <div>签到人员：姚董毅</div>
        <div>签到课号：120:2000287001001</div>
        <div>签到课程：软件工程形式化方法 2000287001001</div>
        <div> 若显示学号错误,点击这里进行重置.</div>
        </div></body></html>
    """.trimIndent()

    @Test
    fun `HTML 正文里姓名不会把闭合标签一起吃进去`() {
        val parsed = SignResponseParser.parse(httpStatus = 200, location = null, body = realHtmlSample)

        assertEquals("姚董毅", parsed.realName)
        assertEquals("2551830", parsed.studentId)
        assertEquals(SignStatus.FAILED, parsed.status)
        assertEquals("未加入此课程", parsed.reason)
        assertFalse(
            "姓名不应包含 HTML 标签残留：${parsed.realName}",
            parsed.realName!!.contains("<") || parsed.realName!!.contains(">")
        )
    }

    @Test
    fun `htmlToPlainText 会剥离标签并解码常见实体`() {
        val plain = htmlToPlainText(
            "<div>签到人员：骆欣畅</div><div>学号/工号：2551633</div><br><div>签到课程：&nbsp;软件工程</div>"
        )
        assertFalse("不应残留标签：$plain", plain.contains("<") || plain.contains(">"))
        assertTrue("应保留姓名：$plain", plain.contains("签到人员：骆欣畅"))
        assertTrue("应保留学号：$plain", plain.contains("学号/工号：2551633"))
        assertTrue("&nbsp; 应被解码为空格：$plain", plain.contains("签到课程： 软件工程"))

        // 未选课样本换成 HTML 形态后，各字段依然要解析正确
        val parsed = SignResponseParser.parse(
            httpStatus = 200,
            location = null,
            body = "<div>点名签到</div><div>未知错误</div><div>学号/工号：2551830</div>" +
                "<div>签到人员：姚董毅</div><div>签到课程：无</div>"
        )
        assertEquals("姚董毅", parsed.realName)
        assertEquals("2551830", parsed.studentId)
        assertEquals(SignStatus.UNKNOWN_ERROR, parsed.status)
    }
}
