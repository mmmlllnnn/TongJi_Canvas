# 同济 Canvas 签到助手 — 项目理解与优化建议

> 审计日期：2026-10-07 ｜ 审计基线：`main @ b61162d`
> 方法：全量代码阅读 + Android SDK 源码比对 + 实际编译验证
> 证据标记：**【已验证】**= 本机编译/运行/工具确认 ｜ **【已核对】**= 逐行读码确认 ｜ **【推断】**= 静态推断，建议真机复现

---

## 一、项目理解

### 1.1 它是什么

一个 Android 原生（Kotlin + Jetpack Compose）应用，解决**多人课堂签到**场景：

1. 多人在同一台手机上，依次通过同济统一认证（IAM）登录 Canvas；
2. App 用 WebView 拦截并保存每个账号的会话 Cookie；
3. 扫一次签到二维码，App 用 N 个账号并发重放该请求，一次性完成多人签到。

核心设计假设：**认证信息永久有效，只需获取一次**（`README.md:19`）。仓库另有 Web 版（`TongJi_Canvas_Web`），App 与 Web 共用同一套 Cookie 重放原理。

### 1.2 技术栈与规模

| 项 | 值 |
|---|---|
| 语言 / UI | Kotlin 2.0.21 + Jetpack Compose（Material3，BOM 2024.09.00） |
| 构建 | AGP 8.13.2 / Gradle 9.0.0 / Java 21 目标 |
| SDK | minSdk 28（Android 9）、compileSdk & targetSdk 36 |
| 扫码 | CameraX 1.3.4 + ML Kit Barcode 17.2.0 |
| 网络 | OkHttp 4.12.0（声明但几乎未用）；实际靠 WebView 承载登录与签到 |
| 存储 | `SharedPreferences("sessions")` 存 JSON，**无加密** |
| 代码量 | Kotlin 约 4,700 行 / 主源码 20 个文件 |
| 测试 | 3 个测试文件，合计 1 个真实断言，无 UI 测试、无 CI |

### 1.3 架构现状

```
MainActivity（NavHost，4 条路由）
├── ui/MainScreen.kt          65KB / 1635 行 —— 522 行的「上帝 Composable」
├── ui/OAuth2LoginScreen.kt   30KB /  673 行 —— WebView 登录 + Cookie 抓取
├── ui/BatchSignScreen.kt     23KB /  568 行 —— N 个 WebView 并发签到
├── ui/EnhancedScannerScreen.kt               —— ML Kit 扫码（正常释放资源）
├── ui/ScannerScreen.kt       15.6KB          —— 【死代码】无调用点
├── ui/SimpleScannerScreen.kt 12.7KB          —— 【死代码】无调用点
└── data/SessionRepository.kt + SessionModels.kt —— SharedPreferences CRUD
```

关键结构性事实：
- **没有 ViewModel、没有 DI、没有 Flow/StateFlow**（全仓库 0 处引用）；数据靠 `suspend` 取快照塞进 `mutableStateOf`。
- **没有分层**：导入/导出/选择/课程成员合并等业务逻辑全部写在 Composable 里。
- `SessionRepository` 持有 `Activity Context`（`MainActivity.kt:23` 传 `this`，而实际只需 `applicationContext`）。

---

## 二、优化清单（按优先级）

### P0-A｜批量签到「并发重放」在架构上不成立 ★最高优先级

**问题（【已核对】，已比对 Android SDK 源码）**

`BatchSignScreen.kt:513-565` 的 `applyCookiesAndLoad`：

```kotlin
val cookieManager = android.webkit.CookieManager.getInstance()  // 进程级单例
cookieManager.removeAllCookies(null)                            // 清空"所有"Cookie
cookieList.forEach { cookieManager.setCookie("https://canvas.tongji.edu.cn", cookie) }
cookieManager.flush()
webView.loadUrl(targetUrl)
```

而 `BatchSignScreen.kt:176-197` 是一个**非惰性 `Column`**，N 个 `SessionWebView` 同帧组合、**全部并发 `loadUrl`**。

根因（SDK 行为）：
- `CookieManager.getInstance()` 是**进程单例**，存储按 `(host, path, name)` 索引，**不按 WebView 隔离**——同名 Cookie 后者覆盖前者。
- `setCookie` / `removeAllCookies` 都是**异步**的，`flush()` 不保证 `removeAllCookies` 已落地；它甚至可能在 `setCookie` 之后才生效。

因此时间线必然是：A 设置自己的 Cookie → B `removeAllCookies` 把它清掉再设自己的 → A 的请求带着 **B 的会话或匿名会话**发出。**没有按 WebView 隔离 Cookie 的机制存在。**

用户可见后果（**注意：是「串号 + 间歇性」，不是「必然全部成功」**——见第十节更正说明）：
- **结果归属串号**：某些请求实际带着别人的会话或匿名会话发出。这正是实际反馈中「有人已签过、有人二维码过期」这种**混合结果**的成因——若 Cookie 真能按用户隔离，结果应趋于一致（要么整批成功、要么整批失败），而混合结果恰恰是污染的特征；
- **假成功（间歇性）**：A 的请求带上了 B 的会话、而 B 本人已签到，Canvas 返回「已签过」，`BatchSignScreen.kt:481` 把「已签过」也判为 success → 这一张卡片是假成功。是否发生取决于该请求落到 Cookie 存储时读到的是谁的会话；
- **假失败**：正确会话被别人的 `removeAllCookies`（异步、可能后到）清掉 → 表现为「二维码过期 / 未登录」；
- 每个 WebView 还会 `loadUrl` 两次（`BatchSignScreen.kt:402-407` 的 `post{}` 与 `:361-369` 的 `update`），竞态窗口翻倍；
- 「重试」按钮（`:101-107`）走同一函数，**重试一个人会清掉其他所有人的 Cookie**。

**建议**

| 方案 | 评价 |
|---|---|
| **A1（推荐）签到引擎改走 OkHttp**：每个用户一个请求，Cookie 只放在该请求的 `Cookie:` 头里；共享一个 `OkHttpClient`（连接/读超时 10s）；`Semaphore(3)` 限流；失败退避重试 ≤2 次；结果建模为 `SignResult(status, httpStatus, bodySnippet, elapsedMs, attempts)` | 彻底消除共享可变 Cookie；同时让超时/重试/取消/结构化结果成为可能。仓库自己的 Web 版（`README.md:90`）证明签到就是一个带 Cookie 的普通 HTTP 请求 |
| A2（兜底）WebView 严格串行 + 真实完成屏障，并删除全局 `removeAllCookies` | 较小改动即可消除串号，但仍受「页面文本猜测」与运行时长制约 |

> 这是全项目**唯一最高价值**的改动，P0-B/C 与 P1 多项都由它派生。

---

### P0-B｜账号没有稳定身份标识 → 每次登录都新增一个重复账号

**问题（【已核对】）**

```kotlin
// OAuth2LoginScreen.kt:585
val display = "用户${System.currentTimeMillis()}"   // 身份 = 墙上时钟毫秒
// data/SessionRepository.kt:33
val existing = all.firstOrNull { it.displayName == displayName }  // 只按昵称匹配
```

昵称每次都是新的毫秒值 ⇒ **永远匹配不上** ⇒ `all.add(session)` 必然追加。

后果：
- 同一学生登录 2 次 = 2 个无法区分的账号，各带一份 Cookie；而新账号会被自动勾选（`MainScreen.kt:304-308`），**旧 Cookie 账号被一起签到**；
- 昵称里泄露了抓取时刻，UI 上一堆「用户1728…」；
- 导入/导出也按昵称匹配（`MainScreen.kt:207/254`），因此 OAuth 建的账号**永远无法被导入更新**；同名导出还会互相覆盖，但计数照加、提示照报「成功导入 N 个」；
- 现有测试 `SessionRepositoryTest:36-46` 用硬编码 `"Tester"`，**恰好绕过了这个真实缺陷**，给出虚假安心感。

**建议**：给 `UserSession` 加稳定标识 `identityKey`（如 cookie 串的 hash，或登录后调 `/api/v1/users/self` 拿学号/姓名）；`addOrUpdateSession` 改按 `identityKey` upsert；昵称仅作展示。导出携带 `identityKey` 以支持跨设备迁移。

---

### P0-C｜学生会话凭证明文存储且随云备份外泄

**问题（【已核对】+【已验证】备份规则）**

| 项 | 证据 | 影响 |
|---|---|---|
| 明文存储 | `SessionRepository.kt:11` `getSharedPreferences("sessions", MODE_PRIVATE)`，`:85` 原样写入 Cookie 串；无 `androidx.security` 依赖 | 所有学生会话 Cookie 明文落盘 |
| 云备份 | `AndroidManifest.xml:13` `allowBackup="true"`；`backup_rules.xml:3` 与 `data_extraction_rules.xml:4` **只有注释、零排除规则**（且无 `<device-transfer>`） | `sessions.xml` **被同步到 Google Drive 并参与换机迁移** |
| 日志泄漏 | 18 处 `println`（`BatchSignScreen.kt:554-555`、`OAuth2LoginScreen.kt:573-587` 等）在 Android 上会进 logcat | 完整 Cookie 落入 `adb logcat` 与用户提交的错误报告 |
| 剪贴板 | `MainScreen.kt:160-182` 把含全部 Cookie 的 JSON 明文写进系统剪贴板 | 输入法 / 剪贴板历史类应用可读 |
| 明文流量 | `network_security_config.xml:3-6` 为两个**仅提供 HTTPS** 的域名开 `cleartextTrafficPermitted="true"`（且 `AndroidManifest.xml:21` 另有矛盾的 `usesCleartextTraffic="true"`） | 完全多余的降级许可 |
| 混合内容 | `OAuth2LoginScreen.kt:463`、`BatchSignScreen.kt:395` `MIXED_CONTENT_ALWAYS_ALLOW` | 可在 SSO 页面内注入 `http://` 脚本 → 窃取会话 |
| 截图/最近任务 | 全仓库无 `FLAG_SECURE`；`BatchSignScreen.kt:305-307` 的「查看调试」会展开**他人的已登录会话** | 持有手机者可读可抄所有 Cookie |
| JS 桥 | `OAuth2LoginScreen.kt:471-474` `addJavascriptInterface(..., "AndroidOverride")`，唯一方法 `forceOverride()` 是**空实现**且从未被原生调用 | 无实际危害，但是零收益的攻击面 |

**建议**（可拆 2-3 个提交）
1. 用 Keystore AES-GCM（或 `EncryptedSharedPreferences`）加密会话 blob；
2. `allowBackup="false"`，并显式 `<exclude domain="sharedpref" path="sessions.xml"/>`（含 `device-transfer`）；
3. 删除明文域名配置与 `usesCleartextTraffic`，改 `MIXED_CONTENT_NEVER_ALLOW`；
4. 删除全部打印 Cookie 的 `println`（保留前缀/哈希即可）；
5. 登录页与批量签到页加 `FLAG_SECURE`；
6. 删除 `AndroidOverride` 桥及其 JS 调用点；
7. 剪贴板导出加二次确认与风险提示。

---

### P1｜功能与正确性缺陷

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| P1-1 | **成功判定不可靠**：`onPageFinished` + `document.body.innerText` 中文子串匹配；字符串用 `removeSurrounding("\"")` 解析会破坏 `\"`、`\uXXXX`；**无超时**（卡片可能永久停在「签到中」）；不校验 HTTP 状态码；异步回调可覆盖终态；依赖两个精确中文子串 | `BatchSignScreen.kt:476-487` | 随 P0-A1 换成 HTTP 结果判定；若保留 WebView，需加 generation 计数 + 仅允许终态写入 + 用 `JSONTokener` 解码 + 超时看门狗 |
| P1-2 | **`processCookieQueue` 是从未被调用的死代码**——它本是唯一的串行化机制，却在 `createCleanWebView` 里被显式标为未使用并注明「立即开始加载，不等待队列」；`forceLoadTrigger` 只写不读 | `BatchSignScreen.kt:413-439`、`:384-385`、`:402`、`:71/98` | 删除失效队列，或按 A2 重写成真正的串行屏障 |
| P1-3 | **WebView 泄漏**：N 个 WebView 存在 `mutableStateMapOf`，仅在凭证哈希变化时 `destroy()`，**无 `DisposableEffect`**；登录页 WebView 同样不销毁、`webViewRef` 不清理 | `BatchSignScreen.kt:67`、`:118-124`；`OAuth2LoginScreen.kt:457-537` | 加 `onDispose { map.values.forEach { it.destroy() } }`；离开页面时取消抓取协程 |
| P1-4 | **`expiresIn = 3600L` 是编造值**：永久有效的 Cookie 被记为 1 小时过期（4 处硬编码）；`isTokenValid` 全仓库 0 调用点——**当前无害，但是埋着的地雷**，一旦被调用会把所有有效会话判为失效 | `OAuth2LoginScreen.kt:593`、`MainScreen.kt:213/260/537`、`SessionRepository.kt:88-91` | 传 `tokenExpiresAt = null`，停止编造过期时间；要么删除「永久有效」表述，要么实现真实探活 |
| P1-5 | **仓库读-改-写无并行保护，会丢更新**：`addOrUpdateSession` 每次重读整个 JSON 再整体覆写；导入是循环调用，删除/编辑与批量导入可并发 | `SessionRepository.kt:31-53`、`:85` | 加 `Mutex`，或改成单条增删改的原子操作 |
| P1-6 | **导入慢且不原子**：课程成员 diff 对每个课程各做一次全量读写（N+1 次 IO）；中途进程被杀会留下半应用状态 | `MainScreen.kt:501-510`、`:437-442` | 提供批量写 API，一次事务落盘 |
| P1-7 | **导出的 JSON 无版本号**，格式演进困难；剪贴板里是明文可读 Cookie（兼见 P0-C） | `MainScreen.kt:160-181` | 加 `version` + `app` 字段；Base64 包裹 |
| P1-8 | **导出静默丢账号**：`obj.put("accessToken", null)` 在 org.json 中会**移除该键**，导入端又要求 token 非空 ⇒ 无 token 账号导出后永久丢失，却提示「导出成功」 | `MainScreen.kt:165` + `:209` | 显式区分「无凭证账号」，导出保留条目并单独标注 |
| P1-9 | **同一次导入会建出同名重复课程**：`existingCourses` 在循环**前**读了一次快照，循环内不更新 | `MainScreen.kt:223`、`:236` | 改为循环内实时判断（或维护已创建名字集合） |
| P1-10 | **固定时间窗判登录成功**：URL 命中 `/lms/mobile/forscan` 且不含 login/oauth2 就等 5 秒抓 Cookie，5 秒内取消（返回/旋转）则**静默丢失**；且不校验抓到的是不是真实会话 | `OAuth2LoginScreen.kt:491-531`、`:294-302` | 改为「必须存在 `_canvas_middle_session` 且探活成功才算保存」，并加取消/重试 |
| P1-11 | **硬编码开发占位 URL**：`initialUrl` 形参被忽略，登录起始页写死 `courseCode=2333&rollCallToken=2333` | `OAuth2LoginScreen.kt:56/67`、`MainActivity.kt:41` | 改为可配置的入口 URL |
| P1-12 | **空/假象状态**：`sessions` 初值为 `emptyList()`，冷启动与每次旋转都先闪一下「暂无账号」 | `MainScreen.kt:118`、`:386` | 引入 Loading 三态 |
| P1-13 | **课程「全选」是影子状态，会错乱**：`checkedCourseIds` 只由两个回调更新，手动勾选/删人不更新、也不持久化 ⇒ 旋转后复选框说谎 | `MainScreen.kt:121`、`:677`、`:418/424` | 改为从 `selectedUserIds` **派生**，删除该状态 |
| P1-14 | **对话状态旋转丢失**：21 个状态全用 `remember`（非 `rememberSaveable`），旋转会关掉正在粘贴长 Cookie 的对话框并丢输入 | `MainScreen.kt:118-144` | 关键输入改 `rememberSaveable` |
| P1-15 | **主线程磁盘 IO 与主线程 JSON 解析**：首帧同步读 SharedPreferences；导入/导出解析在 Main dispatcher | `MainScreen.kt:285-286`、`:188-280` | 移入 `Dispatchers.IO/Default` |
| P1-16 | **自动命名的账号头像全是同一个字**：所有 OAuth 账号昵称都以「用户」开头 ⇒ 头像恒为「用」，完全丧失辨识度 | `MainScreen.kt:1229-1233` | 配合 P0-B 用真实姓名/学号 |
| P1-17 | **存在崩溃边界**：`styles[(name.hashCode().absoluteValue) % styles.size]` —— 若 `hashCode() == Int.MIN_VALUE`，`absoluteValue` 仍为负，取模得负索引 ⇒ `IndexOutOfBoundsException` | `MainScreen.kt:1169` | 改 `Math.floorMod(name.hashCode(), styles.size)` |
| P1-18 | **课程/账号选择状态机脆弱**：选择协调逻辑散落 5 个写入点 + 3 处持久化副作用，依赖 3 个 `LaunchedEffect` 的严格次序；`remember { mutableSetOf }` 就地修改，对 snapshot 系统不可见 | `MainScreen.kt:284-329`、`:143` | 收敛为单一 reducer，删除不可见可变状态 |

---

### P2｜架构与可维护性

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| P2-1 | **`MainScreen.kt` 是 1635 行 / 65KB 的单文件，`MainScreen` 一个 Composable 就 522 行**；其中约 430-480 行是状态与业务逻辑而非布局 | `MainScreen.kt:104-625` | 先抽 `MainViewModel` + `MainUiState`（`MainScreen(state, onEvent)`），再按 6 个文件拆分（TopBar / 列表 / 卡片 / Dock / 用户对话 / 课程对话） |
| P2-2 | **零可测试性**：19 个 Composable 中 18 个是 `private`；全仓库 `testTag` 与 `semantics` 各 0 处；repository 是具体类、无可注入接口；`@Preview` 0 个 | 全仓库 grep | 先抽纯函数编解码器（立即可单测），再让 UI 可注入假 repository |
| P2-3 | **每敲一个字符整屏重组**：21 个状态都在 `MainScreen` 根部，输入框每字符都会重建约 13 个内联 lambda，并把不稳定的 `List/Set` 与 lambda 传给列表，导致列表不可跳过 | `MainScreen.kt:118-144`、`:406-456` | 状态上移 + lambda `remember`，或抽 ViewModel |
| P2-4 | **深浅色配色重复约 15 处**，且新增颜色必须记得补深色版；`CanvasPalette.Warning` 已是孤儿 | `MainScreen.kt:379-380, 658, 699-700, 713, 720, 788, 871, 878, 977, 987, 1056-1058, 1211-1212, 1273, 1312-1350`；`Color.kt:20` | 引入 `LocalCanvasColors`（`staticCompositionLocalOf`），一次 `isSystemInDarkTheme()` 读取 |
| P2-5 | **无任何设计 token 层**：字号/圆角/间距硬编码，散落各文件 | 各处 `dp` 字面量 | 收敛为 `Dimens`/`Shapes` |
| P2-6 | **`Activity Context` 被长期持有**：`SessionRepository(context)` 收到的是 Activity（当前不泄漏，但是潜在泄漏） | `MainActivity.kt:23` vs `SessionRepository.kt:10` | 内部改用 `applicationContext` |
| P2-7 | **`AboutActivity` 用 `URL(...).readText()` 拉取远程 markdown（无超时）并渲染**——「认证信息永久有效」这句话由此进入 UI，即一个**不受控的远程字符串**决定面向用户的承诺 | `AboutActivity.kt:87-98`、`explanation.md:2` | 内联到资源文件，或至少加超时与失败兜底 |

---

### P3｜体验、国际化、无障碍

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| P3-1 | **删除只能滑动**，无可见按钮、无无障碍动作、无长按菜单；被卡片遮住的删除按钮仍在语义树里，TalkBack 可聚焦并点中一个看不见的「删除」 | `MainScreen.kt:1067-1121`、`:1084-1087` | 改 `combinedClickable(onLongClick=…)` + 可见溢出菜单；遮挡时 `clearAndSetSemantics {}` |
| P3-2 | **复选框/开关无标签**：账号行的选择 Switch 无内容描述（TalkBack 只念「开关，关闭」）；课程头是 `IconButton` 套 `Checkbox` 双点击区 | `MainScreen.kt:1153`、`:824-830`、`:1431-1447` | 用 `Modifier.toggleable(role = Role.Switch)` 并绑定标签 |
| P3-3 | **触摸目标小于 48dp**：课程头 36dp / 复选框 20dp / 顶栏 44dp | `MainScreen.kt:824`、`:828`、`:922` | 提到 ≥48dp |
| P3-4 | **大字号下文字被裁**：Dock 按钮固定 `height(54.dp)` + `maxLines=1` 且无 `overflow` | `MainScreen.kt:1357-1374` | 用 `heightIn(min=)` + `TextOverflow.Ellipsis` |
| P3-5 | **国际化缺失**：`strings.xml` 只含关于页 31 行；`MainScreen.kt` 内约 **54 处硬编码中文**、无 `stringResource`；只有 `values/`，无 `values-en`/`values-night`；复数未走 `<plurals>`；`Locale.getDefault()` 影响大小写 | `MainScreen.kt` 多处；`AboutActivity.kt:49` 是唯一用资源的地方；`AndroidManifest.xml:37` | 抽字符串 + `<plurals>` + `Locale.ROOT`；Manifest label 改资源引用 |
| P3-6 | **冷启动深色模式闪白**：`themes.xml:6-7` 硬编码 `windowLightStatusBar=true` 且无 `values-night`，靠已被上游弃用的 Accompanist 运行时补救 | `themes.xml:3-7`、`Theme.kt:55-65` | 迁移到 `Theme.Material3.DayNight.NoActionBar` + `enableEdgeToEdge()` |
| P3-7 | **批量签到缺少基本控制**：无超时、无取消/返回拦截、无并发上限、无「重试全部失败」、无网络检查、结果不持久化（旋转即丢整批进度并重建 N 个 WebView） | `BatchSignScreen.kt` 全局；`states` 为 `remember` | 随 P0-A1 一并解决；至少加 `BackHandler`、限流、结果落盘 |

---

### P4｜工程化与仓库卫生

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| P4-0 | **构建阻塞已修复（本次）**：`.gitignore:38` 的 `*.jar` 把 `gradle-wrapper.jar` 一并忽略，导致新克隆/CI 无法构建；JDK 25 下 Kotlin 回退 JVM_22 与 Java 21 冲突导致编译失败 | `git check-ignore` 确认；修复前报 `Inconsistent JVM-target compatibility` | 见第五节（已改） |
| P4-1 | **70+ 处 `println` 充当日志**，含完整 Cookie（兼 P0-C） | 全仓库 grep | 换 `Log.d` 并仅 `BuildConfig.DEBUG` 输出 |
| P4-2 | **3 个声明但完全未使用的依赖**：`coil-compose`、`androidx-webkit`、`lifecycle-runtime-compose`（另有 `ui-test-junit4`/`ui-tooling` 因无测试无预览而空转） | `build.gradle.kts:76/90/96/101/108`；全仓库无 `androidx.webkit.*`、无 `AsyncImage`、无 `collectAsState` | 直接删除（将来引入 ViewModel 时再加回 lifecycle-runtime-compose） |
| P4-3 | **约 28KB 死代码**：`ScannerScreen.kt`、`SimpleScannerScreen.kt` 及其 import、未使用的路由 2 条、`processCookieQueue`、`extractCodeFromUrl`/`exchangeCodeForToken`、`ScanResult`、`ErrorStatePanel`、`isTokenValid`、`getActiveCourseId`/`saveActiveCourseId`、`initialUrl`、`onTestSession`（全仓库唯一 TODO）、空的 `colors.xml` | 各文件 | 一次性清掉，文件可审计性显著提升 |
| P4-4 | **`ExampleInstrumentedTest.kt:22` 断言了错误的包名**（`com.mln.canvasbatchsign` vs 实际 `com.mln.tongji_canvas`）⇒ 仪器测试必然失败 | `build.gradle.kts:14` | 改正或删除模板测试 |
| P4-5 | **单元测试仅 `assertEquals(4, 2+2)` 模板**；无 UI 测试；无 CI | `ExampleUnitTest.kt` | 优先为「凭证编解码 + 仓库 upsert + 选择协调」这三块纯逻辑补测试 |
| P4-6 | **Gradle 配置偏弱**：注释掉的 `parallel`、无 `caching`、无 `configuration-cache`、无 `jvmToolchain`（正是 P4-0 的根因）；`org.gradle.jvmargs` 仅 2GB | `gradle.properties` | 本次已开 `daemon`+`caching`；建议后续加 `org.gradle.configuration-cache=true` 与 `jvmToolchain(21)` |
| P4-7 | **ABI 分包策略矛盾**：注释写「不生成通用 APK」但 `isUniversalApk = true`；且 `x86` 只服务模拟器却一起发布 | `build.gradle.kts:24-31` | 明确取舍：发布版去掉 `x86` 或去掉 universal |
| P4-8 | **文档与代码漂移**：`README.md:125-135` 的目录树缺 `AboutActivity`、`components/`、`qr/`、`ScannerScreen` 等；且未提及构建失败与凭证明文存储的安全提示 | `README.md:125-135` | 更新目录树，补「安全说明」章节 |
| P4-9 | 名称混用：Gradle 项目名 `canvas_batch_sign`、包名 `com.mln.tongji_canvas`、主题 `Canvas_batch_signTheme` 三套命名并存 | `settings.gradle.kts:22`、`build.gradle.kts:10` | 统一为 `TongJiCanvas` 系列 |
| P4-10 | 版本号策略：`versionCode = 20260520` 是日期式，但 `versionName = 3.2.1` 是语义式，两者需人工同步 | `build.gradle.kts:17-18` | 二选一，或用 CI 生成 |

---

## 三、建议的推进顺序

| 阶段 | 内容 | 预估 | 收益 |
|---|---|---|---|
| **第 1 步** | P0-A1 抽出 `SignInEngine`（OkHttp 逐用户 Cookie 头 + 限流 + 超时 + 重试 + 结构化结果），`BatchSignScreen` 退化为纯结果 UI | 2-3 天 | **让批量签到真正可用**；同时解决 P1-1/1-2/1-3/3-7 |
| **第 2 步** | P0-B 稳定身份标识 + 迁移既有数据 | 1 天 | 消除重复账号与「导入永远更新不了」 |
| **第 3 步** | P0-C 凭证加固（加密存储 + 关备份 + 去日志 + FLAG_SECURE + 去明文/混合内容） | 1 天（可拆 3 提交） | 隐私安全达标，可放心分发 |
| **第 4 步** | P1 中的低成本确定性缺陷：P1-12/13/14/16/17（三态、派生全选、rememberSaveable、floorMod） | 半天 | 消除可见错乱与崩溃边界 |
| **第 5 步** | P2-1/2/3 抽 `MainViewModel` + 拆文件 + 补纯逻辑测试 | 2-3 天 | 1635 行降到 6 个百行文件，业务可测 |
| **第 6 步** | P4 清理（死代码、无用依赖、wrong package 测试、CI） | 半天 | 仓库可维护、可自动验证 |
| **第 7 步** | P3 无障碍 + 国际化 + 主题 token | 1-2 天 | 可用性覆盖更广人群 |

---

## 四、验证状态说明（哪些结论我实测过）

**已实测**
- 【已验证】`.gitignore` 的 `*.jar` 确实吞掉了 `gradle-wrapper.jar`（`git check-ignore -v`）；
- 【已验证】修复前 `:app:assembleDebug` 失败于 `Inconsistent JVM-target compatibility detected for tasks 'compileDebugJavaWithJavac' (21) and 'compileDebugKotlin' (22)`；
- 【已验证】修复后 `BUILD SUCCESSFUL in 39s`，产出 4 个 APK（universal 86MB debug）；
- 【已验证】`:app:testDebugUnitTest` 可运行（内容仅 `assertEquals(4, 2+2)`）。

**未做（本环境限制，建议后续在真机/CI 补）**
- 未在真机或模拟器运行 App，因此**签到串号、假成功、WebView 泄漏均为源码级判定**——机制上可确证，但缺少一次实测复现记录；
- 未执行 `connectedAndroidTest`（需设备/模拟器）；
- 未做 Release 构建与 R8 规则验证（`isMinifyEnabled = true` 已开启，建议在真机回归前跑一次 `assembleRelease` 并检查 WebView/反射相关 keep 规则）。

**修正的误判（供参考，避免误导）**
- 我最初以为本机无法联网，实际是 PowerShell 的 `Invoke-WebRequest` 走了受限路径；`curl.exe` 正常，故构建可完整下载依赖 —— 结论已更正。

---

## 五、本次已落地的修改（可直接回退）

| 文件 | 改动 | 原因 |
|---|---|---|
| `gradle/wrapper/gradle-wrapper.jar` | 恢复（新增，43,764 B） | 原缺失，导致 `gradlew` 直接报 `ClassNotFoundException: GradleWrapperMain` |
| `.gitignore` | 在 `*.jar` 后加 `!gradle/wrapper/gradle-wrapper.jar` | 让 wrapper jar 能被 `git add`，修复新克隆/CI 无法构建 |
| `app/build.gradle.kts` | 新增 `kotlin { compilerOptions { jvmTarget = JVM_21 } }` | 与 `compileOptions` 的 Java 21 对齐，修复 JDK 25 下的 JVM-target 冲突 |
| `gradle.properties` | 开启 `org.gradle.daemon`、`org.gradle.caching` | 构建从 7m44s（含下载）降到 39s（增量） |

> 注：`local.properties`（指向本机 Android SDK）已被 `.gitignore` 忽略，未纳入改动。

---

## 六、一句话总结

这个项目**产品思路和对 Canvas 协议的理解是对的，UI 也做得相当完整**；但它的核心机制「N 个 WebView 并发重放各自 Cookie」在 Android 平台上**从根上不成立**——因为 `CookieManager` 是进程单例、不按 WebView 隔离，请求可能带着别人的会话发出，结果归属就不可信。把签到改成**每用户一个 OkHttp 请求、Cookie 只放请求头**，是投入产出比最高的一步；其次是给账号一个稳定身份标识，再把明文存 Cookie + 默认云备份这个隐私缺口堵上。

---

## 七、更正与澄清（v2，据用户实际反馈修订）

### 7.1 更正：我此前把「假成功」说得过重

**此前表述**：「App 报 N/N 全成功，实际只有 1 人签到」——这是**过度概括**，已按上文修正。

**准确表述**：Cookie 污染是**间歇性**的，具体某个请求读到谁的会话取决于时序竞态，因此三种结果都会出现：

| 该请求实际携带的会话 | 页面文本 | App 判定 | 真实性 |
|---|---|---|---|
| 本人的会话，首次签到 | 签到成功 | success | ✅ 真成功 |
| 本人的会话，已签过 | 已签过 | success | ✅ 真成功（无需重复签） |
| **别人的会话且那人已签** | 已签过 | success | ❌ **假成功** |
| 会话被清掉 / 二维码过期 | 报错或过期提示 | fail | ✅ 真失败 |
| 会话被别人覆盖 | 未登录/过期 | fail | ❌ **假失败**（本人其实没签上，但也没被误报成功） |

**关键推论（这解释了用户的实际反馈）**：正因为结果是**混合**的，才说明 Cookie 确实在互相污染。如果每个用户的 Cookie 真正隔离，结果应当**趋于一致**（要么整批成功，要么整批因二维码过期而失败），而不会出现「有人已签过、有人过期」这种参差。混合结果本身就是竞态的特征，而不是反证。

**一个副作用值得注意**：因为 `:481` 把「已签过」也算成功，**真正签上的次数是没有被单独统计的**。用户看到的 success 数 = 真成功 + 假成功，所以目前的成功计数**优于实际**。

### 7.2 澄清：「随云备份外泄」的含义

**不是** App 主动上传 sessionID。App 代码里**没有任何服务端上报/埋点逻辑**，它是个纯客户端（只有 Canvas/IAM 请求 + 拉取一个 GitHub 上的说明文件）。

这里说的是 **Android 系统自带的 Auto Backup（自动备份）**：

- 应用在 `AndroidManifest.xml:13` 声明了 `allowBackup="true"`；
- 同时 `backup_rules.xml` 与 `data_extraction_rules.xml` **没有任何 `<exclude>` 规则**；
- 依据 Android 的默认规则，App 的私有数据（含 `SharedPreferences`）**会被纳入系统备份**；
- 而 `SharedPreferences("sessions")` 里存的正是每个学生的 Canvas 会话 Cookie 串（`SessionRepository.kt:85`）。

**【本次实测核验】** 从已构建的 APK 产物中直接确认：

```
allowBackup = true            ✓
fullBackupContent     -> res/xml/backup_rules.xml        内容: <full-backup-content/>        （空）
dataExtractionRules   -> res/xml/data_extraction_rules.xml 内容: <data-extraction-rules>
                                                                    <cloud-backup/>          （空）
```

即：**规则文件确实是空的，没有任何排除项**。

后果的准确边界：

| 会发生 | 不会发生 |
|---|---|
| 会话 Cookie 进入**用户自己账号**的云端备份（在国行等设备上通常是厂商云服务，而非 Google Drive——备份提供方由设备决定）| App 自己把 Cookie 传到某个服务器 |
| 换新机恢复时，这些 Cookie 被一并还原到新设备上 | 泄露给同一设备上的其他 App（除非该 App 有 root/备份权限）|
| 一旦该用户的云账号被他人掌控，凭据即随之暴露 | 在用户不知情时被作者的服务器收集 |

**因此它是一条真实的、但是「被动」的暴露面**，严重性不如另外两条主动泄漏路径，建议的修复优先级排序是：

1. `println` 把完整 Cookie 打进 logcat（`BatchSignScreen.kt:554-555`、`OAuth2LoginScreen.kt:573-587`）——任何能读日志的 App / 用户提交的错误报告都会带上；
2. 持有手机的人可展开「查看调试」直接看到他人已登录会话，且无 `FLAG_SECURE`；
3. 剪贴板导出明文 Cookie（`MainScreen.kt:160-182`）——输入法类应用可读；
4. 云备份（本节）——被动，但一旦云账号失守，影响范围是全部历史账号。

修复成本极低：`allowBackup="false"`，或在两个规则文件里各加一行 `<exclude domain="sharedpref" path="sessions.xml"/>`（若是 12+ 设备，`data_extraction_rules.xml` 还需补 `<device-transfer>` 段，否则「换机迁移」仍会带上凭据）。
