package com.mln.tongji_canvas

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mln.tongji_canvas.data.SessionRepository
import com.mln.tongji_canvas.ui.OAuth2LoginScreen
import com.mln.tongji_canvas.ui.MainScreen
import com.mln.tongji_canvas.ui.EnhancedScannerScreen
import com.mln.tongji_canvas.ui.BatchSignScreen
import com.mln.tongji_canvas.ui.theme.Canvas_batch_signTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 本应用承载他人账号的登录态，禁止截屏、录屏与「最近任务」预览，
        // 避免会话凭据随界面截图外泄。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()
        val repository = SessionRepository(this)
        setContent {
            Canvas_batch_signTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            repository = repository,
                            onAddUser = { nav.navigate("login") },
                            onScanQr = { selectedUserIds ->
                                // 将选中的用户ID列表编码为URL参数
                                val userIdsParam = selectedUserIds.joinToString(",")
                                nav.navigate("scanner?selectedUsers=" + java.net.URLEncoder.encode(userIdsParam, "UTF-8"))
                            },
                            onTestSession = { /* TODO: open test page with cookies */ }
                        )
                    }
                    composable("login") {
                        OAuth2LoginScreen(repository = repository, initialUrl = "https://canvas.tongji.edu.cn/") { nav.popBackStack() }
                    }
                    // 唯一的扫码入口：onScanQr 总是带上 selectedUsers 参数，
                    // 原来的无参 "scanner" 路由被本路由的默认参数覆盖，属不可达死代码，已删除。
                    composable("scanner?selectedUsers={selectedUsers}") { backStackEntry ->
                        val selectedUsersParam = backStackEntry.arguments?.getString("selectedUsers")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
                        EnhancedScannerScreen { detectedUrl ->
                            nav.navigate("batchsign?url=" + java.net.URLEncoder.encode(detectedUrl, "UTF-8") +
                                "&selectedUsers=" + java.net.URLEncoder.encode(selectedUsersParam, "UTF-8"))
                        }
                    }
                    // 唯一的签到入口：selectedUsers 缺省时按空集合处理，
                    // 原来的 "batchsign?url={url}" 路由被本路由覆盖，属不可达死代码，已删除。
                    composable("batchsign?url={url}&selectedUsers={selectedUsers}") { backStackEntry ->
                        val url = backStackEntry.arguments?.getString("url")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
                        val selectedUsersParam = backStackEntry.arguments?.getString("selectedUsers")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
                        val selectedUserIds = if (selectedUsersParam.isNotEmpty()) {
                            selectedUsersParam.split(",").toSet()
                        } else {
                            emptySet<String>()
                        }
                        BatchSignScreen(repository = repository, targetUrl = url, selectedUserIds = selectedUserIds)
                    }
                }
            }
        }
    }
}
