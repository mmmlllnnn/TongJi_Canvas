package com.mln.tongji_canvas.ui

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.mln.tongji_canvas.data.Course
import com.mln.tongji_canvas.data.SessionRepository
import com.mln.tongji_canvas.data.UserSession
import com.mln.tongji_canvas.ui.components.EmptyStatePanel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.absoluteValue
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun MainScreen(
    repository: SessionRepository,
    onAddUser: () -> Unit,
    onScanQr: (List<String>) -> Unit,
    onTestSession: (UserSession) -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val pageBackground = Color(0xFFF5F7FB)
    val clipboardManager = LocalClipboardManager.current

    var sessions by remember { mutableStateOf<List<UserSession>>(emptyList()) }
    var courses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var selectedUserIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editingSession by remember { mutableStateOf<UserSession?>(null) }
    var editDisplayName by remember { mutableStateOf("") }
    var editAccessToken by remember { mutableStateOf("") }
    var editUserCourseIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var addingUser by remember { mutableStateOf(false) }
    var addDisplayName by remember { mutableStateOf("") }
    var addAccessToken by remember { mutableStateOf("") }
    var showAddMenu by remember { mutableStateOf(false) }

    // 课程相关状态
    var showAddCourseDialog by remember { mutableStateOf(false) }
    var addCourseName by remember { mutableStateOf("") }
    var editingCourse by remember { mutableStateOf<Course?>(null) }
    var editCourseName by remember { mutableStateOf("") }
    var editCourseMemberIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 折叠状态
    var allUsersExpanded by remember { mutableStateOf(true) }
    var expandedCourseIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val previousUserIds = remember { mutableSetOf<String>() }
    var isInitialized by remember { mutableStateOf(false) }

    fun refreshSessions() {
        scope.launch {
            sessions = repository.getAllSessions()
            courses = repository.getAllCourses()
        }
    }

    fun exportSessionsToClipboard() {
        scope.launch {
            val exportable = sessions.filter { !it.accessToken.isNullOrBlank() }
            if (exportable.isEmpty()) {
                snackbarHostState.showSnackbar("暂无可导出的账号")
                return@launch
            }
            // 新格式：包含用户和课程信息的完整对象
            val root = JSONObject()
            val usersArray = JSONArray()
            exportable.forEach { user ->
                val obj = JSONObject()
                obj.put("displayName", user.displayName)
                obj.put("accessToken", user.accessToken)
                // 记录该用户所属的课程名称列表
                val userCourseNames = JSONArray()
                courses.filter { it.memberIds.contains(user.id) }.forEach { course ->
                    userCourseNames.put(course.name)
                }
                if (userCourseNames.length() > 0) {
                    obj.put("courses", userCourseNames)
                }
                usersArray.put(obj)
            }
            root.put("users", usersArray)
            // 导出课程列表（保留空课程信息）
            if (courses.isNotEmpty()) {
                val coursesArray = JSONArray()
                courses.forEach { course ->
                    val cObj = JSONObject()
                    cObj.put("name", course.name)
                    val memberNames = JSONArray()
                    course.memberIds.mapNotNull { mid -> sessions.firstOrNull { it.id == mid } }
                        .forEach { memberNames.put(it.displayName) }
                    cObj.put("members", memberNames)
                    coursesArray.put(cObj)
                }
                root.put("courses", coursesArray)
            }
            clipboardManager.setText(AnnotatedString(root.toString()))
            snackbarHostState.showSnackbar("已复制 ${exportable.size} 个账号及 ${courses.size} 个课程到剪贴板")
        }
    }

    fun importSessionsFromClipboard() {
        scope.launch {
            val raw = clipboardManager.getText()?.text?.trim()
            if (raw.isNullOrEmpty()) {
                snackbarHostState.showSnackbar("剪贴板为空")
                return@launch
            }
            try {
                var imported = 0
                var coursesImported = 0

                // 尝试新格式（带课程的对象格式）
                if (raw.startsWith("{")) {
                    val root = JSONObject(raw)
                    val usersArray = root.optJSONArray("users") ?: JSONArray()

                    // 名称 -> 用户ID 映射（用于后续课程成员关联）
                    val nameToIdMap = mutableMapOf<String, String>()

                    // 先导入已有用户的映射
                    sessions.forEach { nameToIdMap[it.displayName] = it.id }

                    for (i in 0 until usersArray.length()) {
                        val obj = usersArray.optJSONObject(i) ?: continue
                        val name = obj.optString("displayName", obj.optString("name")).trim()
                        val token = obj.optString("accessToken", obj.optString("token")).trim()
                        if (name.isNotEmpty() && token.isNotEmpty()) {
                            val tokens = com.mln.tongji_canvas.data.OAuth2Tokens(
                                accessToken = token,
                                refreshToken = null,
                                expiresIn = 3600L
                            )
                            val session = repository.addOrUpdateSession(name, tokens)
                            nameToIdMap[name] = session.id
                            imported++
                        }
                    }

                    // 导入课程
                    val coursesArray = root.optJSONArray("courses")
                    if (coursesArray != null) {
                        val existingCourses = repository.getAllCourses()
                        for (i in 0 until coursesArray.length()) {
                            val cObj = coursesArray.optJSONObject(i) ?: continue
                            val courseName = cObj.optString("name").trim()
                            if (courseName.isEmpty()) continue

                            val membersArr = cObj.optJSONArray("members") ?: JSONArray()
                            val memberIds = mutableListOf<String>()
                            for (j in 0 until membersArr.length()) {
                                val memberName = membersArr.optString(j).trim()
                                nameToIdMap[memberName]?.let { memberIds.add(it) }
                            }

                            // 如果同名课程已存在，合并成员；否则新建
                            val existing = existingCourses.firstOrNull { it.name == courseName }
                            if (existing != null) {
                                val mergedMembers = (existing.memberIds + memberIds).distinct()
                                repository.updateCourse(existing.copy(memberIds = mergedMembers))
                            } else {
                                val newCourse = repository.addCourse(courseName)
                                if (memberIds.isNotEmpty()) {
                                    repository.updateCourse(newCourse.copy(memberIds = memberIds))
                                }
                            }
                            coursesImported++
                        }
                    }
                } else {
                    // 兼容旧格式（纯数组）
                    val array = JSONArray(raw)
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val name = obj.optString("displayName", obj.optString("name")).trim()
                        val token = obj.optString("accessToken", obj.optString("token")).trim()
                        if (name.isNotEmpty() && token.isNotEmpty()) {
                            val tokens = com.mln.tongji_canvas.data.OAuth2Tokens(
                                accessToken = token,
                                refreshToken = null,
                                expiresIn = 3600L
                            )
                            repository.addOrUpdateSession(name, tokens)
                            imported++
                        }
                    }
                }

                if (imported > 0 || coursesImported > 0) {
                    refreshSessions()
                    val msg = buildString {
                        append("成功导入 $imported 个账号")
                        if (coursesImported > 0) append("、$coursesImported 个课程")
                    }
                    snackbarHostState.showSnackbar(msg)
                } else {
                    snackbarHostState.showSnackbar("未识别到可导入的账号")
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("剪贴板内容格式有误")
            }
        }
    }

    LaunchedEffect(Unit) {
        selectedUserIds = repository.getSelectedUserIds()
        refreshSessions()
    }

    LaunchedEffect(repository) { refreshSessions() }

    LaunchedEffect(sessions) {
        if (sessions.isEmpty()) return@LaunchedEffect
        val allIds = sessions.map { it.id }.toSet()
        if (!isInitialized) {
            previousUserIds.clear()
            previousUserIds.addAll(allIds)
            selectedUserIds = selectedUserIds.filter { allIds.contains(it) }.toSet()
            isInitialized = true
            return@LaunchedEffect
        }
        val current = selectedUserIds.toMutableSet()
        current.removeAll { !allIds.contains(it) }
        val newUsers = allIds.filter { !previousUserIds.contains(it) && !current.contains(it) }
        if (newUsers.isNotEmpty()) {
            current.addAll(newUsers)
            selectedUserIds = current
            repository.saveSelectedUserIds(selectedUserIds)
        } else if (current != selectedUserIds) {
            selectedUserIds = current
        }
        previousUserIds.clear()
        previousUserIds.addAll(allIds)
    }

    LaunchedEffect(selectedUserIds) {
        if (isInitialized) {
            repository.saveSelectedUserIds(selectedUserIds)
        }
    }

    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topBarState)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .background(pageBackground),
        topBar = {
            MainScreenTopBar(
                sessionCount = sessions.size,
                selectedCount = selectedUserIds.size,
                courseCount = courses.size,
                scrollBehavior = scrollBehavior,
                onImportClick = { importSessionsFromClipboard() },
                onExportClick = { exportSessionsToClipboard() },
                onAboutClick = {
                    val intent = Intent(context, com.mln.tongji_canvas.AboutActivity::class.java)
                    context.startActivity(intent)
                }
            )
        },
        bottomBar = {
            FloatingDock(
                selectedCount = selectedUserIds.size,
                onAdd = { showAddMenu = true }
            ) {
                val targets = sessions.filter { selectedUserIds.contains(it.id) }.map { it.id }
                if (targets.isEmpty()) {
                    scope.launch {
                        snackbarHostState.showSnackbar("请至少选择一个用户参与签到")
                    }
                } else {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onScanQr(targets)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            pageBackground,
                            Color(0xFFE9EDFF),
                            Color(0xFFDDE4FF)
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            AnimatedContent(
                targetState = sessions.isEmpty(),
                label = "session_list_state"
            ) { isEmpty ->
                if (isEmpty) {
                    EmptyStatePanel(
                        icon = Icons.Outlined.PersonAdd,
                        title = "暂无账号",
                        subtitle = "添加任意 Canvas 账号后即可一键批量签到",
                        primaryActionLabel = "添加账号",
                        onPrimaryAction = { showAddMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    GroupedSessionList(
                        sessions = sessions,
                        courses = courses,
                        selectedUserIds = selectedUserIds,
                        allUsersExpanded = allUsersExpanded,
                        expandedCourseIds = expandedCourseIds,
                        onToggleAllUsersExpanded = { allUsersExpanded = !allUsersExpanded },
                        onToggleCourseExpanded = { courseId ->
                            expandedCourseIds = if (expandedCourseIds.contains(courseId)) {
                                expandedCourseIds - courseId
                            } else {
                                expandedCourseIds + courseId
                            }
                        },
                        onToggleSelection = { id, checked ->
                            val updated = if (checked) selectedUserIds + id else selectedUserIds - id
                            selectedUserIds = updated
                            scope.launch { repository.saveSelectedUserIds(updated) }
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        },
                        onSelectAllInCourse = { course ->
                            val updated = selectedUserIds + course.memberIds.filter { mid ->
                                sessions.any { it.id == mid }
                            }
                            selectedUserIds = updated
                            scope.launch { repository.saveSelectedUserIds(updated) }
                        },
                        onDeselectAllInCourse = { course ->
                            val updated = selectedUserIds - course.memberIds.toSet()
                            selectedUserIds = updated
                            scope.launch { repository.saveSelectedUserIds(updated) }
                        },
                        onEditUser = { session ->
                            editingSession = session
                            editDisplayName = session.displayName
                            editAccessToken = session.accessToken.orEmpty()
                            editUserCourseIds = courses.filter { it.memberIds.contains(session.id) }.map { it.id }.toSet()
                        },
                        onDeleteUser = { session ->
                            scope.launch {
                                repository.removeSession(session.id)
                                // 同时从所有课程中移除该用户
                                val allCourses = repository.getAllCourses()
                                allCourses.forEach { course ->
                                    if (course.memberIds.contains(session.id)) {
                                        repository.updateCourse(course.copy(memberIds = course.memberIds - session.id))
                                    }
                                }
                                refreshSessions()
                            }
                        },
                        onEditCourse = { course ->
                            editingCourse = course
                            editCourseName = course.name
                            editCourseMemberIds = course.memberIds.toSet()
                        },
                        onDeleteCourse = { course ->
                            scope.launch {
                                repository.removeCourse(course.id)
                                refreshSessions()
                            }
                        },
                        onAddCourse = { showAddCourseDialog = true }
                    )
                }
            }
        }
    }

    AddUserMenu(
        expanded = showAddMenu,
        onDismiss = { showAddMenu = false },
        onOAuthAdd = {
            showAddMenu = false
            onAddUser()
        },
        onManualAdd = {
            showAddMenu = false
            addingUser = true
        }
    )

    // 编辑用户对话框（带课程分配）
    EditingDialogWithCourses(
        editingSession = editingSession,
        editDisplayName = editDisplayName,
        editAccessToken = editAccessToken,
        editUserCourseIds = editUserCourseIds,
        courses = courses,
        onDisplayNameChange = { editDisplayName = it },
        onAccessTokenChange = { editAccessToken = it },
        onCourseToggle = { courseId, checked ->
            editUserCourseIds = if (checked) editUserCourseIds + courseId else editUserCourseIds - courseId
        },
        onDismiss = {
            editingSession = null
            editDisplayName = ""
            editAccessToken = ""
            editUserCourseIds = emptySet()
        },
        onSave = { session ->
            scope.launch {
                repository.updateSession(session.copy(
                    displayName = editDisplayName,
                    accessToken = editAccessToken.ifBlank { null }
                ))
                // 更新课程成员
                val allCourses = repository.getAllCourses()
                allCourses.forEach { course ->
                    val shouldContain = editUserCourseIds.contains(course.id)
                    val currentlyContains = course.memberIds.contains(session.id)
                    if (shouldContain && !currentlyContains) {
                        repository.updateCourse(course.copy(memberIds = course.memberIds + session.id))
                    } else if (!shouldContain && currentlyContains) {
                        repository.updateCourse(course.copy(memberIds = course.memberIds - session.id))
                    }
                }
                refreshSessions()
                editingSession = null
                editDisplayName = ""
                editAccessToken = ""
                editUserCourseIds = emptySet()
            }
        }
    )

    AddUserDialog(
        visible = addingUser,
        displayName = addDisplayName,
        accessToken = addAccessToken,
        onDisplayNameChange = { addDisplayName = it },
        onAccessTokenChange = { addAccessToken = it },
        onDismiss = {
            addingUser = false
            addDisplayName = ""
            addAccessToken = ""
        },
        onConfirm = {
            if (addDisplayName.isNotBlank()) {
                scope.launch {
                    val tokens = com.mln.tongji_canvas.data.OAuth2Tokens(
                        accessToken = addAccessToken.ifBlank { null },
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
    )

    // 添加课程对话框
    AddCourseDialog(
        visible = showAddCourseDialog,
        courseName = addCourseName,
        onCourseNameChange = { addCourseName = it },
        onDismiss = {
            showAddCourseDialog = false
            addCourseName = ""
        },
        onConfirm = {
            if (addCourseName.isNotBlank()) {
                scope.launch {
                    repository.addCourse(addCourseName)
                    refreshSessions()
                    showAddCourseDialog = false
                    addCourseName = ""
                }
            }
        }
    )

    // 编辑课程对话框
    EditCourseDialog(
        editingCourse = editingCourse,
        editCourseName = editCourseName,
        editCourseMemberIds = editCourseMemberIds,
        allSessions = sessions,
        onCourseNameChange = { editCourseName = it },
        onMemberToggle = { userId, checked ->
            editCourseMemberIds = if (checked) editCourseMemberIds + userId else editCourseMemberIds - userId
        },
        onDismiss = {
            editingCourse = null
            editCourseName = ""
            editCourseMemberIds = emptySet()
        },
        onSave = { course ->
            scope.launch {
                repository.updateCourse(course.copy(
                    name = editCourseName,
                    memberIds = editCourseMemberIds.toList()
                ))
                refreshSessions()
                editingCourse = null
                editCourseName = ""
                editCourseMemberIds = emptySet()
            }
        }
    )
}

// ==================== 分组列表 ====================

@Composable
private fun GroupedSessionList(
    sessions: List<UserSession>,
    courses: List<Course>,
    selectedUserIds: Set<String>,
    allUsersExpanded: Boolean,
    expandedCourseIds: Set<String>,
    onToggleAllUsersExpanded: () -> Unit,
    onToggleCourseExpanded: (String) -> Unit,
    onToggleSelection: (String, Boolean) -> Unit,
    onSelectAllInCourse: (Course) -> Unit,
    onDeselectAllInCourse: (Course) -> Unit,
    onEditUser: (UserSession) -> Unit,
    onDeleteUser: (UserSession) -> Unit,
    onEditCourse: (Course) -> Unit,
    onDeleteCourse: (Course) -> Unit,
    onAddCourse: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // "全部人员" 分组
        item(key = "header_all") {
            GroupHeader(
                title = "全部人员",
                count = sessions.size,
                icon = Icons.Outlined.People,
                expanded = allUsersExpanded,
                onToggleExpanded = onToggleAllUsersExpanded,
                accentColor = Color(0xFF7C3AED)
            )
        }

        if (allUsersExpanded) {
            items(sessions, key = { "all_${it.id}" }) { session ->
                SwipeableSessionCard(
                    session = session,
                    selected = selectedUserIds.contains(session.id),
                    onToggleSelection = { checked -> onToggleSelection(session.id, checked) },
                    onEdit = { onEditUser(session) },
                    onDelete = { onDeleteUser(session) }
                )
            }
        }

        // 各个课程分组
        courses.forEach { course ->
            val courseExpanded = expandedCourseIds.contains(course.id)
            val courseMembers = sessions.filter { course.memberIds.contains(it.id) }
            val allSelected = courseMembers.isNotEmpty() && courseMembers.all { selectedUserIds.contains(it.id) }

            item(key = "header_course_${course.id}") {
                Spacer(Modifier.height(4.dp))
                CourseGroupHeader(
                    course = course,
                    memberCount = courseMembers.size,
                    expanded = courseExpanded,
                    allSelected = allSelected,
                    onToggleExpanded = { onToggleCourseExpanded(course.id) },
                    onToggleSelectAll = {
                        if (allSelected) onDeselectAllInCourse(course) else onSelectAllInCourse(course)
                    },
                    onEdit = { onEditCourse(course) },
                    onDelete = { onDeleteCourse(course) }
                )
            }

            if (courseExpanded) {
                if (courseMembers.isEmpty()) {
                    item(key = "empty_course_${course.id}") {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFFF8F9FD)
                        ) {
                            Text(
                                "暂无成员，点击编辑添加",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(courseMembers, key = { "course_${course.id}_${it.id}" }) { session ->
                        SwipeableSessionCard(
                            session = session,
                            selected = selectedUserIds.contains(session.id),
                            onToggleSelection = { checked -> onToggleSelection(session.id, checked) },
                            onEdit = { onEditUser(session) },
                            onDelete = { onDeleteUser(session) }
                        )
                    }
                }
            }
        }

        // 添加课程按钮
        item(key = "add_course_button") {
            Spacer(Modifier.height(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFF0F0FF),
                border = BorderStroke(1.dp, Color(0xFFE0E0F0))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAddCourse)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null,
                        tint = Color(0xFF7C3AED),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "新建课程分组",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF7C3AED)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    icon: ImageVector,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    accentColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = accentColor.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(22.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = accentColor,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = accentColor.copy(alpha = 0.15f)
            ) {
                Text(
                    "$count",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor
                )
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun CourseGroupHeader(
    course: Course,
    memberCount: Int,
    expanded: Boolean,
    allSelected: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val accentColor = Color(0xFF0EA5E9)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = accentColor.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = accentColor, modifier = Modifier.size(22.dp))
            Text(
                course.name,
                style = MaterialTheme.typography.titleMedium,
                color = accentColor,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = accentColor.copy(alpha = 0.15f)
            ) {
                Text(
                    "$memberCount",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor
                )
            }
            // 全选/取消全选
            if (memberCount > 0) {
                IconButton(onClick = onToggleSelectAll, modifier = Modifier.size(36.dp)) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { onToggleSelectAll() },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑课程", tint = accentColor, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除课程", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ==================== TopBar ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenTopBar(
    sessionCount: Int,
    selectedCount: Int,
    courseCount: Int,
    scrollBehavior: TopAppBarScrollBehavior,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    onAboutClick: () -> Unit
) {
    androidx.compose.material3.LargeTopAppBar(
        title = {
            Column {
                Text("批量签到", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "共 $sessionCount 个账号",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (courseCount > 0) {
                        Text(
                            "$courseCount 个课程",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selectedCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFFE7E0FF),
                            tonalElevation = 0.dp
                        ) {
                            Text(
                                "已选 $selectedCount 个",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF5C33CF)
                            )
                        }
                    }
                }
            }
        },
        actions = {
            TopBarActionButton(
                icon = Icons.Outlined.ContentPaste,
                contentDescription = "剪贴板导入",
                onClick = onImportClick
            )
            TopBarActionButton(
                icon = Icons.Outlined.IosShare,
                contentDescription = "导出账号",
                onClick = onExportClick
            )
            TopBarActionButton(
                icon = Icons.Outlined.Info,
                contentDescription = "关于",
                onClick = onAboutClick
            )
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun TopBarActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .padding(horizontal = 2.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp)
        )
    }
}

// ==================== 添加菜单 ====================

@Composable
private fun AddUserMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOAuthAdd: () -> Unit,
    onManualAdd: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.background(Color.Transparent)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.padding(4.dp)
        ) {
            Column(
                modifier = Modifier
                    .width(240.dp)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AddMenuOption(
                    icon = Icons.Outlined.PhoneIphone,
                    title = "在线登录添加",
                    description = "自动捕获认证信息",
                    accent = Color(0xFF7C3AED),
                    onClick = {
                        onOAuthAdd()
                        onDismiss()
                    }
                )
                AddMenuOption(
                    icon = Icons.Outlined.Computer,
                    title = "手动录入 Cookies",
                    description = "适合已有认证信息的账号",
                    accent = Color(0xFF0EA5E9),
                    onClick = {
                        onManualAdd()
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun AddMenuOption(
    icon: ImageVector,
    title: String,
    description: String,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================== 用户卡片 ====================

@Composable
private fun SwipeableSessionCard(
    session: UserSession,
    selected: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val swipeWidth = 96.dp
    val swipeWidthPx = with(density) { swipeWidth.toPx() }
    var offsetPx by remember { mutableFloatStateOf(0f) }
    val statusInfo = SessionStatusInfo(session)
    val borderStroke = if (selected) {
        BorderStroke(2.dp, Color(0xFFB8A2FF))
    } else {
        BorderStroke(1.dp, Color(0xFFE6E8F2))
    }
    val surfaceColor = Color.White

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 16.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .width(swipeWidth)
                    .fillMaxHeight()
                    .defaultMinSize(minHeight = 64.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            offsetPx = 0f
                            onDelete()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onError
                        )
                        Text(
                            text = "删除",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetPx.toInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val threshold = swipeWidthPx / 2
                            offsetPx = if (abs(offsetPx) > threshold) -swipeWidthPx else 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            val newOffset = (offsetPx + dragAmount).coerceIn(-swipeWidthPx, 0f)
                            offsetPx = newOffset
                        }
                    )
                }
                .clip(RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(30.dp),
            tonalElevation = if (selected) 4.dp else 1.dp,
            shadowElevation = if (selected) 10.dp else 2.dp,
            color = surfaceColor,
            border = borderStroke
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit() }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SessionAvatar(name = session.displayName)
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Switch(checked = selected, onCheckedChange = onToggleSelection)
                    }
                    Spacer(Modifier.height(4.dp))
                    SessionStatusLabel(statusInfo)
                }
            }
        }
    }
}

@Composable
private fun SessionAvatar(name: String) {
    val initials = remember(name) { initialFor(name) }
    val style = remember(name) {
        avatarStyles[(name.hashCode().absoluteValue) % avatarStyles.size]
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(style.background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            color = style.foreground
        )
    }
}

@Composable
private fun SessionStatusLabel(statusInfo: SessionStatusUi) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        statusInfo.dotColor?.let { dot ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
        }
        Text(
            text = statusInfo.text,
            style = MaterialTheme.typography.bodySmall,
            color = statusInfo.color
        )
    }
}

@Composable
private fun SessionStatusInfo(session: UserSession): SessionStatusUi {
    return if (session.accessToken.isNullOrEmpty()) {
        SessionStatusUi(
            text = "等待手动验证",
            color = Color(0xFFF97316),
            dotColor = Color(0xFFF97316)
        )
    } else {
        SessionStatusUi(
            text = "已保存认证信息",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            dotColor = null
        )
    }
}

private data class SessionStatusUi(
    val text: String,
    val color: Color,
    val dotColor: Color?
)

private fun initialFor(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    return trimmed.first().toString().uppercase(Locale.getDefault())
}

private data class AvatarStyle(val background: Color, val foreground: Color)

private val avatarStyles = listOf(
    AvatarStyle(Color(0xFFE6DEFF), Color(0xFF6A49FF)),
    AvatarStyle(Color(0xFFFCE1EF), Color(0xFFD23C7B)),
    AvatarStyle(Color(0xFFFFE4D6), Color(0xFFC05B29)),
    AvatarStyle(Color(0xFFE1F6F3), Color(0xFF1E8A78)),
    AvatarStyle(Color(0xFFE0ECFF), Color(0xFF1E5BB8)),
    AvatarStyle(Color(0xFFF7E5FF), Color(0xFF8A32B8))
)

// ==================== 底部栏 ====================

@Composable
private fun FloatingDock(
    selectedCount: Int,
    onAdd: () -> Unit,
    onScan: () -> Unit
) {
    val scanEnabled = selectedCount > 0
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(40.dp),
            color = Color(0xFFF9FAFF),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DockButton(
                    modifier = Modifier.weight(1f),
                    label = "添加账号",
                    icon = Icons.Outlined.Add,
                    onClick = onAdd
                )
                GradientDockButton(
                    modifier = Modifier.weight(1.2f),
                    enabled = scanEnabled,
                    label = if (selectedCount == 0) "一键签到" else "一键签到（$selectedCount）",
                    onClick = onScan
                )
            }
        }
    }
}

@Composable
private fun DockButton(
    modifier: Modifier = Modifier,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFFF1F5F9),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF475569))
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = Color(0xFF475569),
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

@Composable
private fun GradientDockButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val gradientColors = if (enabled) {
        listOf(Color(0xFF7C3AED), Color(0xFF9333EA))
    } else {
        listOf(Color(0xFFD7DDED), Color(0xFFD7DDED))
    }
    val contentColor = if (enabled) Color.White else Color(0xFF94A3B8)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.horizontalGradient(gradientColors))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp)
            .height(54.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = contentColor)
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = contentColor,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1
            )
        }
    }
}

// ==================== 对话框 ====================

@Composable
private fun EditingDialogWithCourses(
    editingSession: UserSession?,
    editDisplayName: String,
    editAccessToken: String,
    editUserCourseIds: Set<String>,
    courses: List<Course>,
    onDisplayNameChange: (String) -> Unit,
    onAccessTokenChange: (String) -> Unit,
    onCourseToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (UserSession) -> Unit
) {
    if (editingSession == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(editingSession) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("编辑用户信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = editDisplayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text("用户昵称") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editAccessToken,
                    onValueChange = onAccessTokenChange,
                    label = { Text("认证信息") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                if (courses.isNotEmpty()) {
                    Text(
                        "所属课程",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    courses.forEach { course ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onCourseToggle(course.id, !editUserCourseIds.contains(course.id)) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = editUserCourseIds.contains(course.id),
                                onCheckedChange = { checked -> onCourseToggle(course.id, checked) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(course.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun AddUserDialog(
    visible: Boolean,
    displayName: String,
    accessToken: String,
    onDisplayNameChange: (String) -> Unit,
    onAccessTokenChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("手动录入 Cookies") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text("用户昵称") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = accessToken,
                    onValueChange = onAccessTokenChange,
                    label = { Text("认证信息 (Cookies)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
            }
        }
    )
}

@Composable
private fun AddCourseDialog(
    visible: Boolean,
    courseName: String,
    onCourseNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("新建课程分组") },
        text = {
            OutlinedTextField(
                value = courseName,
                onValueChange = onCourseNameChange,
                label = { Text("课程名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    )
}

@Composable
private fun EditCourseDialog(
    editingCourse: Course?,
    editCourseName: String,
    editCourseMemberIds: Set<String>,
    allSessions: List<UserSession>,
    onCourseNameChange: (String) -> Unit,
    onMemberToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Course) -> Unit
) {
    if (editingCourse == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(editingCourse) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("编辑课程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = editCourseName,
                    onValueChange = onCourseNameChange,
                    label = { Text("课程名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (allSessions.isNotEmpty()) {
                    Text(
                        "选择成员",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    allSessions.forEach { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onMemberToggle(session.id, !editCourseMemberIds.contains(session.id)) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = editCourseMemberIds.contains(session.id),
                                onCheckedChange = { checked -> onMemberToggle(session.id, checked) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(session.displayName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    )
}
