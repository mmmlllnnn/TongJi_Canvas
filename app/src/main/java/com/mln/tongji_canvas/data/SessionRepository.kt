package com.mln.tongji_canvas.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SessionRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("sessions", Context.MODE_PRIVATE)

    suspend fun getAllSessions(): List<UserSession> = withContext(Dispatchers.IO) {
        val json = prefs.getString(KEY, null) ?: return@withContext emptyList()
        val arr = JSONArray(json)
        val list = mutableListOf<UserSession>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list += UserSession(
                id = o.getString("id"),
                displayName = o.getString("displayName"),
                accessToken = if (o.has("accessToken")) o.getString("accessToken") else null,
                refreshToken = if (o.has("refreshToken")) o.getString("refreshToken") else null,
                tokenExpiresAt = if (o.has("tokenExpiresAt")) o.getLong("tokenExpiresAt") else null,
                lastVerifiedAt = if (o.has("lastVerifiedAt")) o.getLong("lastVerifiedAt") else null
            )
        }
        list
    }

    suspend fun addOrUpdateSession(displayName: String, tokens: OAuth2Tokens): UserSession = withContext(Dispatchers.IO) {
        val all = getAllSessions().toMutableList()
        val existing = all.firstOrNull { it.displayName == displayName }
        val session = if (existing == null) {
            UserSession(
                id = UUID.randomUUID().toString(), 
                displayName = displayName, 
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                tokenExpiresAt = System.currentTimeMillis() + (tokens.expiresIn * 1000),
                lastVerifiedAt = null
            )
        } else {
            existing.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                tokenExpiresAt = System.currentTimeMillis() + (tokens.expiresIn * 1000)
            )
        }
        if (existing == null) all.add(session) else all[all.indexOf(existing)] = session
        saveAll(all)
        session
    }

    suspend fun updateSession(session: UserSession) = withContext(Dispatchers.IO) {
        val all = getAllSessions().toMutableList()
        val existingIndex = all.indexOfFirst { it.id == session.id }
        println("更新用户: ID=${session.id}, 昵称=${session.displayName}, 找到索引=$existingIndex")
        if (existingIndex != -1) {
            all[existingIndex] = session
            saveAll(all)
            println("用户更新成功")
        } else {
            println("未找到要更新的用户")
        }
    }

    suspend fun removeSession(id: String) = withContext(Dispatchers.IO) {
        val all = getAllSessions().filterNot { it.id == id }
        saveAll(all)
    }

    private fun saveAll(list: List<UserSession>) {
        val arr = JSONArray()
        list.forEach { s ->
            val o = JSONObject()
            o.put("id", s.id)
            o.put("displayName", s.displayName)
            s.accessToken?.let { o.put("accessToken", it) }
            s.refreshToken?.let { o.put("refreshToken", it) }
            s.tokenExpiresAt?.let { o.put("tokenExpiresAt", it) }
            s.lastVerifiedAt?.let { o.put("lastVerifiedAt", it) }
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

    companion object { 
        private const val KEY = "sessions_json"
        private const val SELECTED_USERS_KEY = "selected_user_ids"
    }
}


