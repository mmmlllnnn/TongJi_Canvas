package com.mln.tongji_canvas.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 账号 / 课程分组的持久化仓库。
 *
 * 并发：所有「读-改-写」操作（账号与课程）都通过同一个进程级互斥锁（[Companion.storeMutex]）串行化，
 * 避免并发导入、编辑、删除时互相覆盖（丢更新）。
 */
class SessionRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("sessions", Context.MODE_PRIVATE)

    /** 在 IO 线程并持有互斥锁的情况下执行 [block]。注意：[block] 内部不得再调用本类的加锁方法。 */
    private suspend fun <T> withStoreLock(block: () -> T): T =
        storeMutex.withLock { withContext(Dispatchers.IO) { block() } }

    // ========== 账号 ==========

    /**
     * 读取全部账号。旧数据（无 [UserSession.identityKey]）会被「惰性补齐」：
     * 能从昵称里解析出学号时补上标识，解析不出来的保持 null（数据不丢、不改名、不删除）。
     */
    suspend fun getAllSessions(): List<UserSession> = withStoreLock {
        val stored = parseSessions(prefs.getString(KEY, null))
        val healed = SessionIdentityLogic.backfillIdentities(stored)
        if (healed != stored) writeSessions(healed)
        healed
    }

    /** 兼容旧调用：未经验证的保存（昵称即身份线索，会尝试从昵称解析学号）。 */
    suspend fun addOrUpdateSession(displayName: String, tokens: OAuth2Tokens): UserSession =
        upsertSession(displayName = displayName, tokens = tokens)

    /**
     * 新增或更新账号。
     *
     * @param studentId 凭据探测得到的学号/工号，是稳定身份（去重的第一优先级）。
     * @param verifiedAt 验证成功时间；null 表示本次凭据未经校验（凭据照存，只标记未验证）。
     * @param identityKey 显式身份（导入备份时使用），优先级高于 [studentId]。
     */
    suspend fun upsertSession(
        displayName: String,
        tokens: OAuth2Tokens,
        studentId: String? = null,
        verifiedAt: Long? = null,
        identityKey: String? = null
    ): UserSession = withStoreLock {
        val now = System.currentTimeMillis()
        val incoming = UserSession(
            id = UUID.randomUUID().toString(),
            displayName = displayName.trim(),
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            tokenExpiresAt = now + tokens.expiresIn * 1000L,
            lastVerifiedAt = verifiedAt,
            identityKey = identityKey?.trim()?.takeIf { it.isNotEmpty() }
                ?: SessionIdentityLogic.identityKeyFor(studentId, displayName)
        )
        val (all, saved) = SessionIdentityLogic.upsert(readSessionsLocked(), incoming)
        writeSessions(all)
        saved
    }

    suspend fun updateSession(session: UserSession) = withStoreLock {
        val all = readSessionsLocked().toMutableList()
        val existingIndex = all.indexOfFirst { it.id == session.id }
        if (existingIndex != -1) {
            val stored = all[existingIndex]
            val credentialChanged = session.accessToken != stored.accessToken
            all[existingIndex] = session.copy(
                identityKey = session.identityKey
                    ?: SessionIdentityLogic.identityKeyFor(null, session.displayName),
                // 凭据被改写过，旧的「已验证」结论不再适用于新凭据
                lastVerifiedAt = if (credentialChanged) null else session.lastVerifiedAt
            )
            writeSessions(all)
        }
    }

    suspend fun removeSession(id: String) = withStoreLock {
        writeSessions(readSessionsLocked().filterNot { it.id == id })
    }

    private fun readSessionsLocked(): List<UserSession> =
        SessionIdentityLogic.backfillIdentities(parseSessions(prefs.getString(KEY, null)))

    private fun parseSessions(json: String?): List<UserSession> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = JSONArray(json)
        val list = ArrayList<UserSession>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list += UserSession(
                id = o.getString("id"),
                displayName = o.optString("displayName", ""),
                accessToken = o.optStringOrNull("accessToken"),
                refreshToken = o.optStringOrNull("refreshToken"),
                tokenExpiresAt = o.optLongOrNull("tokenExpiresAt"),
                lastVerifiedAt = o.optLongOrNull("lastVerifiedAt"),
                identityKey = o.optStringOrNull("identityKey")
            )
        }
        return list
    }

    private fun writeSessions(list: List<UserSession>) {
        val arr = JSONArray()
        list.forEach { s ->
            val o = JSONObject()
            o.put("id", s.id)
            o.put("displayName", s.displayName)
            s.accessToken?.let { o.put("accessToken", it) }
            s.refreshToken?.let { o.put("refreshToken", it) }
            s.tokenExpiresAt?.let { o.put("tokenExpiresAt", it) }
            s.lastVerifiedAt?.let { o.put("lastVerifiedAt", it) }
            s.identityKey?.let { o.put("identityKey", it) }
            arr.put(o)
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun isTokenValid(session: UserSession): Boolean {
        return session.accessToken != null &&
               (session.tokenExpiresAt == null || session.tokenExpiresAt > System.currentTimeMillis())
    }

    // 保存选中的用户ID列表
    fun saveSelectedUserIds(userIds: Set<String>) {
        val jsonArray = JSONArray()
        userIds.forEach { jsonArray.put(it) }
        prefs.edit().putString(SELECTED_USERS_KEY, jsonArray.toString()).apply()
    }

    // 加载选中的用户ID列表
    fun getSelectedUserIds(): Set<String> = run {
        val json = prefs.getString(SELECTED_USERS_KEY, null) ?: return emptySet()
        try {
            val arr = JSONArray(json)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            set
        } catch (e: Exception) {
            emptySet()
        }
    }

    // ========== 课程/分组管理 ==========

    suspend fun getAllCourses(): List<Course> = withStoreLock { readCoursesLocked() }

    suspend fun addCourse(name: String): Course = withStoreLock {
        val all = readCoursesLocked().toMutableList()
        val course = Course(id = UUID.randomUUID().toString(), name = name)
        all.add(course)
        writeCourses(all)
        course
    }

    suspend fun updateCourse(course: Course) = withStoreLock {
        val all = readCoursesLocked().toMutableList()
        val idx = all.indexOfFirst { it.id == course.id }
        if (idx != -1) {
            all[idx] = course
            writeCourses(all)
        }
    }

    suspend fun removeCourse(id: String) = withStoreLock {
        writeCourses(readCoursesLocked().filterNot { it.id == id })
    }

    private fun readCoursesLocked(): List<Course> {
        val json = prefs.getString(COURSES_KEY, null) ?: return emptyList()
        val arr = JSONArray(json)
        val list = ArrayList<Course>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val memberIds = mutableListOf<String>()
            if (o.has("memberIds")) {
                val memberArr = o.getJSONArray("memberIds")
                for (j in 0 until memberArr.length()) {
                    memberIds.add(memberArr.getString(j))
                }
            }
            list += Course(
                id = o.getString("id"),
                name = o.getString("name"),
                memberIds = memberIds
            )
        }
        return list
    }

    private fun writeCourses(list: List<Course>) {
        val arr = JSONArray()
        list.forEach { c ->
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            val memberArr = JSONArray()
            c.memberIds.forEach { memberArr.put(it) }
            o.put("memberIds", memberArr)
            arr.put(o)
        }
        prefs.edit().putString(COURSES_KEY, arr.toString()).apply()
    }

    // 保存当前激活的课程ID（null表示"全部人员"模式）
    fun saveActiveCourseId(courseId: String?) {
        prefs.edit().putString(ACTIVE_COURSE_KEY, courseId).apply()
    }

    fun getActiveCourseId(): String? {
        return prefs.getString(ACTIVE_COURSE_KEY, null)
    }

    fun saveAllUsersExpanded(expanded: Boolean) {
        prefs.edit().putBoolean(ALL_USERS_EXPANDED_KEY, expanded).apply()
    }

    fun getAllUsersExpanded(): Boolean {
        return prefs.getBoolean(ALL_USERS_EXPANDED_KEY, true)
    }

    companion object {
        /**
         * 进程级互斥锁：Activity 重建（旋转）会产生新的 [SessionRepository] 实例，
         * 若锁挂在实例上，新的「读-改-写」就可能与旧的并发操作互相覆盖。
         */
        private val storeMutex = Mutex()

        private const val KEY = "sessions_json"
        private const val SELECTED_USERS_KEY = "selected_user_ids"
        private const val COURSES_KEY = "courses_json"
        private const val ACTIVE_COURSE_KEY = "active_course_id"
        private const val ALL_USERS_EXPANDED_KEY = "all_users_expanded"
    }
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = optString(name, "")
    return value.takeIf { it.isNotBlank() }
}

private fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val value = optLong(name, -1L)
    return value.takeIf { it > 0L }
}

/**
 * 账号身份的纯逻辑（不依赖 Android / JSON），便于单元测试。
 *
 * 匹配优先级（第一个命中即视为同一账号）：
 * 1. 相同的非空 [UserSession.identityKey]（学号，最可靠）；
 * 2. 相同的 Canvas 会话 Cookie 指纹——字面同一个凭据，必然同一账号；
 * 3. 相同的 displayName，且已有记录没有 identityKey（旧数据 / 未验证记录）——回退兼容并借此补齐学号；
 * 4. 都不匹配则新增记录（宁可多一个可删除的账号，也不覆盖别人的账号）。
 */
object SessionIdentityLogic {
    const val IDENTITY_PREFIX = "sid:"

    private val PARENTHESIZED_ID = Regex("""[（(]\s*([0-9A-Za-z]{4,20})\s*[)）]""")
    private val PURE_NUMERIC_ID = Regex("""^[0-9]{5,20}$""")
    private val SESSION_COOKIE = Regex("""(?:^|[;\s])_canvas_middle_session=([^;\s]+)""")

    // ========== 身份字段 ==========

    /** 从「姚董毅(2551830)」「2551830」这类昵称里解析学号；解析不出来返回 null。 */
    fun extractStudentId(displayName: String?): String? {
        val name = displayName?.trim().orEmpty()
        if (name.isEmpty()) return null
        PARENTHESIZED_ID.find(name)?.let { return it.groupValues[1] }
        if (PURE_NUMERIC_ID.matches(name)) return name
        return null
    }

    /** 去掉昵称里的学号后缀，用于旧数据（无标识）的宽松同名匹配：「姚董毅(2551830)」→「姚董毅」。 */
    fun baseName(displayName: String?): String =
        displayName?.trim().orEmpty().replace(PARENTHESIZED_ID, "").trim()

    /** 由学号（或昵称里的学号）生成稳定标识；没有可用信息时返回 null。 */
    fun identityKeyFor(studentId: String?, displayName: String? = null): String? {
        val sid = studentId?.trim()?.takeIf { it.isNotEmpty() } ?: extractStudentId(displayName)
        return sid?.let { IDENTITY_PREFIX + it }
    }

    /** 已验证账号的展示名：「姓名(学号)」；缺一项时用另一项，两者都缺返回空串。 */
    fun displayNameFor(realName: String?, studentId: String?): String {
        val name = realName?.trim()?.takeIf { it.isNotEmpty() }
        val sid = studentId?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            name != null && sid != null -> "$name($sid)"
            name != null -> name
            sid != null -> sid
            else -> ""
        }
    }

    /** Canvas 会话 Cookie 的值（作为「同一个凭据」的指纹）；取不到返回 null。 */
    fun canvasSessionCookieValue(cookieHeader: String?): String? {
        val raw = cookieHeader?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return SESSION_COOKIE.find(raw)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
    }

    // ========== 旧数据惰性补齐 ==========

    /**
     * 为旧记录（identityKey == null）尝试补出 identityKey。
     * 只补 identityKey，不动 id / 昵称 / 凭据 / 顺序；补不出来的保持 null。
     */
    fun backfillIdentities(sessions: List<UserSession>): List<UserSession> {
        var changed = false
        val result = ArrayList<UserSession>(sessions.size)
        sessions.forEach { s ->
            if (s.identityKey == null) {
                val derived = identityKeyFor(null, s.displayName)
                if (derived != null) {
                    changed = true
                    result += s.copy(identityKey = derived)
                    return@forEach
                }
            }
            result += s
        }
        return if (changed) result else sessions
    }

    // ========== 新增 / 更新 ==========

    /** 找出 [incoming] 应该更新哪条已有记录，找不到返回 -1。 */
    fun findMatchIndex(existing: List<UserSession>, incoming: UserSession): Int {
        incoming.identityKey?.let { key ->
            val idx = existing.indexOfFirst { it.identityKey == key }
            if (idx >= 0) return idx
        }
        canvasSessionCookieValue(incoming.accessToken)?.let { fingerprint ->
            val idx = existing.indexOfFirst { canvasSessionCookieValue(it.accessToken) == fingerprint }
            if (idx >= 0) return idx
        }
        val name = baseName(incoming.displayName)
        if (name.isNotEmpty()) {
            // 只回退匹配「旧数据 / 未验证」记录：已有稳定身份的记录绝不按同名合并（防同名顶号）
            val idx = existing.indexOfFirst {
                it.identityKey == null && baseName(it.displayName) == name
            }
            if (idx >= 0) return idx
        }
        return -1
    }

    /**
     * 合并一条新记录。
     * @return Pair(新列表, 实际保存的记录)
     */
    fun upsert(existing: List<UserSession>, incoming: UserSession): Pair<List<UserSession>, UserSession> {
        val idx = findMatchIndex(existing, incoming)
        if (idx < 0) return (existing + incoming) to incoming

        val current = existing[idx]
        val merged = current.copy(
            // 昵称以本次为准（验证成功后会变成真实姓名）；空昵称不覆盖旧昵称
            displayName = incoming.displayName.ifBlank { current.displayName },
            // 凭据为空时不擦除旧凭据
            accessToken = incoming.accessToken ?: current.accessToken,
            refreshToken = incoming.refreshToken ?: current.refreshToken,
            tokenExpiresAt = incoming.tokenExpiresAt ?: current.tokenExpiresAt,
            // null = 本次凭据未验证（宁可显示未验证，也不用旧结论误导）
            lastVerifiedAt = incoming.lastVerifiedAt,
            identityKey = incoming.identityKey ?: current.identityKey
        )
        val updated = existing.toMutableList()
        updated[idx] = merged
        return updated to merged
    }
}
