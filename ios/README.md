# TongJi_Canvas for iOS

TongJi_Canvas 的原生 iOS 客户端，支持账号管理、课堂二维码扫描和批量签到。

## 使用指南

1. 点击右上角 **添加账号** 按钮，进入同济大学统一身份认证页面。登录成功后，系统会自动提取认证信息并保存至主页的 Canvas 用户列表。也可以新建空账号手动输入 Cookie 信息。
2. 支持 **课程分组**（新建/编辑/删除、按课程一键批量勾选），也支持在账号详情页调整所属课程。
3. 在主页批量勾选已保存的用户或课程分组，点击 **扫码签到** 进行批量签到。
4. 支持 **签到历史**（本机最多保留最近 50 条记录，可查看每条签到的状态/HTTP 状态码/耗时详情、复制结果或诊断信息、单条删除与清空）。
5. 点击右上角导入/导出菜单，支持 **一键导出/导入剪贴板**（兼容 Android 端 JSON 格式，含账号与课程分组）以及 **JSON 文件导入/导出**。
6. 支持在账号详情页一键复制网页版签到链接。

**支持系统：iOS 17.0 及以上版本。**
**ipa包未签名，请自行签名安装。**

<table>
  <tr>
    <td align="center"><img src="./iOS_Demo/Demo.png" width="30%" alt="新版3.0.0"></td>
  </tr>
</table>

### 目录结构

```text
ios/
├── TongJiCanvas.xcodeproj/
│   ├── project.pbxproj
│   └── xcshareddata/
│       └── xcschemes/                 # Xcode 共享构建方案
├── TongJiCanvas/
│   ├── TongJiCanvasApp.swift          # 应用入口
│   ├── ContentView.swift              # 主页、课程分组、导入导出与账号管理
│   ├── AccountStore.swift             # 账号、课程分组与 Keychain 凭据管理
│   ├── AccountTransfer.swift          # 剪贴板/JSON 批量导入导出编解码
│   ├── CourseEditorView.swift         # 课程分组新建与编辑界面
│   ├── CanvasSessionCookie.swift      # 自定义 Cookie 配置
│   ├── CanvasLoginView.swift          # 统一认证登录界面
│   ├── CanvasLoginController.swift    # 登录流程与会话获取
│   ├── CanvasScannerView.swift        # 扫码签到界面
│   ├── NativeQRScannerView.swift      # 相机二维码扫描
│   ├── CanvasSignInClient.swift       # Canvas 签到请求与结果解析
│   ├── SignHistoryStore.swift         # 签到历史持久化（最多 50 条）
│   ├── SignHistoryView.swift          # 签到历史与结果/诊断详情界面
│   ├── TongJiCanvas.entitlements      # 应用权限配置
│   └── Assets.xcassets/               # 图标与图片资源
└── Package.swift                      # Swift Package 配置
```