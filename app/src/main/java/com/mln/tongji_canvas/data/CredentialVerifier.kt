package com.mln.tongji_canvas.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class VerifiedAccount(val name: String, val studentId: String) {
    val displayName: String get() = "$name($studentId)"
}

object CredentialVerifier {
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val namePattern = Regex("""签到人员\s*[:：]\s*([^\s<&"'，,;；]+)""")
    private val idPattern = Regex("""学号\s*/\s*工号\s*[:：]\s*([0-9A-Za-z]{4,20})""")
    private const val PROBE_URL =
        "https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=117029&rollCallToken=0000000000"

    suspend fun verify(rawCookie: String): VerifiedAccount? = withContext(Dispatchers.IO) {
        val cookie = rawCookie.trim().removePrefix("Cookie:").trim()
        if (cookie.isEmpty()) return@withContext null
        val request = Request.Builder()
            .url(PROBE_URL)
            .header("Cookie", cookie)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 Chrome/139.0.0.0 Mobile Safari/537.36")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                val name = namePattern.find(body)?.groupValues?.get(1)
                val id = idPattern.find(body)?.groupValues?.get(1)
                if (name != null && id != null) VerifiedAccount(name, id) else null
            }
        } catch (e: IOException) {
            null
        }
    }
}
