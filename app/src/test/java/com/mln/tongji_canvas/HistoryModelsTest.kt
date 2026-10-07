package com.mln.tongji_canvas

import com.mln.tongji_canvas.data.AccountOutcome
import com.mln.tongji_canvas.data.CredentialFingerprint
import com.mln.tongji_canvas.data.DiagEntry
import com.mln.tongji_canvas.data.DiagLevel
import com.mln.tongji_canvas.data.HistoryJson
import com.mln.tongji_canvas.data.SecretRedactor
import com.mln.tongji_canvas.data.SignHistoryEntry
import com.mln.tongji_canvas.data.SignResult
import com.mln.tongji_canvas.data.SignStatus
import com.mln.tongji_canvas.data.credentialFingerprintOf
import com.mln.tongji_canvas.data.signDiagTag
import com.mln.tongji_canvas.data.trimDiagLines
import com.mln.tongji_canvas.data.trimHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史/诊断**纯逻辑**单元测试：JSON 编解码、环形裁剪、凭据脱敏、指纹形状。
 *
 * 只覆盖不依赖 Android 的部分（[com.mln.tongji_canvas.data.HistoryStore] 的文件读写另有测试）。
 */
class HistoryModelsTest {

    // ==================== 工具 ====================

    private fun outcome(
        sessionId: String = "s1",
        displayName: String = "姚董毅(2551830)",
        status: SignStatus = SignStatus.SUCCESS,
        reason: String? = null,
        evidence: String? = "匹配「签到成功」",
        rawSnippet: String? = null,
        inferred: Boolean = false
    ) = AccountOutcome(
        sessionId = sessionId,
        displayName = displayName,
        status = status,
        realName = "姚董毅",
        studentId = "2551830",
        reason = reason,
        httpStatus = 200,
        attempts = 1,
        elapsedMs = 1234L,
        evidence = evidence,
        inferred = inferred,
        rawSnippet = rawSnippet
    )

    private fun entry(id: String = "run-1", startedAtMs: Long = 1_700_000_000_000L) = SignHistoryEntry(
        id = id,
        startedAtMs = startedAtMs,
        finishedAtMs = startedAtMs + 5_000L,
        courseName = "数据结构",
        courseCode = "117029",
        targetHost = "canvas.tongji.edu.cn",
        accounts = listOf(
            outcome(),
            outcome(sessionId = "s2", displayName = "骆欣畅(2551831)", status = SignStatus.ALREADY_SIGNED),
            outcome(
                sessionId = "s3",
                displayName = "张三",
                status = SignStatus.FAILED,
                reason = "未加入此课程",
                evidence = "匹配「签到失败：」"
            )
        )
    )

    // ==================== JSON 往返 ====================

    @Test
    fun historyJson_roundTrip_keepsEveryField() {
        val original = listOf(entry())

        val decoded = HistoryJson.decodeHistory(HistoryJson.encodeHistory(original))

        assertEquals(original, decoded)
    }

    @Test
    fun historyJson_roundTrip_survivesQuotesNewlinesAndEmoji() {
        val tricky = entry(id = "run\"2\\3").copy(
            courseName = "编译原理（含\n换行）",
            accounts = listOf(
                outcome(
                    displayName = "李四\"引号\"",
                    status = SignStatus.UNKNOWN_ERROR,
                    reason = "回归测试：\"引号\"、反斜杠\\、换行\n、制表\t、emoji 🙂",
                    rawSnippet = "签到人员：李四</div>\n签到课程：无",
                    inferred = true
                )
            )
        )

        val decoded = HistoryJson.decodeHistory(HistoryJson.encodeHistory(listOf(tricky)))

        assertEquals(listOf(tricky), decoded)
    }

    @Test
    fun historyJson_emptyAndCorruptInputDecodesToEmptyList() {
        assertEquals(emptyList<SignHistoryEntry>(), HistoryJson.decodeHistory(null))
        assertEquals(emptyList<SignHistoryEntry>(), HistoryJson.decodeHistory("   "))
        // 写入过程中被杀 → 半个数组；手改坏 → 非法字符。都不能让历史页打不开
        assertEquals(emptyList<SignHistoryEntry>(), HistoryJson.decodeHistory("""[{"id":"a","startedAtMs":1,""""))
        assertEquals(emptyList<SignHistoryEntry>(), HistoryJson.decodeHistory("这不是 JSON"))
        assertEquals(emptyList<SignHistoryEntry>(), HistoryJson.decodeHistory("{\"id\":\"a\"}"))
    }

    @Test
    fun historyJson_skipsBrokenItemsButKeepsTheRest() {
        val json = """[{"id":"ok","startedAtMs":5,"finishedAtMs":6,"accounts":[]},{"startedAtMs":7}]"""

        val decoded = HistoryJson.decodeHistory(json)

        assertEquals(1, decoded.size)
        assertEquals("ok", decoded.first().id)
    }

    @Test
    fun diagLine_roundTrip_andCorruptLineIsDropped() {
        val line = DiagEntry(
            atMs = 1_700_000_000_123L,
            level = DiagLevel.WARN,
            tag = signDiagTag("run-9"),
            message = "HTTP 302 → 凭据失效",
            detail = "evidence=302→IAM · attempts=1 · 1.2s"
        )

        assertEquals(line, HistoryJson.decodeDiagLine(HistoryJson.encodeDiagLine(line)))
        assertNull(HistoryJson.decodeDiagLine("不是 JSON"))
        assertNull(HistoryJson.decodeDiagLine(""))
        assertNull(HistoryJson.decodeDiagLine("""{"atMs":1}"""))
    }

    // ==================== 环形裁剪 ====================

    @Test
    fun trimHistory_keepsNewestAndDropsOldest() {
        val all = (1..250).map { entry(id = "run-$it", startedAtMs = it.toLong()) }

        val trimmed = trimHistory(all, 200)

        assertEquals(200, trimmed.size)
        assertEquals("run-51", trimmed.first().id)
        assertEquals("run-250", trimmed.last().id)
    }

    @Test
    fun trimHistory_degenerateLimits() {
        val all = (1..5).map { entry(id = "run-$it", startedAtMs = it.toLong()) }
        assertEquals(all, trimHistory(all, 5))
        assertEquals(emptyList<SignHistoryEntry>(), trimHistory(all, 0))
    }

    @Test
    fun trimDiagLines_keepsNewestAndDropsOldest() {
        val lines = (1..600).map { "line-$it" }

        val trimmed = trimDiagLines(lines, 500)

        assertEquals(500, trimmed.size)
        assertEquals("line-101", trimmed.first())
        assertEquals("line-600", trimmed.last())
    }

    // ==================== 脱敏（安全红线） ====================

    @Test
    fun redactor_masksCookieHeaderSessionValueAndRollCallToken() {
        val session = "9f2a1c8e5b7d4a3f0e1c2b3a4d5e6f70"
        val text = "_canvas_middle_session=$session; csrftoken=abc123\n" +
            "Cookie: _canvas_middle_session=$session\n" +
            "https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=117029&rollCallToken=Zm9vYmFyMTIzNDU2\n" +
            "Authorization: Bearer abcdef0123456789"

        val redacted = SecretRedactor.redact(text).orEmpty()

        assertFalse(redacted.contains(session))
        assertFalse(redacted.contains("rollCallToken=Zm9vYmFyMTIzNDU2"))
        assertFalse(redacted.contains("abcdef0123456789"))
        assertTrue(redacted.contains(SecretRedactor.MASK))
        // 保留键名，排障时仍能看出「这里原本有一个 Cookie」
        assertTrue(redacted.contains("rollCallToken="))
        assertTrue(redacted.contains("courseCode=117029"))
    }

    @Test
    fun redactor_isIdempotent() {
        val once = SecretRedactor.redact("rollCallToken=abcdef123456; _canvas_middle_session=zzz999").orEmpty()
        assertEquals(once, SecretRedactor.redact(once))
    }

    @Test
    fun redactor_masksLongBareTokenEvenWithoutKeyName() {
        val bare = "eyJhbGciOiJIUzI1NiJ9abcdefghijklmnop0123456789"

        val redacted = SecretRedactor.redact("正文片段：$bare 结束").orEmpty()

        assertFalse(redacted.contains(bare))
    }

    @Test
    fun redactor_leavesOrdinaryReasonTextUntouched() {
        val reason = "会话已失效（被重定向到统一认证）"
        val normal = "签到失败：未加入此课程"
        val url = "https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=117029"

        assertEquals(reason, SecretRedactor.redact(reason))
        assertEquals(normal, SecretRedactor.redact(normal))
        assertEquals(url, SecretRedactor.redact(url))
        assertFalse(SecretRedactor.containsSecretLike(reason))
    }

    // ==================== 凭据指纹 ====================

    @Test
    fun fingerprint_keepsShapeButNeverTheWholeSecret() {
        val secret = "9f2a1c8e5b7d4a3f0e1c2b3a4d5e6f70"

        val fingerprint = CredentialFingerprint.of(secret)

        assertNotNull(fingerprint)
        assertEquals(secret.length, fingerprint!!.length)
        assertEquals("9f2a1c", fingerprint.head) // 规格允许的前 6 位
        assertEquals(8, fingerprint.hash.length)
        assertTrue(fingerprint.hash.all { it in "0123456789abcdef" })
        assertFalse(fingerprint.toString().contains(secret))
    }

    @Test
    fun fingerprint_dropsHeadForShortSecrets() {
        // 短凭据（例如手输的测试串）连前 6 位都不能留：否则「前 6 位」就等于整串
        val short = "abc12345"

        val fingerprint = CredentialFingerprint.of(short)

        assertNotNull(fingerprint)
        assertEquals("", fingerprint!!.head)
        assertFalse(fingerprint.toString().contains(short))
        assertNull(CredentialFingerprint.of(null))
        assertNull(CredentialFingerprint.of("   "))
    }

    @Test
    fun fingerprint_isStableAndDistinguishesCredentials() {
        val a1 = CredentialFingerprint.of("aaaaaaaaaaaaaaaaaaaaaa")
        val a2 = CredentialFingerprint.of("aaaaaaaaaaaaaaaaaaaaaa")
        val b = CredentialFingerprint.of("bbbbbbbbbbbbbbbbbbbbbb")

        assertEquals(a1, a2)
        assertFalse(a1 == b)
    }

    @Test
    fun credentialFingerprint_prefersSessionCookieValueOverWholeHeader() {
        val session = "9f2a1c8e5b7d4a3f0e1c2b3a4d5e6f70"
        val header = "foo=1; _canvas_middle_session=$session"

        val fromHeader = credentialFingerprintOf(header)
        val fromValue = CredentialFingerprint.of(session)

        assertEquals(fromValue, fromHeader)
    }

    // ==================== 结论文案 ====================

    @Test
    fun accountOutcome_fromSignResult_mapsEveryField() {
        val result = SignResult(
            sessionId = "s1",
            displayName = "姚董毅(2551830)",
            status = SignStatus.CREDENTIAL_EXPIRED,
            httpStatus = 302,
            reason = "会话已失效（被重定向到统一认证）",
            studentId = "2551830",
            realName = "姚董毅",
            courseName = "数据结构",
            elapsedMs = 880L,
            attempts = 2,
            rawSnippet = "签到人员：姚董毅",
            evidence = "302→IAM",
            inferred = false
        )

        assertEquals(
            AccountOutcome(
                sessionId = "s1",
                displayName = "姚董毅(2551830)",
                status = SignStatus.CREDENTIAL_EXPIRED,
                realName = "姚董毅",
                studentId = "2551830",
                reason = "会话已失效（被重定向到统一认证）",
                httpStatus = 302,
                attempts = 2,
                elapsedMs = 880L,
                evidence = "302→IAM",
                inferred = false,
                rawSnippet = "签到人员：姚董毅"
            ),
            AccountOutcome.from(result)
        )
    }

    @Test
    fun statusLabel_coversTheSevenUserVisibleOutcomes() {
        assertEquals("已签到", outcome(status = SignStatus.SUCCESS).statusLabel)
        assertEquals("已签过", outcome(status = SignStatus.ALREADY_SIGNED).statusLabel)
        assertEquals(
            "未选课",
            outcome(status = SignStatus.FAILED, reason = "签到失败：未加入此课程").statusLabel
        )
        assertEquals("签到失败", outcome(status = SignStatus.FAILED, reason = "签到码已过期").statusLabel)
        assertEquals("凭据失效", outcome(status = SignStatus.CREDENTIAL_EXPIRED).statusLabel)
        assertEquals("网络失败", outcome(status = SignStatus.NETWORK_ERROR).statusLabel)
        assertEquals("未知错误", outcome(status = SignStatus.UNKNOWN_ERROR).statusLabel)
        assertEquals(
            "已取消",
            outcome(status = SignStatus.TIMEOUT, evidence = AccountOutcome.CANCELLED_EVIDENCE).statusLabel
        )
        assertEquals("超时", outcome(status = SignStatus.TIMEOUT).statusLabel)
    }

    @Test
    fun entrySummary_countsSuccessLikeAccounts() {
        val summaries = entry()

        assertEquals(3, summaries.total)
        assertEquals(2, summaries.signedCount) // 成功 + 已签过
        assertEquals(1, summaries.failedCount)
        assertEquals("成功 2 / 3", summaries.summaryText)
        assertEquals("数据结构", summaries.courseText)
        assertEquals(5_000L, summaries.durationMs)
        assertFalse(summaries.cancelled)
    }

    @Test
    fun entrySummary_marksCancelledBatch() {
        val cancelled = SignHistoryEntry(
            id = "run-x",
            startedAtMs = 1L,
            finishedAtMs = 2L,
            accounts = listOf(
                outcome(status = SignStatus.TIMEOUT, evidence = AccountOutcome.CANCELLED_EVIDENCE)
            )
        )

        assertTrue(cancelled.cancelled)
        assertEquals("未知课程", cancelled.courseText)
    }
}
