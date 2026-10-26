package com.mln.tongji_canvas.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

enum class SignStatus { SUCCESS, ALREADY_SIGNED, NOT_ENROLLED, FAILED }

data class SignResult(val status: SignStatus, val message: String) {
    val signed: Boolean get() = status == SignStatus.SUCCESS || status == SignStatus.ALREADY_SIGNED
    val retryable: Boolean get() = status == SignStatus.FAILED
}

object SignInClient {
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val sessionCookie = Regex("(?:^|;)\\s*_canvas_middle_session=([^;\\s]+)")
    private val failure = Regex("签到失败\\s*[:：]\\s*([^\\s<]+)")

    fun validTarget(url: String): Boolean {
        val target = url.toHttpUrlOrNull() ?: return false
        return target.scheme == "https" && target.host == "canvas.tongji.edu.cn" &&
            target.encodedPath == "/lms/mobile/forscan" &&
            !target.queryParameter("rollCallToken").isNullOrBlank()
    }

    suspend fun sign(session: UserSession, url: String): SignResult = withContext(Dispatchers.IO) {
        if (!validTarget(url)) return@withContext SignResult(SignStatus.FAILED, "签到二维码无效")
        val raw = session.accessToken?.removePrefix("Cookie:")?.trim().orEmpty()
        val cookie = sessionCookie.find(raw)?.groupValues?.get(1)
            ?: raw.takeIf { it.isNotBlank() && '=' !in it && ';' !in it }
            ?: return@withContext SignResult(SignStatus.FAILED, "账号缺少 Canvas 登录凭据")
        val request = Request.Builder()
            .url(url)
            .header("Cookie", "_canvas_middle_session=$cookie")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399 || response.code == 401 || response.code == 403) {
                    return@withContext SignResult(SignStatus.FAILED, "登录已失效，请重新导入账号")
                }
                if (!response.isSuccessful) {
                    return@withContext SignResult(SignStatus.FAILED, "HTTP ${response.code}")
                }
                val text = response.body?.string().orEmpty()
                    .replace(Regex("<[^>]+>"), " ")
                    .replace("&nbsp;", " ")
                when {
                    listOf("已签过", "已签到过", "已经签到", "重复签到").any(text::contains) ->
                        SignResult(SignStatus.ALREADY_SIGNED, "已签到过")
                    text.contains("签到失败") && text.contains("未加入此课程") ->
                        SignResult(SignStatus.NOT_ENROLLED, "未加入此课程")
                    text.contains("签到失败") ->
                        SignResult(SignStatus.FAILED, failure.find(text)?.groupValues?.get(1) ?: "签到失败")
                    text.contains("签到成功") -> SignResult(SignStatus.SUCCESS, "签到成功")
                    else -> SignResult(SignStatus.FAILED, "未识别的签到结果")
                }
            }
        } catch (e: IOException) {
            SignResult(SignStatus.FAILED, "网络请求失败，请重新扫码重试")
        }
    }
}
