package com.mln.tongji_canvas

import com.mln.tongji_canvas.data.AccountOutcome
import com.mln.tongji_canvas.data.CredentialFingerprint
import com.mln.tongji_canvas.data.DiagLevel
import com.mln.tongji_canvas.data.HistoryStore
import com.mln.tongji_canvas.data.IMPORT_DIAG_TAG
import com.mln.tongji_canvas.data.SecretRedactor
import com.mln.tongji_canvas.data.SignStatus
import com.mln.tongji_canvas.data.signDiagTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [HistoryStore] 的文件层测试：原子写、保留上限（环形裁剪）、损坏文件容错，
 * 以及**最要紧的一条**——凭据绝不出现在落盘文件与导出文本里。
 */
class HistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 真实世界里的会话 Cookie 值（Replit 风格的定长十六进制），用来验证「抹得掉」 */
    private val sessionValue = "9f2a1c8e5b7d4a3f0e1c2b3a4d5e6f70"
    private val cookieHeader = "_canvas_middle_session=$sessionValue; csrftoken=abc123def456"
    private val rollCallToken = "Zm9vYmFyMTIzNDU2Nzg5MA"

    private fun store() = HistoryStore(tmp.root)

    private fun outcome(
        sessionId: String = "s1",
        status: SignStatus = SignStatus.SUCCESS,
        reason: String? = null,
        evidence: String? = null,
        rawSnippet: String? = null
    ) = AccountOutcome(
        sessionId = sessionId,
        displayName = "姚董毅(2551830)",
        status = status,
        realName = "姚董毅",
        studentId = "2551830",
        reason = reason,
        httpStatus = 200,
        attempts = 1,
        elapsedMs = 900L,
        evidence = evidence,
        rawSnippet = rawSnippet
    )

    private fun append(store: HistoryStore, runId: String, atMs: Long, accounts: List<AccountOutcome> = listOf(outcome())) {
        store.appendSignSession(
            runId = runId,
            startedAtMs = atMs,
            finishedAtMs = atMs + 1_000L,
            courseName = "数据结构",
            courseCode = "117029",
            targetHost = "canvas.tongji.edu.cn",
            accounts = accounts
        )
    }

    private fun readRaw(name: String): String {
        val file = File(tmp.root, name)
        assertTrue("$name 应该已生成", file.isFile)
        return file.readText(Charsets.UTF_8)
    }

    // ==================== 基本读写 ====================

    @Test
    fun appendThenGet_returnsNewestFirst() {
        val store = store()
        append(store, "run-1", 1_000L)
        append(store, "run-2", 2_000L)
        append(store, "run-3", 3_000L)

        val history = store.getHistory()

        assertEquals(listOf("run-3", "run-2", "run-1"), history.map { it.id })
        assertEquals("数据结构", history.first().courseName)
        assertEquals("成功 1 / 1", history.first().summaryText)
    }

    @Test
    fun appendSignSession_returnsSanitizedEntryThatMatchesWhatWasPersisted() {
        val store = store()

        val saved = store.appendSignSession(
            runId = "run-1",
            startedAtMs = 1_000L,
            finishedAtMs = 2_000L,
            courseName = "数据结构",
            courseCode = "117029",
            targetHost = "canvas.tongji.edu.cn",
            accounts = listOf(outcome())
        )

        assertNotNull(saved)
        assertEquals(store.getHistory().first(), saved)
    }

    @Test
    fun storeOnEmptyDirectory_readsAsEmpty() {
        assertTrue(store().getHistory().isEmpty())
        assertTrue(store().getDiag().isEmpty())
    }

    @Test
    fun clearAll_removesBothFiles() {
        val store = store()
        append(store, "run-1", 1_000L)
        store.log(DiagLevel.INFO, IMPORT_DIAG_TAG, "导入成功")

        store.clearAll()

        assertTrue(store.getHistory().isEmpty())
        assertTrue(store.getDiag().isEmpty())
        assertFalse(File(tmp.root, HistoryStore.HISTORY_FILE_NAME).exists())
        assertFalse(File(tmp.root, HistoryStore.DIAG_FILE_NAME).exists())
    }

    @Test
    fun clearHistory_keepsDiagnostics() {
        val store = store()
        append(store, "run-1", 1_000L)
        store.log(DiagLevel.INFO, IMPORT_DIAG_TAG, "导入成功")

        store.clearHistory()

        assertTrue(store.getHistory().isEmpty())
        assertEquals(1, store.getDiag().size)
    }

    // ==================== 保留上限（环形裁剪） ====================

    @Test
    fun historyFile_keepsOnlyNewestTwoHundredEntries() {
        val store = store()
        val total = HistoryStore.HISTORY_MAX_ENTRIES + 15
        repeat(total) { index -> append(store, "run-$index", 1_000L + index) }

        val history = store.getHistory()

        assertEquals(HistoryStore.HISTORY_MAX_ENTRIES, history.size)
        assertEquals("run-${total - 1}", history.first().id)          // 最新
        assertEquals("run-15", history.last().id)                     // 最旧的 15 条被丢弃
        // 文件里真的只剩下 200 条，而不是内存里裁了、盘上还在涨
        assertEquals(200, store.getHistory().size)
    }

    @Test
    fun diagFile_keepsOnlyNewestFiveHundredLines() {
        val store = store()
        val total = HistoryStore.DIAG_MAX_LINES + 30
        repeat(total) { index -> store.log(DiagLevel.INFO, IMPORT_DIAG_TAG, "第 $index 行") }

        val diag = store.getDiag()

        assertEquals(HistoryStore.DIAG_MAX_LINES, diag.size)
        assertEquals("第 30 行", diag.first().message)
        assertEquals("第 ${total - 1} 行", diag.last().message)
        assertEquals(HistoryStore.DIAG_MAX_LINES, readRaw(HistoryStore.DIAG_FILE_NAME).lines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun getDiagForRun_returnsOnlyThatBatch() {
        val store = store()
        append(store, "run-a", 1_000L)
        append(store, "run-b", 2_000L)
        store.log(DiagLevel.INFO, signDiagTag("run-a"), "A 的请求")
        store.log(DiagLevel.INFO, signDiagTag("run-b"), "B 的请求")
        store.log(DiagLevel.INFO, IMPORT_DIAG_TAG, "导入")

        val runA = store.getDiagForRun("run-a")

        assertEquals(1, runA.size)
        assertEquals("A 的请求", runA.first().message)
    }

    // ==================== 损坏文件容错 / 原子性 ====================

    @Test
    fun truncatedHistoryFile_isTreatedAsEmptyAndCanBeAppendedAgain() {
        val store = store()
        append(store, "run-1", 1_000L)
        // 模拟「写到一半被杀」：文件只剩半截合法 JSON
        File(tmp.root, HistoryStore.HISTORY_FILE_NAME).writeText("""[{"id":"run-1","started""", Charsets.UTF_8)

        assertTrue(store.getHistory().isEmpty())

        append(store, "run-2", 2_000L)
        assertEquals(listOf("run-2"), store.getHistory().map { it.id })
    }

    @Test
    fun noTemporaryFileIsLeftBehind() {
        val store = store()
        append(store, "run-1", 1_000L)
        store.log(DiagLevel.INFO, IMPORT_DIAG_TAG, "导入成功")

        val leftovers = tmp.root.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }

        assertTrue("原子写不应留下临时文件：${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    // ==================== 安全：凭据绝不落盘 / 绝不导出 ====================

    @Test
    fun credentialsNeverReachPersistedFilesOrExportedText() {
        val store = store()
        // 最坏情况：调用方把整串 Cookie、rollCallToken 都塞进了自由文本字段
        val leaky = outcome(
            status = SignStatus.UNKNOWN_ERROR,
            reason = "请求失败：$cookieHeader",
            evidence = "Cookie: $cookieHeader",
            rawSnippet = "https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=117029&rollCallToken=$rollCallToken"
        )
        store.appendSignSession(
            runId = "run-leak",
            startedAtMs = 1_000L,
            finishedAtMs = 2_000L,
            courseName = "数据结构",
            courseCode = "117029",
            targetHost = "canvas.tongji.edu.cn",
            accounts = listOf(leaky)
        )
        store.log(
            level = DiagLevel.ERROR,
            tag = signDiagTag("run-leak"),
            message = "带着 _canvas_middle_session=$sessionValue 的请求失败",
            detail = "Cookie: $cookieHeader · rollCallToken=$rollCallToken"
        )
        store.log(DiagLevel.INFO, IMPORT_DIAG_TAG, "导入凭据 ${CredentialFingerprint.of(sessionValue)}")

        val historyJson = readRaw(HistoryStore.HISTORY_FILE_NAME)
        val diagLog = readRaw(HistoryStore.DIAG_FILE_NAME)
        val exported = store.exportText()

        // 1) 凭据本体（会话值）在哪儿都不许出现
        listOf(historyJson, diagLog, exported).forEach { text ->
            assertFalse("持久化/导出文本里出现了会话值", text.contains(sessionValue))
            assertFalse("持久化/导出文本里出现了 Cookie 值", text.contains("_canvas_middle_session=$sessionValue"))
            assertFalse("持久化/导出文本里出现了 rollCallToken 值", text.contains(rollCallToken))
            assertFalse("持久化/导出文本里出现了 csrf token", text.contains("abc123def456"))
        }

        // 2) 确认脱敏确实发生了（不是碰巧没写进去）
        assertTrue(historyJson.contains(SecretRedactor.MASK))
        assertTrue(diagLog.contains(SecretRedactor.MASK))
        assertTrue(exported.contains(SecretRedactor.MASK))
        assertTrue(exported.contains("rollCallToken=")) // 键名保留，方便排障

        // 3) 正向对照：脱敏器确实能识别出这段文本携带凭据，脱敏后就不再像凭据
        assertTrue(SecretRedactor.containsSecretLike(cookieHeader))
        assertFalse(SecretRedactor.containsSecretLike(SecretRedactor.redact(cookieHeader)))

        // 3b) 内存里的记录（含 data class 的 toString）同样不许出现凭据
        assertEquals(1, store.getHistory().size)
        assertFalse(store.getHistory().toString().contains(sessionValue))
        assertFalse(store.getDiag().toString().contains(sessionValue))

        // 4) 需要指认凭据时只留不可逆指纹（长度 + 前 6 位 + 短哈希）
        assertTrue(exported.contains("len=${sessionValue.length}"))
        assertTrue(exported.contains("head=${sessionValue.take(6)}"))
        assertFalse(exported.contains(sessionValue))
    }

    @Test
    fun exportText_isReadableAndCoversEveryAccount() {
        val store = store()
        append(
            store,
            "run-1",
            1_700_000_000_000L,
            accounts = listOf(
                outcome(sessionId = "s1", status = SignStatus.SUCCESS, evidence = "匹配「签到成功」"),
                outcome(
                    sessionId = "s2",
                    status = SignStatus.FAILED,
                    reason = "未加入此课程",
                    evidence = "匹配「签到失败：」"
                ),
                outcome(sessionId = "s3", status = SignStatus.NETWORK_ERROR, reason = "SocketTimeoutException")
            )
        )
        store.log(DiagLevel.INFO, signDiagTag("run-1"), "批次结束", "3 人 · 成功 1")

        val exported = store.exportText()

        assertTrue(exported.contains("同济 Canvas 批量签到 · 诊断导出"))
        assertTrue(exported.contains("成功 1 / 3"))
        assertTrue(exported.contains("数据结构"))
        assertTrue(exported.contains("已签到"))
        assertTrue(exported.contains("未选课"))
        assertTrue(exported.contains("网络失败"))
        assertTrue(exported.contains("evidence=匹配「签到成功」"))
        assertTrue(exported.contains("批次结束"))
    }

    @Test
    fun exportText_onEmptyStoreDoesNotThrow() {
        val exported = store().exportText()

        assertTrue(exported.contains("（暂无记录）"))
        assertTrue(exported.contains("（暂无日志）"))
    }
}
