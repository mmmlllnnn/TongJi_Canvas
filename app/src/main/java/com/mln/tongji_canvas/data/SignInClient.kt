package com.mln.tongji_canvas.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

enum class SignStatus { SUCCESS, ALREADY_SIGNED, NOT_ENROLLED, QR_EXPIRED, CREDENTIAL_EXPIRED, NETWORK_ERROR, FAILED }

data class SignResult(
    val status: SignStatus,
    val message: String,
    val httpCode: Int? = null,
    val elapsedMs: Long = 0,
    val courseName: String? = null
) {
    val signed: Boolean get() = status == SignStatus.SUCCESS || status == SignStatus.ALREADY_SIGNED
    val retryable: Boolean get() = status == SignStatus.FAILED || status == SignStatus.QR_EXPIRED || status == SignStatus.NETWORK_ERROR
}

object SignInClient {
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val sessionCookie = Regex("(?:^|;)\\s*_canvas_middle_session=([^;\\s]+)")
    private val failure = Regex("签到失败\\s*[:：]\\s*([^\\s<]+)")

    fun validTarget(url: String): Boolean {
        val target = url.toHttpUrlOrNull() ?: return false
        return target.scheme == "https" && target.host == "canvas.tongji.edu.cn" &&
            target.encodedPath == "/lms/mobile/forscan" &&
            !target.queryParameter("courseCode").isNullOrBlank() &&
            !target.queryParameter("rollCallToken").isNullOrBlank()
    }

    suspend fun sign(session: UserSession, url: String): SignResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        fun result(status: SignStatus, message: String, code: Int? = null, course: String? = null) =
            SignResult(status, message, code, (System.nanoTime() - started) / 1_000_000, course)
        if (!validTarget(url)) return@withContext result(SignStatus.FAILED, "签到二维码无效")
        val raw = session.accessToken?.removePrefix("Cookie:")?.trim().orEmpty()
        val cookie = sessionCookie.find(raw)?.groupValues?.get(1)
            ?: raw.takeIf { it.isNotBlank() && '=' !in it && ';' !in it }
            ?: return@withContext result(SignStatus.CREDENTIAL_EXPIRED, "账号缺少 Canvas 登录凭据")
        val request = Request.Builder()
            .url(url)
            .header("Cookie", "_canvas_middle_session=$cookie")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399 || response.code == 401 || response.code == 403) {
                    return@withContext result(SignStatus.CREDENTIAL_EXPIRED, "登录已失效，请重新导入账号", response.code)
                }
                if (!response.isSuccessful) {
                    return@withContext result(SignStatus.FAILED, "HTTP ${response.code}", response.code)
                }
                val text = response.body?.string().orEmpty()
                    .replace(Regex("<[^>]+>"), " ")
                    .replace("&nbsp;", " ")
                val course = Regex("签到课程\\s*[:：]\\s*([^\\s]+)")
                    .find(text)?.groupValues?.get(1)?.takeIf { it != "无" }
                when {
                    listOf("已签过", "已签到过", "已经签到", "重复签到").any(text::contains) ->
                        result(SignStatus.ALREADY_SIGNED, "已签到过", response.code, course)
                    text.contains("二维码已过期") || text.contains("二维码已失效") ->
                        result(SignStatus.QR_EXPIRED, "二维码已过期，请重新扫码", response.code, course)
                    text.contains("签到失败") && text.contains("未加入此课程") ->
                        result(SignStatus.NOT_ENROLLED, "未加入此课程", response.code, course)
                    text.contains("签到失败") ->
                        result(SignStatus.FAILED, failure.find(text)?.groupValues?.get(1) ?: "签到失败", response.code, course)
                    text.contains("签到成功") -> result(SignStatus.SUCCESS, "签到成功", response.code, course)
                    else -> result(SignStatus.FAILED, "未识别的签到结果", response.code, course)
                }
            }
        } catch (e: IOException) {
            result(SignStatus.NETWORK_ERROR, "网络请求失败，请重新扫码重试")
        }
    }
}
