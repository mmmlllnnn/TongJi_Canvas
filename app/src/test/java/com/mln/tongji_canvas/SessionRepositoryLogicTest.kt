package com.mln.tongji_canvas

import com.mln.tongji_canvas.data.CredentialProbe
import com.mln.tongji_canvas.data.ProbeResult
import com.mln.tongji_canvas.data.SessionIdentityLogic
import com.mln.tongji_canvas.data.UserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯逻辑单元测试：账号稳定标识（identityKey）去重 + 旧数据兼容 + 凭据探测解析。
 *
 * 只覆盖不依赖 Android / JSON 的部分（SessionRepository 本身需要 SharedPreferences）。
 */
class SessionRepositoryLogicTest {

    // ==================== 工具 ====================

    private fun session(
        id: String,
        displayName: String,
        cookie: String? = null,
        identityKey: String? = null,
        verifiedAt: Long? = null
    ) = UserSession(
        id = id,
        displayName = displayName,
        accessToken = cookie,
        tokenExpiresAt = 1_800_000_000_000L,
        lastVerifiedAt = verifiedAt,
        identityKey = identityKey
    )

    /** 模拟「探测成功后」构造的 incoming 记录。 */
    private fun verified(
        id: String,
        realName: String,
        studentId: String?,
        cookie: String? = null,
        verifiedAt: Long? = 1_700_000_000_000L
    ) = session(
        id = id,
        displayName = SessionIdentityLogic.displayNameFor(realName, studentId),
        cookie = cookie,
        identityKey = SessionIdentityLogic.identityKeyFor(studentId, realName),
        verifiedAt = verifiedAt
    )

    private fun cookie(vararg pairs: String) = pairs.joinToString("; ")

    // ==================== 去重：同一个学号 ====================

    @Test
    fun sameStudentId_updatesInsteadOfDuplicating() {
        val stored = listOf(verified("s1", "姚董毅", "2551830", cookie("_canvas_middle_session=A")))
        val incoming = verified("s2", "姚董毅", "2551830", cookie("_canvas_middle_session=B"))

        val (all, saved) = SessionIdentityLogic.upsert(stored, incoming)

        assertEquals(1, all.size)
        // 必须复用旧记录的主键，否则课程分组里的成员关系会丢失
        assertEquals("s1", saved.id)
        assertEquals("_canvas_middle_session=B", saved.accessToken)
        assertEquals("sid:2551830", saved.identityKey)
        assertEquals(1_700_000_000_000L, saved.lastVerifiedAt)
    }

    @Test
    fun repeatedLogin_neverDuplicatesVerifiedAccount() {
        var stored = emptyList<UserSession>()
        repeat(5) { attempt ->
            val incoming = verified("u$attempt", "姚董毅", "2551830", cookie("_canvas_middle_session=S$attempt"))
            stored = SessionIdentityLogic.upsert(stored, incoming).first
        }
        assertEquals(1, stored.size)
    }

    @Test
    fun differentStudentIds_sameRealName_areNotMerged() {
        val stored = listOf(verified("s1", "张三", "1111111", cookie("_canvas_middle_session=A")))
        val incoming = verified("s2", "张三", "2222222", cookie("_canvas_middle_session=B"))

        val (all, saved) = SessionIdentityLogic.upsert(stored, incoming)

        assertEquals(2, all.size)
        assertEquals("s2", saved.id)
        assertEquals("sid:1111111", all[0].identityKey)
        assertEquals("sid:2222222", all[1].identityKey)
    }

    // ==================== 旧数据（无 identityKey）兼容 ====================

    @Test
    fun legacyRecord_withoutIdentity_isMatchedByNameAndUpgraded() {
        val stored = listOf(session("old", "姚董毅", cookie("_canvas_middle_session=OLD")))
        val incoming = verified("new", "姚董毅", "2551830", cookie("_canvas_middle_session=NEW"))

        val (all, saved) = SessionIdentityLogic.upsert(stored, incoming)

        assertEquals(1, all.size)
        assertEquals("old", saved.id)
        assertEquals("sid:2551830", saved.identityKey)
        assertEquals("姚董毅(2551830)", saved.displayName)
        assertEquals(1_700_000_000_000L, saved.lastVerifiedAt)
    }

    @Test
    fun legacyRecord_withDifferentName_isLeftUntouched() {
        val stored = listOf(session("old", "用户1737567123456", cookie("_canvas_middle_session=OLD")))
        val incoming = verified("new", "姚董毅", "2551830", cookie("_canvas_middle_session=NEW"))

        val (all, _) = SessionIdentityLogic.upsert(stored, incoming)

        assertEquals(2, all.size)
        // 旧记录原样保留（不丢数据、不改昵称、不删课程引用）
        assertEquals("old", all[0].id)
        assertEquals("用户1737567123456", all[0].displayName)
        assertEquals("_canvas_middle_session=OLD", all[0].accessToken)
        assertNull(all[0].identityKey)
    }

    @Test
    fun legacyRecord_withSameCookieFingerprint_isMatchedEvenWithDifferentName() {
        val stored = listOf(session("old", "用户1737567123456", cookie("_canvas_middle_session=SAME", "other=1")))
        val incoming = verified("new", "姚董毅", "2551830", cookie("_canvas_middle_session=SAME"))

        val (all, saved) = SessionIdentityLogic.upsert(stored, incoming)

        assertEquals(1, all.size)
        assertEquals("old", saved.id)
        assertEquals("姚董毅(2551830)", saved.displayName)
        assertEquals("sid:2551830", saved.identityKey)
    }

    @Test
    fun verifiedRecord_isNotOverwrittenByUnverifiedSameNameCredential() {
        val stored = listOf(verified("s1", "张三", "1111111", cookie("_canvas_middle_session=A")))
        // 未验证、无学号、无 Cookie 指纹（例如手动录入的另一个同名账号）
        val incoming = session("s2", "张三", cookie("_canvas_middle_session=B"))

        val (all, saved) = SessionIdentityLogic.upsert(stored, incoming)

        assertEquals(2, all.size)
        assertEquals("s1", all[0].id)
        assertEquals("_canvas_middle_session=A", all[0].accessToken)
        assertEquals("s2", saved.id)
    }

    @Test
    fun unverifiedUpsert_clearsVerifiedMarkerButKeepsCredential() {
        val stored = listOf(verified("s1", "姚董毅", "2551830", cookie("_canvas_middle_session=A")))
        val incoming = session(
            id = "s2",
            displayName = "姚董毅(2551830)",
            cookie = "_canvas_middle_session=B",
            identityKey = "sid:2551830",
            verifiedAt = null
        )

        val (all, saved) = SessionIdentityLogic.upsert(stored, incoming)

        assertEquals(1, all.size)
        assertNull(saved.lastVerifiedAt)
        assertEquals("sid:2551830", saved.identityKey)
    }

    @Test
    fun emptyIncomingCredential_doesNotEraseStoredCredential() {
        val stored = listOf(verified("s1", "姚董毅", "2551830", cookie("_canvas_middle_session=A")))
        val incoming = session(
            id = "s2",
            displayName = "姚董毅(2551830)",
            cookie = null,
            identityKey = "sid:2551830"
        )

        val (all, saved) = SessionIdentityLogic.upsert(stored, incoming)

        assertEquals(1, all.size)
        assertEquals("_canvas_middle_session=A", saved.accessToken)
    }

    // ==================== 惰性补齐 ====================

    @Test
    fun backfillIdentities_derivesStudentIdFromParenthesizedName() {
        val stored = listOf(session("a", "姚董毅(2551830)"))

        val healed = SessionIdentityLogic.backfillIdentities(stored)

        assertEquals("sid:2551830", healed[0].identityKey)
    }

    @Test
    fun backfillIdentities_keepsUnderivableRecordsAsNull() {
        val stored = listOf(
            session("a", "用户1737567123456"),
            session("b", "我的小号"),
            session("c", "")
        )

        val healed = SessionIdentityLogic.backfillIdentities(stored)

        assertEquals(3, healed.size)
        healed.forEach { assertNull(it.identityKey) }
    }

    @Test
    fun backfillIdentities_doesNotTouchExistingFieldsOrOrder() {
        val stored = listOf(
            session("a", "用户1", cookie("k=v"), identityKey = "sid:9999999", verifiedAt = 123L),
            session("b", "姚董毅(2551830)", cookie("k=v2"))
        )

        val healed = SessionIdentityLogic.backfillIdentities(stored)

        assertEquals(listOf("a", "b"), healed.map { it.id })
        assertEquals("sid:9999999", healed[0].identityKey)
        assertEquals(123L, healed[0].lastVerifiedAt)
        assertEquals("k=v", healed[0].accessToken)
        assertEquals("k=v2", healed[1].accessToken)
        assertEquals("sid:2551830", healed[1].identityKey)
    }

    @Test
    fun backfillIdentities_isIdempotent() {
        val stored = listOf(session("a", "姚董毅(2551830)"), session("b", "用户123"))
        val once = SessionIdentityLogic.backfillIdentities(stored)
        val twice = SessionIdentityLogic.backfillIdentities(once)
        assertEquals(once, twice)
    }

    // ==================== 身份字段辅助函数 ====================

    @Test
    fun identityKeyFor_prefersExplicitStudentId() {
        assertEquals("sid:2551830", SessionIdentityLogic.identityKeyFor("2551830", "随便起的名字"))
        assertEquals("sid:2551830", SessionIdentityLogic.identityKeyFor(null, "姚董毅(2551830)"))
        assertEquals("sid:2551830", SessionIdentityLogic.identityKeyFor(" 2551830 ", null))
        assertNull(SessionIdentityLogic.identityKeyFor(null, "用户1737567123456"))
        assertNull(SessionIdentityLogic.identityKeyFor("", ""))
    }

    @Test
    fun extractStudentId_handlesBothColonWidthsAndPureNumericNames() {
        assertEquals("2551830", SessionIdentityLogic.extractStudentId("姚董毅(2551830)"))
        assertEquals("A1234", SessionIdentityLogic.extractStudentId("工号（A1234）"))
        assertEquals("2551830", SessionIdentityLogic.extractStudentId("2551830"))
        assertNull(SessionIdentityLogic.extractStudentId("用户1737567123456"))
    }

    @Test
    fun baseName_stripsStudentIdSuffixForLegacyNameMatching() {
        assertEquals("姚董毅", SessionIdentityLogic.baseName("姚董毅(2551830)"))
        assertEquals("姚董毅", SessionIdentityLogic.baseName("  姚董毅  "))
        assertEquals("", SessionIdentityLogic.baseName(null))
    }

    @Test
    fun legacyPlainNameRecord_isMatchedByBaseNameWhenVerifiedNameHasStudentId() {
        val stored = listOf(session("old", "姚董毅", cookie("_canvas_middle_session=OLD")))
        val incoming = verified("new", "姚董毅", "2551830", cookie("_canvas_middle_session=NEW"))

        val (all, saved) = SessionIdentityLogic.upsert(stored, incoming)

        assertEquals(1, all.size)
        assertEquals("old", saved.id)
        assertEquals("sid:2551830", saved.identityKey)
    }

    @Test
    fun displayNameFor_buildsNameWithStudentId() {
        assertEquals("姚董毅(2551830)", SessionIdentityLogic.displayNameFor("姚董毅", "2551830"))
        assertEquals("姚董毅", SessionIdentityLogic.displayNameFor("姚董毅", null))
        assertEquals("2551830", SessionIdentityLogic.displayNameFor(" ", "2551830"))
        assertEquals("", SessionIdentityLogic.displayNameFor(null, ""))
    }

    @Test
    fun canvasSessionCookieValue_extractsTheSessionValueOnly() {
        assertEquals(
            "abc123",
            SessionIdentityLogic.canvasSessionCookieValue("foo=1; _canvas_middle_session=abc123; bar=2")
        )
        assertEquals("abc123", SessionIdentityLogic.canvasSessionCookieValue("_canvas_middle_session=abc123"))
        assertNull(SessionIdentityLogic.canvasSessionCookieValue("foo=1; bar=2"))
        assertNull(SessionIdentityLogic.canvasSessionCookieValue(null))
    }

    // ==================== 探测结果解析（离线，纯函数） ====================

    @Test
    fun parseIdentity_readsDocumentedResponseBody() {
        val body = "点名签到 未知错误 学号/工号：2551830 签到人员：姚董毅 签到课程：无 若显示学号错误,点击这里进行重置."

        val identity = CredentialProbe.parseIdentity(body)

        assertEquals("姚董毅", identity?.realName)
        assertEquals("2551830", identity?.studentId)
    }

    @Test
    fun parseIdentity_handlesHtmlEntitiesAndHalfWidthColon() {
        val body = "<div>学号: 2551830&nbsp;&nbsp;签到人员: 姚董毅</div>"

        val identity = CredentialProbe.parseIdentity(body)

        assertEquals("姚董毅", identity?.realName)
        assertEquals("2551830", identity?.studentId)
    }

    @Test
    fun parseIdentity_returnsNullForLoginPage() {
        assertNull(CredentialProbe.parseIdentity("<html><body>统一身份认证登录</body></html>"))
        assertNull(CredentialProbe.parseIdentity(""))
        assertNull(CredentialProbe.parseIdentity(null))
    }

    @Test
    fun normalizeCookieHeader_stripsPrefixAndWhitespace() {
        assertEquals("a=1; b=2", CredentialProbe.normalizeCookieHeader("Cookie: a=1; b=2"))
        assertEquals("a=1; b=2", CredentialProbe.normalizeCookieHeader("  a=1;\r\n  b=2 ; "))
        assertEquals("", CredentialProbe.normalizeCookieHeader("   "))
        assertEquals("", CredentialProbe.normalizeCookieHeader(null))
    }

    @Test
    fun hasSessionCookie_requiresCanvasSessionCookie() {
        assertTrue(CredentialProbe.hasSessionCookie("foo=1; _canvas_middle_session=abc"))
        assertTrue(!CredentialProbe.hasSessionCookie("foo=1; bar=2"))
        assertTrue(!CredentialProbe.hasSessionCookie(null))
    }

    @Test
    fun probeResultCases_areDistinguishable() {
        val valid: ProbeResult = ProbeResult.Valid("姚董毅", "2551830")
        assertTrue(valid is ProbeResult.Valid)
        assertNotEquals(ProbeResult.Expired, ProbeResult.Timeout as ProbeResult)
        assertEquals(ProbeResult.NetworkError, ProbeResult.NetworkError)
    }
}
