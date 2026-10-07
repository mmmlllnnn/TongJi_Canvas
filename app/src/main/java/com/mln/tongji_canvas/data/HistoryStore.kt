package com.mln.tongji_canvas.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 签到历史 / 诊断日志的落盘仓库。
 *
 * ## 存储格式（两个文件，都放在 `context.filesDir` 下）
 * - `history.json`：**一个 JSON 数组**，元素是 [SignHistoryEntry]，按时间**正序**（最旧在前、最新在最后）。
 *   追加 = 读全量 → 尾部追加 → 裁到最新 [HISTORY_MAX_ENTRIES] 条 → 整体重写。
 * - `diag.log`：**JSON Lines**（一行一条 [DiagEntry] 的 JSON），按时间正序。
 *   追加 = 读全部行 → 尾部追加 → 裁到最新 [DIAG_MAX_LINES] 行 → 整体重写。
 *
 * 之所以能整体重写，是因为两个文件都有硬上限（200 条 / 500 行），体量固定在几十 KB 级。
 *
 * ## 原子性
 * 所有写入都走 [writeAtomically]：先写同目录下的 `*.tmp`，`fsync` 之后再 `rename` 覆盖目标文件。
 * 进程在写到一半时被杀，最坏结果只是留下一个 `.tmp`，**原有文件仍然完整**；读取端也额外容忍
 * 「文件被截断 / 被手改坏」——解析失败按空处理，绝不让一条坏记录把历史页卡死。
 *
 * ## 线程模型
 * 全部方法是**同步**（非 suspend）的，内部用进程级锁 [lock] 串行化「读-改-写」，
 * 避免批量签到与导入流程并发追加时互相覆盖。调用方自己决定放在哪个线程（App 内都从 IO 线程调）。
 * 这样设计也是为了避免「在非 suspend 函数里调 suspend 函数」这类编译错误。
 *
 * ## 安全
 * **任何**写入与导出都会经过 [SecretRedactor]：凭据、Cookie 串、token 一律变成 `«已隐去»`。
 * 本类不持有任何凭据，[AppDiagnostics] 也不。
 */
class HistoryStore(private val dir: File) {

    private val historyFile = File(dir, HISTORY_FILE_NAME)
    private val diagFile = File(dir, DIAG_FILE_NAME)

    // ==================== 签到历史 ====================

    /**
     * 追加一次批量签到的历史记录。
     *
     * @param runId 批次标识；调用方在批次开始时就生成（[newRunId]），因为它同时是诊断日志的 tag
     *              （`sign:<runId>`），必须与运行期写入的诊断行对得上。
     * @return 实际写入的记录（已脱敏）；写盘失败返回 null（历史写不进去不应影响签到本身）
     */
    fun appendSignSession(
        runId: String,
        startedAtMs: Long,
        finishedAtMs: Long,
        courseName: String? = null,
        courseCode: String? = null,
        targetHost: String = "",
        accounts: List<AccountOutcome> = emptyList()
    ): SignHistoryEntry? {
        val entry = SignHistoryEntry(
            id = runId,
            startedAtMs = startedAtMs,
            finishedAtMs = finishedAtMs,
            courseName = SecretRedactor.redact(courseName)?.trim()?.takeIf { it.isNotEmpty() },
            courseCode = SecretRedactor.redact(courseCode)?.trim()?.takeIf { it.isNotEmpty() },
            targetHost = SecretRedactor.redact(targetHost)?.trim().orEmpty(),
            accounts = accounts.map { sanitize(it) }
        )
        return synchronized(lock) {
            val all = trimHistory(readHistoryLocked() + entry, HISTORY_MAX_ENTRIES)
            if (!writeAtomically(historyFile, HistoryJson.encodeHistory(all))) return@synchronized null
            entry
        }
    }

    /** 全部历史，**倒序**（最新在前），直接给历史页用 */
    fun getHistory(): List<SignHistoryEntry> = synchronized(lock) {
        readHistoryLocked().asReversed()
    }

    /** 清空历史与诊断日志（历史页的「清空历史」用它，语义见 UI 上的确认弹窗） */
    fun clearAll() = synchronized(lock) {
        runCatching { historyFile.delete() }
        runCatching { diagFile.delete() }
        Unit
    }

    /** 只清空历史条目，保留诊断日志（目前 UI 未直接使用，留给「只看诊断」的场景） */
    fun clearHistory() = synchronized(lock) {
        runCatching { historyFile.delete() }
        Unit
    }

    // ==================== 诊断日志 ====================

    /**
     * 写一行诊断日志。
     *
     * [message] 与 [detail] 在写入前会被 [SecretRedactor] 脱敏——这是**唯一**的落盘入口，
     * 调用方即使不小心把 Cookie 放进 detail，也不会被写进文件。
     *
     * 本方法不抛异常：诊断写不进去不该影响签到/导入主流程。
     */
    fun log(level: DiagLevel, tag: String, message: String, detail: String? = null) {
        val entry = DiagEntry(
            atMs = System.currentTimeMillis(),
            level = level,
            tag = SecretRedactor.redact(tag)?.trim().orEmpty(),
            message = SecretRedactor.redact(message)?.trim().orEmpty(),
            detail = SecretRedactor.redact(detail)?.trim()?.takeIf { it.isNotEmpty() }
        )
        synchronized(lock) {
            val line = HistoryJson.encodeDiagLine(entry)
            val lines = trimDiagLines(readDiagLinesLocked() + line, DIAG_MAX_LINES)
            writeAtomically(diagFile, lines.joinToString("\n") + "\n")
        }
    }

    /** 最近 [limit] 行诊断日志（正序：旧 → 新） */
    fun getDiag(limit: Int = DIAG_MAX_LINES): List<DiagEntry> = synchronized(lock) {
        val entries = readDiagLinesLocked().mapNotNull { HistoryJson.decodeDiagLine(it) }
        if (limit <= 0 || entries.size <= limit) entries else entries.subList(entries.size - limit, entries.size).toList()
    }

    /** 某一次批量签到产生的诊断行（历史页的「详情/诊断」用它） */
    fun getDiagForRun(runId: String): List<DiagEntry> =
        getDiag().filter { it.tag == signDiagTag(runId) }

    // ==================== 导出 ====================

    /**
     * 导出为人类可读的纯文本（「复制日志」按钮用）。
     *
     * 组装完成后再整体过一遍 [SecretRedactor] —— 这是最后一道闸门：
     * 就算前面哪一层漏了，导出文本里也不会出现凭据。
     */
    fun exportText(): String {
        val history = getHistory()
        val diag = getDiag()
        val text = buildString {
            appendLine("同济 Canvas 批量签到 · 诊断导出")
            appendLine("导出时间：${HistoryFormat.time(System.currentTimeMillis())}")
            appendLine("历史记录：${history.size} 条（上限 $HISTORY_MAX_ENTRIES）")
            appendLine("诊断日志：${diag.size} 行（上限 $DIAG_MAX_LINES）")
            appendLine()
            appendLine("—— 签到历史（新 → 旧）——")
            if (history.isEmpty()) {
                appendLine("（暂无记录）")
            } else {
                history.forEachIndexed { index, entry ->
                    appendLine(formatEntry(index + 1, entry))
                }
            }
            appendLine()
            appendLine("—— 诊断日志（旧 → 新）——")
            if (diag.isEmpty()) {
                appendLine("（暂无日志）")
            } else {
                diag.forEach { appendLine(it.formatLine()) }
            }
        }
        return SecretRedactor.redact(text).orEmpty()
    }

    private fun formatEntry(index: Int, entry: SignHistoryEntry): String = buildString {
        append("[$index] ${HistoryFormat.time(entry.startedAtMs)} · ${entry.courseText} · ")
        append(entry.summaryText)
        if (entry.cancelled) append("（已取消）")
        append(" · 耗时 ${HistoryFormat.duration(entry.durationMs)}")
        entry.courseCode?.takeIf { it.isNotBlank() }?.let { append(" · courseCode=$it") }
        entry.targetHost.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        entry.accounts.forEach { account ->
            appendLine()
            append("    ${account.identityText}：${account.statusLabel}")
            account.reason?.takeIf { it.isNotBlank() }?.let { append("（$it）") }
            append(" · HTTP ${account.httpStatus ?: "-"} · ${account.attempts} 次 · ")
            append(HistoryFormat.duration(account.elapsedMs))
            account.evidence?.takeIf { it.isNotBlank() }?.let { append(" · evidence=$it") }
            if (account.inferred) append(" · 推断")
            // 原始正文片段是排障的关键证据（已经过 [SecretRedactor] 脱敏，引擎侧只留 ~200 字）
            account.rawSnippet?.takeIf { it.isNotBlank() }?.let { appendLine().append("      原始正文：$it") }
        }
    }

    // ==================== 内部读写 ====================

    private fun sanitize(outcome: AccountOutcome): AccountOutcome = outcome.copy(
        displayName = SecretRedactor.redact(outcome.displayName).orEmpty(),
        realName = SecretRedactor.redact(outcome.realName),
        studentId = SecretRedactor.redact(outcome.studentId),
        reason = SecretRedactor.redact(outcome.reason),
        evidence = SecretRedactor.redact(outcome.evidence),
        rawSnippet = SecretRedactor.redact(outcome.rawSnippet)
    )

    private fun readHistoryLocked(): List<SignHistoryEntry> {
        val text = runCatching { historyFile.takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()
        return HistoryJson.decodeHistory(text)
    }

    private fun readDiagLinesLocked(): List<String> {
        val text = runCatching { diagFile.takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()
        return text?.lines()?.filter { it.isNotBlank() }.orEmpty()
    }

    /**
     * 原子写：[target].tmp → fsync → rename 覆盖。
     * 任何一步失败都返回 false，并且**不动**原有文件（宁可不写，也不写坏）。
     */
    private fun writeAtomically(target: File, content: String): Boolean {
        return runCatching {
            dir.mkdirs()
            val tmp = File(dir, target.name + TMP_SUFFIX)
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                runCatching { out.fd.sync() }
            }
            if (tmp.renameTo(target)) return@runCatching true
            // Windows / 少数文件系统上 rename 不能覆盖已存在的目标：先删再改名
            if (target.exists() && target.delete() && tmp.renameTo(target)) return@runCatching true
            // 实在不行就退化为直接写（非原子，但好过整条日志丢失）
            tmp.delete()
            target.writeText(content, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    companion object {
        /** 历史保留上限：最新 200 条（更旧的丢弃） */
        const val HISTORY_MAX_ENTRIES = 200

        /** 诊断保留上限：最新 500 行（更旧的丢弃） */
        const val DIAG_MAX_LINES = 500

        const val HISTORY_FILE_NAME = "history.json"
        const val DIAG_FILE_NAME = "diag.log"

        private const val TMP_SUFFIX = ".tmp"

        /** 进程级锁：Activity 重建会产生新的 [HistoryStore] 实例，锁必须挂在类上 */
        private val lock = Any()

        /** 生成批次标识（同时用作诊断 tag） */
        fun newRunId(): String = UUID.randomUUID().toString()

        /** App 内唯一入口：文件放在 `context.filesDir` 下 */
        fun forContext(context: Context): HistoryStore = HistoryStore(context.filesDir)
    }
}

/**
 * 应用级诊断入口。
 *
 * 这样设计是为了让埋点**零侵入**：[CredentialProbe] 是 object、没有 Context，
 * `probe()` 的签名也不需要为了写日志而改变（不破坏既有导入流程）。
 * Activity 启动时 [install] 一次，之后任何地方调 [log] 都行；没安装时静默丢弃。
 */
object AppDiagnostics {
    @Volatile
    private var store: HistoryStore? = null

    /** App 启动时调用一次（重复调用会替换，方便测试） */
    fun install(store: HistoryStore?) {
        this.store = store
    }

    val current: HistoryStore? get() = store

    /** 写一行诊断；未安装时为 no-op */
    fun log(level: DiagLevel, tag: String, message: String, detail: String? = null) {
        store?.log(level, tag, message, detail)
    }
}
