package com.mln.tongji_canvas.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AppUpdate(val tag: String, val pageUrl: String)

class AppUpdateChecker(context: Context) {
    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun check(currentVersionCode: Int): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/mmmlllnnn/TongJi_Canvas/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "TongJi-Canvas-Android")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val release = JSONObject(response.body?.string().orEmpty())
                val tag = release.getString("tag_name")
                if (!tag.matches(Regex("[0-9]{8}"))) return@withContext null
                if (tag.toInt() <= currentVersionCode || tag == prefs.getString("ignored_tag", null)) {
                    return@withContext null
                }
                AppUpdate(tag, release.getString("html_url"))
            }
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        }
    }

    fun ignore(tag: String) {
        prefs.edit().putString("ignored_tag", tag).apply()
    }
}
