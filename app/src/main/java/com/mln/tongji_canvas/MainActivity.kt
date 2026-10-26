package com.mln.tongji_canvas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mln.tongji_canvas.data.AppUpdate
import com.mln.tongji_canvas.data.AppUpdateChecker
import com.mln.tongji_canvas.data.SessionRepository
import com.mln.tongji_canvas.data.SignHistoryStore
import com.mln.tongji_canvas.ui.OAuth2LoginScreen
import com.mln.tongji_canvas.ui.MainScreen
import com.mln.tongji_canvas.ui.ScannerScreen
import com.mln.tongji_canvas.ui.SimpleScannerScreen
import com.mln.tongji_canvas.ui.EnhancedScannerScreen
import com.mln.tongji_canvas.ui.BatchSignScreen
import com.mln.tongji_canvas.ui.SignHistoryScreen
import com.mln.tongji_canvas.ui.theme.Canvas_batch_signTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = SessionRepository(this)
        val history = SignHistoryStore(this)
        setContent {
            Canvas_batch_signTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "main") {
                    composable(
                        "main",
                        exitTransition = {
                            if (targetState.destination.route == "history") {
                                slideOutHorizontally(
                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                    targetOffsetX = { -it / 4 }
                                )
                            } else null
                        },
                        popEnterTransition = {
                            if (initialState.destination.route == "history") {
                                slideInHorizontally(
                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                    initialOffsetX = { -it / 4 }
                                )
                            } else null
                        }
                    ) {
                        MainScreen(
                            repository = repository,
                            onAddUser = { nav.navigate("login") },
                            onScanQr = { selectedUserIds ->
                                // 将选中的用户ID列表编码为URL参数
                                val userIdsParam = selectedUserIds.joinToString(",")
                                nav.navigate("scanner?selectedUsers=" + java.net.URLEncoder.encode(userIdsParam, "UTF-8"))
                            },
                            onOpenHistory = { nav.navigate("history") },
                            onTestSession = { /* TODO: open test page with cookies */ }
                        )
                    }
                    composable("login") {
                        OAuth2LoginScreen(repository = repository, initialUrl = "https://canvas.tongji.edu.cn/") { nav.popBackStack() }
                    }
                    composable(
                        "history",
                        enterTransition = {
                            slideInHorizontally(
                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                                initialOffsetX = { it }
                            )
                        },
                        popExitTransition = {
                            slideOutHorizontally(
                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                                targetOffsetX = { it }
                            )
                        }
                    ) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            SignHistoryScreen(store = history, onBack = { nav.popBackStack() })
                        }
                    }
                    composable("scanner?selectedUsers={selectedUsers}") { backStackEntry ->
                        val selectedUsersParam = backStackEntry.arguments?.getString("selectedUsers")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
                        EnhancedScannerScreen { detectedUrl ->
                            nav.navigate("batchsign?url=" + java.net.URLEncoder.encode(detectedUrl, "UTF-8") + 
                                "&selectedUsers=" + java.net.URLEncoder.encode(selectedUsersParam, "UTF-8"))
                        }
                    }
                    composable("scanner") {
                        EnhancedScannerScreen { detectedUrl ->
                            nav.navigate("batchsign?url=" + java.net.URLEncoder.encode(detectedUrl, "UTF-8"))
                        }
                    }
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
                    composable("batchsign?url={url}") { backStackEntry ->
                        val url = backStackEntry.arguments?.getString("url")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
                        BatchSignScreen(repository = repository, targetUrl = url, selectedUserIds = emptySet())
                    }
                }
                AppUpdatePrompt()
            }
        }
    }
}

@Composable
private fun AppUpdatePrompt() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val checker = remember { AppUpdateChecker(context.applicationContext) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }

    LaunchedEffect(Unit) {
        update = checker.check(BuildConfig.VERSION_CODE)
    }

    val release = update ?: return
    AlertDialog(
        onDismissRequest = { update = null },
        title = { Text("发现新版本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                Text("最新发布：${release.tag}")
                Text(
                    release.pageUrl,
                    modifier = Modifier.clickable {
                        update = null
                        uriHandler.openUri(release.pageUrl)
                    },
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { update = null }) { Text("关闭") }
        },
        dismissButton = {
            TextButton(onClick = {
                checker.ignore(release.tag)
                update = null
            }) { Text("不再显示") }
        }
    )
}
