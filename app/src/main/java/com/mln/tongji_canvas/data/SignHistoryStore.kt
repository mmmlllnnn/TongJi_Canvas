package com.mln.tongji_canvas.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

data class AccountSignRecord(
    val name: String,
    val status: SignStatus,
    val message: String,
    val httpCode: Int?,
    val elapsedMs: Long
) {
    val signed: Boolean get() = status == SignStatus.SUCCESS || status == SignStatus.ALREADY_SIGNED
}

data class SignRun(
    val timeMs: Long,
    val courseCode: String,
    val courseName: String?,
    val accounts: List<AccountSignRecord>
) {
    val signedCount: Int get() = accounts.count { it.signed }
    fun summary(): String = buildString {
        append(courseName ?: "课程 ${courseCode.ifBlank { "未知" }}")
        append("：已签到 ").append(signedCount).append('/').append(accounts.size)
        accounts.forEach { append("\n").append(it.name).append("：").append(it.message) }
    }
    fun diagnostics(): String = buildString {
        append("courseCode=").append(courseCode).append(" · time=").append(timeMs)
        accounts.forEach {
            append("\n").append(it.name)
                .append(" · status=").append(it.status.name)
                .append(" · HTTP=").append(it.httpCode ?: "-")
                .append(" · elapsed=").append(it.elapsedMs).append("ms")
                .append(" · ").append(it.message)
        }
    }
}

class SignHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("sign_history", Context.MODE_PRIVATE)

    suspend fun add(url: String, accounts: List<Pair<UserSession, SignResult>>) = withContext(Dispatchers.IO) {
        val courseCode = url.toHttpUrlOrNull()?.queryParameter("courseCode").orEmpty()
        val run = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("course", courseCode)
            .put("courseName", accounts.firstNotNullOfOrNull { it.second.courseName })
        val outcomes = JSONArray()
        accounts.forEach { (session, result) ->
            outcomes.put(JSONObject()
                .put("name", session.displayName)
                .put("status", result.status.name)
                .put("message", result.message)
                .put("http", result.httpCode)
                .put("elapsed", result.elapsedMs))
        }
        run.put("accounts", outcomes)
        val old = JSONArray(prefs.getString("runs", "[]"))
        val saved = JSONArray().put(run)
        for (i in 0 until minOf(old.length(), 49)) saved.put(old.getJSONObject(i))
        prefs.edit().putString("runs", saved.toString()).apply()
    }

    suspend fun list(): List<SignRun> = withContext(Dispatchers.IO) {
        val runs = JSONArray(prefs.getString("runs", "[]"))
        (0 until runs.length()).map { i ->
            val run = runs.getJSONObject(i)
            val outcomes = run.getJSONArray("accounts")
            SignRun(
                run.getLong("time"),
                run.optString("course"),
                run.optString("courseName").takeIf { it.isNotBlank() },
                (0 until outcomes.length()).map { j ->
                    val account = outcomes.getJSONObject(j)
                    AccountSignRecord(
                        account.getString("name"),
                        SignStatus.valueOf(account.getString("status")),
                        account.getString("message"),
                        if (account.isNull("http")) null else account.getInt("http"),
                        account.optLong("elapsed")
                    )
                }
            )
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { prefs.edit().remove("runs").apply() }
}
