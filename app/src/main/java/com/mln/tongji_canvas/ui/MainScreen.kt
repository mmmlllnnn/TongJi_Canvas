package com.mln.tongji_canvas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mln.tongji_canvas.data.SessionRepository
import com.mln.tongji_canvas.data.UserSession
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.material3.Switch

@Composable
fun MainScreen(
    repository: SessionRepository,
    onAddUser: () -> Unit,
    onScanQr: (List<String>) -> Unit,
    onTestSession: (UserSession) -> Unit
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<UserSession>>(emptyList()) }
    var viewingSession by remember { mutableStateOf<UserSession?>(null) }
    var editingSession by remember { mutableStateOf<UserSession?>(null) }
    var editDisplayName by remember { mutableStateOf("") }
    var editAccessToken by remember { mutableStateOf("") }
    var addingUser by remember { mutableStateOf(false) }
    var addDisplayName by remember { mutableStateOf("") }
    var addAccessToken by remember { mutableStateOf("") }
    val context = LocalContext.current
    // 记录哪些用户被选中参与签到
    var selectedUserIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Snackbar状态
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    
    // 滑动检测和浮动按钮状态
    val listState = rememberLazyListState()
    var isFabVisible by remember { mutableStateOf(true) }
    var lastScrollY by remember { mutableStateOf(0f) }
    
    // 监听滑动状态
    val scrollOffset by remember {
        derivedStateOf {
            listState.firstVisibleItemScrollOffset.toFloat()
        }
    }
    
    // 根据滑动方向控制浮动按钮显示
    LaunchedEffect(scrollOffset) {
        val currentScrollY = scrollOffset
        val deltaY = currentScrollY - lastScrollY
        
        // 如果向上滑动（deltaY > 0）且滑动距离超过阈值，隐藏按钮
        if (deltaY > 15f && currentScrollY > 100f) {
            isFabVisible = false
        }
        // 如果向下滑动（deltaY < 0）或接近顶部，显示按钮
        else if (deltaY < -15f || currentScrollY < 100f) {
            isFabVisible = true
        }
        
        lastScrollY = currentScrollY
    }
    
    // 浮动按钮透明度动画
    val fabAlpha by animateFloatAsState(
        targetValue = if (isFabVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "fab_alpha"
    )

    // 自动刷新用户列表
    fun refreshSessions() {
        scope.launch {
            sessions = repository.getAllSessions()
        }
    }

    // 记录上一次的用户列表，用于检测新用户
    val previousUserIds = remember { mutableSetOf<String>() }
    var isInitialized by remember { mutableStateOf(false) }
    
    // 初始化时加载选中的用户ID
    LaunchedEffect(Unit) {
        // 加载保存的选中用户ID列表
        selectedUserIds = repository.getSelectedUserIds()
    }
    
    // 初始化时加载用户列表
    LaunchedEffect(Unit) {
        refreshSessions()
    }

    // 当从其他页面返回时自动刷新
    LaunchedEffect(repository) {
        refreshSessions()
    }
    
    // 当用户列表变化时，同步更新选中状态（移除已删除的用户，新用户默认选中）
    LaunchedEffect(sessions) {
        if (sessions.isEmpty()) return@LaunchedEffect
        
        if (!isInitialized) {
            // 初始化时，设置 previousUserIds 为当前用户列表，并确保已加载的选中状态是有效的
            val existingUserIds = sessions.map { it.id }.toSet()
            previousUserIds.clear()
            previousUserIds.addAll(existingUserIds)
            // 移除已删除用户的选中状态
            selectedUserIds = selectedUserIds.filter { existingUserIds.contains(it) }.toSet()
            isInitialized = true
            return@LaunchedEffect
        }
        
        val currentSelected = selectedUserIds.toMutableSet()
        val existingUserIds = sessions.map { it.id }.toSet()
        
        // 移除已删除的用户
        currentSelected.removeAll { !existingUserIds.contains(it) }
        
        // 检测新添加的用户（存在于当前列表但不在之前的列表中）
        val newUsers = existingUserIds.filter { !previousUserIds.contains(it) && !currentSelected.contains(it) }
        if (newUsers.isNotEmpty()) {
            // 新用户默认选中
            currentSelected.addAll(newUsers)
            selectedUserIds = currentSelected
            // 立即保存新用户的选中状态
            repository.saveSelectedUserIds(selectedUserIds)
        } else if (currentSelected != selectedUserIds) {
            // 如果只是移除了用户，也更新状态
            selectedUserIds = currentSelected
        }
        
        // 更新记录的用户ID列表
        previousUserIds.clear()
        previousUserIds.addAll(existingUserIds)
    }
    
    // 当用户手动切换开关时保存
    LaunchedEffect(selectedUserIds) {
        if (isInitialized) {
            repository.saveSelectedUserIds(selectedUserIds)
        }
    }

    Scaffold(
        snackbarHost = {
            androidx.compose.material3.SnackbarHost(snackbarHostState)
        },
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.graphicsLayer(
                    alpha = fabAlpha,
                    translationY = if (isFabVisible) 0f else 100f
                )
            ) {
                ExtendedFloatingActionButton(
                    text = { Text("扫描签到码") },
                    icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                    onClick = {
                        val selectedList = sessions.filter { selectedUserIds.contains(it.id) }.map { it.id }
                        if (selectedList.isEmpty()) {
                            // 如果没有选中任何用户，显示提示
                            scope.launch {
                                snackbarHostState.showSnackbar("请至少选择一个用户参与签到")
                            }
                        } else {
                            onScanQr(selectedList)
                        }
                    }
                )
                ExtendedFloatingActionButton(
                    text = { Text("添加用户") },
                    icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                    onClick = onAddUser
                )
            }
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("批量签到", style = MaterialTheme.typography.headlineMedium)
                IconButton(
                    onClick = { 
                        val intent = Intent(context, com.mln.tongji_canvas.AboutActivity::class.java)
                        context.startActivity(intent)
                    }
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "关于应用",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp), 
                modifier = Modifier.weight(1f)
            ) {
                items(sessions, key = { it.id }) { s ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).clickable { viewingSession = s }) {
                                    Text(s.displayName, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(4.dp))
                                    val cookieStatus = if (s.accessToken?.isNotEmpty() ?: false) {
                                        "已保存认证信息"
                                    } else {
                                        "未保存认证信息"
                                    }
                                    Text(cookieStatus, style = MaterialTheme.typography.bodySmall)
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                    
                                    Switch(
                                        checked = selectedUserIds.contains(s.id),
                                        onCheckedChange = { checked ->
                                            val updated = if (checked) {
                                                selectedUserIds + s.id
                                            } else {
                                                selectedUserIds - s.id
                                            }
                                            selectedUserIds = updated
                                            // 立即保存开关状态变化
                                            repository.saveSelectedUserIds(updated)
                                        }
                                    )
                                    IconButton(
                                        onClick = {
                                            editingSession = s
                                            editDisplayName = s.displayName
                                            editAccessToken = s.accessToken ?: ""
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "编辑用户",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onTestSession(s) }) { Text("测试") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        repository.removeSession(s.id)
                                        refreshSessions()
                                        selectedUserIds = selectedUserIds - s.id
                                    }
                                }) { Text("删除") }
                            }
                        }
                    }
                }
                
                // 添加用户卡片
                item {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { addingUser = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "添加用户",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "添加自定义用户",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    // 查看用户信息对话框
    val toShow = viewingSession
    if (toShow != null) {
        AlertDialog(
            onDismissRequest = { viewingSession = null },
            title = { Text(text = toShow.displayName + " 的认证信息") },
            text = {
                SelectionContainer {
                    val cookieInfo = buildString {
                        appendLine("认证信息: ${toShow.accessToken ?: "无"}")
                        appendLine("最后验证: ${toShow.lastVerifiedAt?.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: "无"}")
                    }
                    Text(cookieInfo, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingSession = null }) { Text("关闭") }
            }
        )
    }

    // 编辑用户信息对话框
    val toEdit = editingSession
    if (toEdit != null) {
        AlertDialog(
            onDismissRequest = { 
                editingSession = null
                editDisplayName = ""
                editAccessToken = ""
            },
            title = { Text("编辑用户信息") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editDisplayName,
                        onValueChange = { editDisplayName = it },
                        label = { Text("用户昵称") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = editAccessToken,
                        onValueChange = { editAccessToken = it },
                        label = { Text("认证信息") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            // 更新用户信息
                            val updatedSession = toEdit.copy(
                                displayName = editDisplayName,
                                accessToken = editAccessToken.ifEmpty { null }
                            )
                            println("准备更新用户: 原ID=${toEdit.id}, 原昵称=${toEdit.displayName}, 新昵称=${editDisplayName}")
                            repository.updateSession(updatedSession)
                            refreshSessions()
                            editingSession = null
                            editDisplayName = ""
                            editAccessToken = ""
                        }
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        editingSession = null
                        editDisplayName = ""
                        editAccessToken = ""
                    }
                ) { Text("取消") }
            }
        )
    }

    // 添加用户对话框
    if (addingUser) {
        AlertDialog(
            onDismissRequest = { 
                addingUser = false
                addDisplayName = ""
                addAccessToken = ""
            },
            title = { Text("添加自定义用户") },
            text = {
                Column {
                    OutlinedTextField(
                        value = addDisplayName,
                        onValueChange = { addDisplayName = it },
                        label = { Text("用户昵称") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = addAccessToken,
                        onValueChange = { addAccessToken = it },
                        label = { Text("认证信息") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (addDisplayName.isNotBlank()) {
                            scope.launch {
                                // 创建新的用户会话
                                val tokens = com.mln.tongji_canvas.data.OAuth2Tokens(
                                    accessToken = addAccessToken.ifEmpty { null },
                                    refreshToken = null,
                                    expiresIn = 3600L
                                )
                                repository.addOrUpdateSession(addDisplayName, tokens)
                                refreshSessions()
                                addingUser = false
                                addDisplayName = ""
                                addAccessToken = ""
                            }
                        }
                    }
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        addingUser = false
                        addDisplayName = ""
                        addAccessToken = ""
                    }
                ) { Text("取消") }
            }
        )
    }
}