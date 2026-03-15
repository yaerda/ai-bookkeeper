# 🔬 调研报告：Android 13+ POST_NOTIFICATIONS 权限对常驻通知的影响

| 字段 | 值 |
|------|-----|
| 文档类型 | 技术调研报告 |
| 版本 | v1.0 |
| 作者 | 前端工程师 Agent |
| 创建日期 | 2026-03-15 |
| 关联文档 | [NOTIFICATION_QUICK_ENTRY_EVAL.md](./NOTIFICATION_QUICK_ENTRY_EVAL.md) |
| 关联需求 | PRD Epic 10 (US-10.1 常驻通知) |

---

## 1. 背景

本项目 (`targetSdk = 35`, `minSdk = 26`) 使用 `PaymentNotificationService`（Foreground Service，`foregroundServiceType="specialUse"`）在通知栏维持一条常驻通知，作为快捷记账入口。Android 13 (API 33) 引入的 `POST_NOTIFICATIONS` 运行时权限对此功能有直接影响，需要全面评估。

---

## 2. POST_NOTIFICATIONS 权限机制概述

### 2.1 基本规则

| 条件 | 行为 |
|------|------|
| **targetSdk ≥ 33 + Android 13+ 设备** | `POST_NOTIFICATIONS` 视为**运行时（dangerous）权限**，必须在代码中请求，用户授权后才能发送通知 |
| **targetSdk < 33 + Android 13+ 设备** | 系统在首次创建 NotificationChannel 时自动弹出权限弹窗（兼容过渡行为） |
| **Android 12 及以下设备** | 无此权限概念，通知默认可发送 |

### 2.2 权限声明

项目已在 `AndroidManifest.xml` 中声明：

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

✅ **声明正确**，但仅声明不够——targetSdk ≥ 33 时必须在运行时请求。

---

## 3. 对常驻通知（Foreground Service）的具体影响

### 3.1 核心结论：FGS 可以启动，但通知可能不可见

这是最关键的发现：

| 场景 | Foreground Service | 通知显示 | 用户体验 |
|------|-------------------|----------|----------|
| ✅ 权限已授予 | 正常启动并运行 | ✅ 通知栏可见，展开可交互 | 完整功能 |
| ❌ 权限被拒绝/未请求 | **仍可启动并运行** | ❌ 通知不可见（静默隐藏） | ⚠️ 严重降级 |
| ❌ 权限被拒绝/未请求 (Android 13) | 仍可启动 | 通知在任务管理器中可见，通知栏不显示 | ⚠️ 功能完全失效 |

> **关键**：`startForeground()` 不会因为缺少 `POST_NOTIFICATIONS` 权限而抛出异常或阻止 Service 启动。Service 可以正常运行，但用户**看不到通知**——这意味着快捷记账入口完全失效。

### 3.2 详细行为分析

#### 3.2.1 Service 启动流程不受影响

```
startForegroundService() → Service.onCreate() → startForeground(id, notification)
                                                  ↓
                                            即使无 POST_NOTIFICATIONS 权限
                                            也不会抛出异常 ✅
                                            Service 正常存活 ✅
                                            但通知对用户不可见 ❌
```

#### 3.2.2 通知可见性的具体表现

**权限被拒绝时**：
- 通知**不会**出现在通知栏（状态栏无图标、下拉无通知条目）
- 通知**不会**响铃或振动
- `NotificationManager.getActiveNotifications()` **仍然返回该通知**（系统内部存在，只是不展示）
- 在"设置 → 应用 → 正在运行的服务"中**可以看到** Service 在运行
- 在 Android 13 的任务管理器中，前台服务通知可能显示在一个专门的区域（但用户通常不关注）

#### 3.2.3 对 RemoteViews 交互按钮的影响

项目使用 `RemoteViews` 自定义通知布局（含文字、语音、拍照、4 个分类快捷按钮）。如果通知不可见：

| 组件 | 影响 |
|------|------|
| `btn_text_input` | ❌ 不可见、不可点击 |
| `btn_voice_input` | ❌ 不可见、不可点击 |
| `btn_camera_input` | ❌ 不可见、不可点击 |
| `btn_category_*` (4个) | ❌ 不可见、不可点击 |
| `setOngoing(true)` | 无效果——通知本身就不显示 |
| `PendingIntent` | 注册但永远不会触发 |

> 即：**权限未授予 = 通知栏快捷记账功能 100% 失效**。

### 3.3 对成功反馈通知的影响

`showSuccessFeedback()` 发送的临时通知（US-10.4 撤销按钮）同样受影响：

| 权限状态 | 反馈通知 | 撤销操作 |
|----------|----------|----------|
| ✅ 已授予 | 显示 3 秒后自动消失 | 用户可点击"撤销" |
| ❌ 未授予 | 不显示 | 用户无法撤销 |

---

## 4. 权限请求时机与策略

### 4.1 请求时机分析

| 时机 | 优势 | 劣势 | 推荐 |
|------|------|------|------|
| **App 首次启动** | 简单直接 | 用户不理解为什么需要；授权率低 (~40-50%) | ❌ |
| **用户首次记账后** | 有上下文（刚记完账） | 通知功能延迟可用 | 🟡 |
| **用户开启"通知栏快捷入口"设置时** | 上下文最佳；用户明确需要此功能 | 设置入口可能被忽略 | ✅ **推荐** |
| **首次启动 + 功能引导** | 结合引导说明，授权率可达 60-70% | 首次使用流程变长 | ✅ **推荐** |

### 4.2 推荐策略：分层请求

```
┌─────────────────────────────────────────────────────────────────┐
│                    POST_NOTIFICATIONS 权限请求策略                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. 首次启动引导页 (Onboarding)                                   │
│     └─ 展示功能说明卡片："开启通知，随时快速记账"                      │
│        └─ 用户点击"开启" → requestPermission()                   │
│        └─ 用户点击"稍后" → 跳过，后续在设置中引导                     │
│                                                                 │
│  2. 设置页 → "通知栏快捷入口" 开关                                  │
│     └─ 用户开启时：                                               │
│        ├─ 已授权 → 直接启动 Service                                │
│        ├─ 未请求 → requestPermission() → 授权后启动                 │
│        └─ 已拒绝 → 显示引导弹窗 → 跳转系统设置                       │
│                                                                 │
│  3. 主页 Banner 提示（权限被拒绝后）                                 │
│     └─ "通知栏记账已关闭，点击开启" → 跳转系统设置                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4.3 实现代码示例

```kotlin
// NotificationPermissionHelper.kt — 建议放在 :core-common 或 :app 模块

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

object NotificationPermissionHelper {

    /** 是否需要运行时请求 POST_NOTIFICATIONS */
    fun isRuntimePermissionRequired(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU // API 33

    /** 检查通知权限是否已授予 */
    fun isNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 12 及以下：通知默认可发送（用户可在设置中关闭频道，但权限层面默认开启）
            true
        }
    }

    /** 用户是否永久拒绝（需跳转系统设置） */
    fun shouldShowSettingsGuidance(
        activity: androidx.activity.ComponentActivity
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            !activity.shouldShowRequestPermissionRationale(
                Manifest.permission.POST_NOTIFICATIONS
            ) && !isNotificationPermissionGranted(activity)
        } else {
            false
        }
    }

    /** 跳转到应用通知设置页 */
    fun openNotificationSettings(context: Context) {
        val intent = Intent().apply {
            action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        context.startActivity(intent)
    }
}
```

```kotlin
// 在 Activity/Fragment 中使用 (Compose + Activity Result API)

@Composable
fun NotificationPermissionEffect(
    onPermissionResult: (granted: Boolean) -> Unit
) {
    val context = LocalContext.current

    if (!NotificationPermissionHelper.isRuntimePermissionRequired()) {
        LaunchedEffect(Unit) { onPermissionResult(true) }
        return
    }

    if (NotificationPermissionHelper.isNotificationPermissionGranted(context)) {
        LaunchedEffect(Unit) { onPermissionResult(true) }
        return
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onPermissionResult(granted)
    }

    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
```

---

## 5. 对项目现有代码的影响分析

### 5.1 PaymentNotificationService 需要的改动

当前 `PaymentNotificationService.start()` 直接调用 `startForegroundService()`，未检查通知权限：

```kotlin
// 当前代码 — 缺少权限检查
fun start(context: Context) {
    val intent = Intent(context, PaymentNotificationService::class.java)
    context.startForegroundService(intent)  // ⚠️ 无权限时 Service 启动但通知不可见
}
```

**建议改动**：在启动 Service 前检查权限，无权限时不启动（避免无意义的后台 Service）。

```kotlin
fun start(context: Context): Boolean {
    if (!NotificationPermissionHelper.isNotificationPermissionGranted(context)) {
        return false  // 调用方应引导用户授权
    }
    val intent = Intent(context, PaymentNotificationService::class.java)
    context.startForegroundService(intent)
    return true
}
```

### 5.2 AndroidManifest.xml 状态

| 项目 | 状态 | 说明 |
|------|------|------|
| `POST_NOTIFICATIONS` 声明 | ✅ 已有 | 第 11 行 |
| `FOREGROUND_SERVICE` 声明 | ✅ 已有 | 第 10 行 |
| `foregroundServiceType="specialUse"` | ✅ 已有 | Service 声明 |

### 5.3 通知渠道（Channel）行为

| Android 版本 | Channel 行为 |
|-------------|-------------|
| 8.0-12 (API 26-32) | 创建 Channel 后通知默认可展示；用户可在设置中关闭频道 |
| 13+ (API 33+) | 创建 Channel 不受权限影响，但**展示**通知需要 POST_NOTIFICATIONS |

> `IMPORTANCE_LOW`（当前设置）不影响权限需求——无论 importance 级别如何，Android 13+ 都需要权限。

---

## 6. 边界场景与风险

### 6.1 权限撤销后的 Service 行为

| 场景 | 行为 |
|------|------|
| Service 运行中，用户去设置关闭通知权限 | Service **继续运行**，但通知立即从通知栏消失 |
| 用户重新开启通知权限 | 需要**重新发送通知**（`notify()`），不会自动恢复 |
| App 被杀后重启，权限已被关闭 | `START_STICKY` 重启 Service → 通知不可见 → 无意义的后台占用 |

**建议**：注册 `BroadcastReceiver` 监听权限变化（API 33 无直接广播），或在 `onStartCommand` 中检查权限，无权限时 `stopSelf()`。

### 6.2 用户拒绝权限的应对矩阵

| 拒绝次数 | 系统行为 | App 应对 |
|---------|----------|----------|
| 首次拒绝 | 下次仍可弹出系统权限弹窗 | 在合适时机再次请求 + 说明理由 |
| 二次拒绝（"不再询问"） | 系统不再弹窗 | 显示引导 → 跳转系统通知设置 |
| 用户主动在设置中关闭 | 等同永久拒绝 | 同上 |

> ⚠️ Android 13 的 `POST_NOTIFICATIONS` 权限**仅有两次弹窗机会**（首次 + 再次请求），之后自动进入"不再询问"状态。这与 Android 11+ 的其他权限行为一致。

### 6.3 对 Android 14+ (API 34) 的额外考量

| 特性 | 影响 |
|------|------|
| **Foreground Service 类型限制** | `specialUse` 需在 Play Console 额外说明；POST_NOTIFICATIONS 权限需求不变 |
| **用户可关闭前台服务** | 用户可从任务管理器直接关闭 FGS（与通知权限独立） |
| **部分 OEM 的"自动拒绝"** | 部分国产 ROM 可能默认拒绝通知权限，需额外适配引导 |

### 6.4 对 Android 15 (API 35) 的考量

本项目 targetSdk = 35，需注意：

| 特性 | 影响 |
|------|------|
| **前台服务超时** | `dataSync` 类型有 6 小时上限；本项目使用 `specialUse` 暂不受限 |
| **权限行为不变** | POST_NOTIFICATIONS 规则与 Android 13 一致 |

---

## 7. 总结与行动项

### 7.1 核心结论

1. **POST_NOTIFICATIONS 权限未授予时，Foreground Service 可正常启动但通知完全不可见**——常驻快捷记账入口 100% 失效
2. 项目已正确声明权限，但**缺少运行时请求逻辑**
3. 权限请求时机和被拒后的引导策略是保证功能可用率的关键

### 7.2 行动项

| # | 行动项 | 优先级 | 负责模块 |
|---|--------|--------|----------|
| 1 | 实现 `NotificationPermissionHelper` 工具类 | P0 | `:core-common` |
| 2 | 在启动 `PaymentNotificationService` 前添加权限检查 | P0 | `:feature-capture` |
| 3 | 实现首次启动引导页的权限请求 UI | P1 | `:app` |
| 4 | 设置页添加"通知栏快捷入口"开关 + 权限状态检测 | P1 | `:feature-input` 或 `:app` |
| 5 | 权限被拒后的降级 UI（Banner 提示跳转设置） | P1 | `:app` |
| 6 | Service 的 `onStartCommand` 中添加权限检查（无权限时 stopSelf） | P2 | `:feature-capture` |
| 7 | 成功反馈通知添加权限检查（避免静默失败） | P2 | `:feature-capture` |

---

## 附录 A：各 Android 版本通知权限行为速查表

| Android 版本 | API | POST_NOTIFICATIONS | 通知默认行为 | FGS 通知 |
|-------------|-----|-------------------|-------------|---------|
| 8.0-8.1 | 26-27 | 不存在 | 默认允许 | 必须显示 |
| 9.0-12.0 | 28-32 | 不存在 | 默认允许 | 必须显示 |
| 13 | 33 | **运行时权限** | 需请求 | 可启动但通知可能不可见 |
| 14 | 34 | 运行时权限 | 需请求 | 同上 + FGS 类型限制 |
| 15 | 35 | 运行时权限 | 需请求 | 同上 + FGS 超时限制 |

## 附录 B：Google 官方文档参考

- [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fg-service-types)
- [Behavior changes: apps targeting Android 13](https://developer.android.com/about/versions/13/behavior-changes-13#notification-permission)
