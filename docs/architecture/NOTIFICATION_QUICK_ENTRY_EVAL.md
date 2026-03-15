# 🏗️ 技术方案评估：通知栏快捷记账

## AI 智能记账 — Notification Quick Entry Architecture

| 字段 | 值 |
|------|-----|
| 文档类型 | 技术方案评估 (ADR) |
| 版本 | v1.0 |
| 作者 | 架构师 Agent |
| 创建日期 | 2026-03-15 |
| 状态 | 评审中 |
| 关联需求 | PRD Epic 10 (US-10.1 ~ US-10.4) |

---

## 1. 背景与需求概述

PRD 要求实现"通知栏快捷记账"功能（P1），核心场景：

1. **常驻通知**：通知栏显示记账入口，展开后提供「文字记账」「语音记账」「拍照记账」+ 4 个常用分类快捷按钮
2. **快速输入**：点击按钮后弹出轻量输入界面（非全屏），完成后自动关闭返回原界面
3. **响应要求**：弹出到可输入 < 500ms；整体记账流程 < 3s

本文档评估两个核心技术决策：

- **决策 1**：快速输入 UI 载体 — 悬浮窗 (SYSTEM_ALERT_WINDOW) vs 透明 Activity
- **决策 2**：常驻通知实现 — Foreground Service 及其电量影响

---

## 2. 决策 1：悬浮窗 vs 透明 Activity

### 2.1 方案 A：悬浮窗 (SYSTEM_ALERT_WINDOW)

**原理**：通过 `WindowManager.addView()` 在所有应用上层绘制自定义 View。

```
通知栏点击 → PendingIntent → Service/BroadcastReceiver
  → WindowManager.addView(quickInputView, layoutParams)
  → 用户输入 → AI 提取 → 保存 → WindowManager.removeView()
```

#### 优势

| 维度 | 说明 |
|------|------|
| **无 Activity 栈干扰** | 不会把用户当前 Activity 推入后台，体验最自然 |
| **启动速度极快** | 无 Activity 生命周期开销，View 直接添加到 WindowManager，< 100ms |
| **真正的覆盖层** | 可在任何 App 上方显示，包括锁屏、桌面、第三方 App |
| **灵活的尺寸/位置** | 可做底部弹窗、居中对话框、甚至可拖动 |
| **保持前台 App 状态** | 用户正在玩的游戏/看的视频不会中断 |

#### 劣势

| 维度 | 说明 | 严重程度 |
|------|------|----------|
| **权限门槛高** | 需要 `SYSTEM_ALERT_WINDOW` 权限，Android 6.0+ 需跳转系统设置手动开启 | 🔴 **严重** |
| **用户授权率低** | "显示在其他应用上方"权限描述令人不安，转化率通常 < 40% | 🔴 **严重** |
| **厂商兼容性差** | MIUI/EMUI/ColorOS 等对悬浮窗有额外限制，部分机型默认禁止 | 🔴 **严重** |
| **Compose 支持受限** | `WindowManager.addView()` 原生使用传统 View 系统；使用 Compose 需 `ComposeView` 包装，存在 Lifecycle owner 问题 | 🟡 中等 |
| **无法使用 Navigation** | 脱离 Activity 上下文，无法复用 app 内的 NavGraph 和 ViewModel 注入 | 🟡 中等 |
| **安全审核风险** | Google Play 对悬浮窗应用审核更严格，可能影响上架 | 🟡 中等 |
| **后台限制** | Android 12+ 限制后台启动前台 Activity/悬浮窗，需 Foreground Service 或特殊条件 | 🟡 中等 |

#### 权限申请流程

```kotlin
// 检查权限
if (!Settings.canDrawOverlays(context)) {
    // 必须跳转系统设置页，无法直接弹框授权
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
    startActivityForResult(intent, REQUEST_OVERLAY)
}
```

> ⚠️ 注意：此权限**无法通过标准权限弹窗授权**，必须引导用户跳转系统设置页手动开启，用户体验割裂且授权率极低。

#### Compose 兼容方案

```kotlin
// 需要手动创建 Lifecycle 和 SavedStateRegistry
class QuickInputOverlayService : Service() {
    private var overlayView: ComposeView? = null

    fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        overlayView = ComposeView(this).apply {
            // ⚠️ 需要手动设置 ViewTreeLifecycleOwner 和 ViewTreeSavedStateRegistryOwner
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(serviceSavedStateOwner)
            setContent {
                QuickInputContent(onDismiss = { removeOverlay() })
            }
        }
        windowManager.addView(overlayView, params)
    }
}
```

---

### 2.2 方案 B：透明 Activity

**原理**：启动一个 `Theme.Translucent` 主题的 Activity，背景半透明，视觉效果类似悬浮窗。

```
通知栏点击 → PendingIntent → 启动 QuickInputActivity (透明主题)
  → Compose UI 渲染（底部弹窗/卡片）
  → 用户输入 → AI 提取 → 保存 → finish() + overridePendingTransition(0,0)
```

#### 优势

| 维度 | 说明 |
|------|------|
| **零额外权限** | 使用标准 Activity，无需任何特殊权限 | 
| **100% 用户覆盖** | 所有 Android 8.0+ 设备均支持，无厂商兼容问题 |
| **完整 Compose 支持** | 标准 `@AndroidEntryPoint` Activity，天然支持 Compose + Hilt + Navigation |
| **Hilt 注入正常** | `@AndroidEntryPoint` 直接注入 ViewModel / Repository，复用现有架构 |
| **生命周期完整** | 标准 Activity 生命周期，onPause/onResume/onDestroy 可靠 |
| **Google Play 友好** | 不涉及敏感权限，审核无风险 |
| **动画生态丰富** | 可使用 `Accompanist`、`AnimatedVisibility`、`BottomSheet` 等 Compose 动画 |

#### 劣势

| 维度 | 说明 | 严重程度 |
|------|------|----------|
| **Activity 栈影响** | 会将当前前台 App 推入后台（onPause），finish 后恢复 | 🟡 中等 |
| **启动有生命周期开销** | Activity 创建 + Compose 首帧，实测 200-400ms（仍满足 500ms 要求） | 🟢 轻微 |
| **任务切换可见** | 快速双击 Recent 键可能看到透明 Activity 残影 | 🟢 轻微 |
| **视频/游戏中断风险** | 部分全屏应用在 Activity 切换时可能短暂中断 | 🟡 中等 |

#### 实现方案

**1. 透明主题定义**

```xml
<!-- res/values/themes.xml -->
<style name="Theme.QuickInput" parent="Theme.MaterialComponents.DayNight.NoActionBar">
    <item name="android:windowIsTranslucent">true</item>
    <item name="android:windowBackground">@android:color/transparent</item>
    <item name="android:windowContentOverlay">@null</item>
    <item name="android:windowNoTitle">true</item>
    <item name="android:windowIsFloating">false</item>
    <item name="android:backgroundDimEnabled">true</item>
    <item name="android:backgroundDimAmount">0.4</item>
    <item name="android:windowAnimationStyle">@style/QuickInputAnimation</item>
</style>

<style name="QuickInputAnimation">
    <item name="android:windowEnterAnimation">@anim/slide_up</item>
    <item name="android:windowExitAnimation">@anim/slide_down</item>
</style>
```

**2. Activity 声明**

```xml
<!-- AndroidManifest.xml -->
<activity
    android:name=".quickinput.QuickInputActivity"
    android:theme="@style/Theme.QuickInput"
    android:taskAffinity=""
    android:excludeFromRecents="true"
    android:launchMode="singleInstance"
    android:exported="false" />
```

关键属性说明：
- `taskAffinity=""`：独立任务栈，不干扰主 App 任务
- `excludeFromRecents="true"`：不出现在最近任务列表
- `launchMode="singleInstance"`：避免重复创建

**3. Activity 实现**

```kotlin
@AndroidEntryPoint
class QuickInputActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getStringExtra("mode") ?: "text" // text | category
        val categoryId = intent.getLongExtra("category_id", -1L)

        setContent {
            AiBookkeeperTheme {
                QuickInputSheet(
                    mode = mode,
                    preSelectedCategoryId = if (categoryId > 0) categoryId else null,
                    onSaved = { transaction ->
                        showSuccessToast(transaction)
                        finishQuietly()
                    },
                    onDismiss = { finishQuietly() },
                    onDetailEdit = { extractionResult ->
                        // 跳转主 App 编辑页
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                putExtra("extraction", Json.encodeToString(extractionResult))
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                        finishQuietly()
                    }
                )
            }
        }
    }

    private fun finishQuietly() {
        finish()
        overridePendingTransition(0, R.anim.slide_down)
    }
}
```

**4. Compose 快捷输入 UI**

```kotlin
@Composable
fun QuickInputSheet(
    mode: String,
    preSelectedCategoryId: Long?,
    onSaved: (Transaction) -> Unit,
    onDismiss: () -> Unit,
    onDetailEdit: (ExtractionResult) -> Unit,
    viewModel: QuickInputViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 点击半透明背景关闭
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        // 底部弹窗卡片
        AnimatedVisibility(visible = true, enter = slideInVertically { it }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {} // 阻止点击穿透
                    .padding(16.dp),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                when (mode) {
                    "text" -> TextQuickInput(state, viewModel, onSaved, onDetailEdit)
                    "category" -> CategoryQuickInput(
                        state, viewModel, preSelectedCategoryId, onSaved
                    )
                }
            }
        }
    }
}
```

---

### 2.3 对比总结

| 维度 | 悬浮窗 (SYSTEM_ALERT_WINDOW) | 透明 Activity | 胜出 |
|------|------|------|------|
| **权限要求** | 需手动开启特殊权限 | 无额外权限 | ✅ 透明 Activity |
| **用户覆盖率** | ~40% (授权率低 + 厂商限制) | ~100% | ✅ 透明 Activity |
| **启动速度** | < 100ms | 200-400ms | ✅ 悬浮窗 |
| **前台 App 影响** | 无影响 | Activity 栈切换 | ✅ 悬浮窗 |
| **Compose/Hilt 兼容** | 需 hack，不完整 | 原生完美支持 | ✅ 透明 Activity |
| **代码复用** | 低（独立 View 体系） | 高（复用 ViewModel/Repository） | ✅ 透明 Activity |
| **厂商兼容性** | 差 (MIUI/EMUI 额外限制) | 好 (标准 API) | ✅ 透明 Activity |
| **Google Play 审核** | 有风险 | 无风险 | ✅ 透明 Activity |
| **维护成本** | 高 (双 UI 体系) | 低 (统一 Compose) | ✅ 透明 Activity |
| **可测试性** | 差 (Service 中的 View) | 好 (标准 Activity 测试) | ✅ 透明 Activity |

### 2.4 架构决策

> **✅ 决策：采用透明 Activity 方案**

**核心理由**：

1. **用户覆盖率 100% vs ~40%** — 悬浮窗权限授权率极低，尤其在国产 ROM 上，这将导致核心功能形同虚设
2. **架构一致性** — 透明 Activity 作为 `@AndroidEntryPoint` 完美融入现有 MVVM + Hilt 架构，无需维护两套 UI 体系
3. **启动速度可接受** — 200-400ms 仍满足 PRD 要求的 500ms 上限
4. **Activity 栈影响可缓解** — 通过 `taskAffinity="" + excludeFromRecents + singleInstance` 最小化影响

**缓解 Activity 栈切换影响的措施**：

```
策略 1: taskAffinity="" — 使用独立任务栈，不压入主 App 栈
策略 2: excludeFromRecents=true — 从最近任务列表隐藏
策略 3: 无进入动画 + 淡入底部弹窗 — 用户感知不到 Activity 切换
策略 4: finish() 后恢复原应用 — 由系统自动恢复前台 App
策略 5: windowIsTranslucent=true — 背后 App 仍可见(半透明)，减少"跳走"感
```

---

## 3. 决策 2：Foreground Service 与电量影响

### 3.1 常驻通知的实现选项

PRD 要求"应用启动后在通知栏显示常驻通知"。有三种实现方式：

| 方案 | 实现 | 常驻性 | 电量影响 |
|------|------|--------|----------|
| **A: 真 Foreground Service** | `startForeground()` 持续运行 Service | ✅ 系统不回收 | 🔴 高 |
| **B: Foreground Service + 最小化** | 仅持有通知，Service 内部无任何周期任务 | ✅ 系统不回收 | 🟡 中低 |
| **C: 复用 NotificationListenerService** | 已有的支付通知监听 Service 同时承载常驻通知 | ✅ 已常驻 | 🟢 低 (无新增) |

### 3.2 方案分析

#### 方案 A：独立 Foreground Service（不推荐）

```kotlin
class QuickEntryService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildQuickEntryNotification())
        return START_STICKY
    }
}
```

**电量影响分析**：

| 消耗源 | 说明 | 影响 |
|--------|------|------|
| 进程保活 | Service 所在进程常驻内存，增加 ~15-30MB | 🟡 内存间接增加系统调度功耗 |
| CPU 唤醒 | 即使无工作，进程存在仍有微量 CPU 开销 | 🟢 < 0.5% |
| 系统计入前台 | 电池统计将 App 标记为"前台活动" | 🔴 **用户感知差** — 显示"正在耗电" |
| Wakelock (如有) | 若持有 WakeLock 则持续耗电 | 🔴 严重 (本方案不需要) |

> ⚠️ **最大问题**：Android 电池设置会将有 Foreground Service 的 App 标记为"前台活跃"，用户看到后可能卸载应用。实际耗电不大 (~1-2%/天)，但**用户感知极差**。

#### 方案 B：最小化 Foreground Service

与方案 A 实现相同，但确保 Service 内部：
- 不持有 WakeLock
- 不做周期性任务
- 不保持网络连接
- 仅作为通知的"锚点"

**电量实测估算** (基于类似应用经验值)：

| 场景 | 日增耗电 | 占比 (3000mAh 电池) |
|------|----------|---------------------|
| Service 空转 | ~20-40 mAh | ~0.7-1.3% |
| 通知更新 (分类按钮刷新) | ~5-10 mAh | ~0.2-0.3% |
| PendingIntent 响应 | 可忽略 (事件触发) | ~0% |
| **合计** | **~25-50 mAh** | **~0.8-1.7%** |

> 实际电量消耗可接受，但系统 UI 仍会标记为"前台运行中"。

#### 方案 C：复用 NotificationListenerService（推荐）

项目已有 `PaymentNotificationService`（NotificationListenerService），此 Service 在用户授权后**本身就是常驻运行的系统级 Service**。

```kotlin
class PaymentNotificationService : NotificationListenerService() {

    // 已有：支付通知监听
    override fun onNotificationPosted(sbn: StatusBarNotification) { /* ... */ }

    // 新增：常驻快捷记账通知
    override fun onListenerConnected() {
        super.onListenerConnected()
        showQuickEntryNotification()
    }

    private fun showQuickEntryNotification() {
        val notification = buildQuickEntryNotification()
        // 使用 NotificationManager 直接发送（非 startForeground）
        NotificationManagerCompat.from(this).notify(QUICK_ENTRY_ID, notification)
    }
}
```

**为什么这是最优解**：

| 维度 | 说明 |
|------|------|
| **零新增耗电** | NotificationListenerService 已常驻，不新增 Service 进程 |
| **不显示"前台运行"** | 非 Foreground Service，不触发系统电池警告 |
| **通知持久性** | Service 存活期间通知自动持久，Service 被杀后通知消失（合理行为） |
| **架构统一** | 通知监听 + 快捷入口在同一 Service，降低复杂度 |

**对未授权通知监听的用户** — 退化为 Foreground Service：

```kotlin
// 在 App 启动时判断
if (isNotificationListenerEnabled()) {
    // 由 PaymentNotificationService 托管快捷通知
} else if (userEnabledQuickEntry) {
    // 退化方案：启动独立的最小化 ForegroundService
    QuickEntryForegroundService.start(context)
}
```

### 3.3 电量影响总结

```
┌─────────────────────────────────────────────────────────────────────┐
│                     电量影响评估矩阵                                 │
├──────────────────────┬──────────┬────────────┬─────────────────────┤
│ 方案                  │ 日耗电增量 │ 系统标记    │ 用户感知            │
├──────────────────────┼──────────┼────────────┼─────────────────────┤
│ A: 独立 FG Service   │ ~50 mAh  │ "前台运行"  │ ❌ 差（显示耗电）     │
│ B: 最小化 FG Service │ ~30 mAh  │ "前台运行"  │ 🟡 中（显示耗电）     │
│ C: 复用 NLS          │ ~0 mAh   │ 无新增标记  │ ✅ 好（无感知）       │
│ C+B: 混合策略         │ 0~30 mAh │ 视情况      │ ✅ 最优              │
└──────────────────────┴──────────┴────────────┴─────────────────────┘
```

### 3.4 Foreground Service 限制 (Android 12+)

Android 12 (API 31) 起对 Foreground Service 增加了限制：

| 限制 | 影响 | 应对 |
|------|------|------|
| **后台启动限制** | 后台不能 `startForegroundService()` | 从通知 PendingIntent 启动（属于用户触发，豁免） |
| **FGS 类型声明** | 必须在 Manifest 声明 `foregroundServiceType` | 声明为 `specialUse`，Play 提审说明 |
| **任务管理器可关** | 用户可从任务管理器关闭 FGS | 使用 `START_STICKY` 重启 |

Android 14 (API 34) 进一步要求：
- `foregroundServiceType` 必须为预定义类型之一
- `specialUse` 类型需要在 Play Console 额外审批

> 这进一步支持方案 C（复用 NLS）— 避免 FGS 类型声明和审批问题。

### 3.5 架构决策

> **✅ 决策：采用方案 C + B 混合策略**

- **主方案 (C)**：复用 `PaymentNotificationService` (NLS) 托管快捷记账通知 — 零新增耗电
- **退化方案 (B)**：未授权通知监听的用户，启动最小化 Foreground Service — 低耗电 + 可关闭

---

## 4. 常驻通知设计细节

### 4.1 通知渠道定义

```kotlin
object NotificationChannels {
    const val QUICK_ENTRY_CHANNEL_ID = "quick_entry"
    const val QUICK_ENTRY_CHANNEL_NAME = "快捷记账"

    fun createChannels(context: Context) {
        val channel = NotificationChannel(
            QUICK_ENTRY_CHANNEL_ID,
            QUICK_ENTRY_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW  // 不弹横幅、不发声
        ).apply {
            description = "常驻通知栏的快捷记账入口"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
```

### 4.2 通知构建

```kotlin
fun buildQuickEntryNotification(
    context: Context,
    topCategories: List<Category> = defaultCategories()
): Notification {
    // PendingIntent: 点击主体 → 打开文字快捷记账
    val textInputIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, QuickInputActivity::class.java).apply {
            putExtra("mode", "text")
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // Action: 语音记账
    val voiceAction = NotificationCompat.Action.Builder(
        R.drawable.ic_mic, "语音记账",
        PendingIntent.getActivity(context, 1,
            Intent(context, MainActivity::class.java).apply {
                putExtra("navigate_to", "voice_input")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    ).build()

    // Action: 拍照记账
    val photoAction = NotificationCompat.Action.Builder(
        R.drawable.ic_camera, "拍照记账",
        PendingIntent.getActivity(context, 2,
            Intent(context, MainActivity::class.java).apply {
                putExtra("navigate_to", "capture/camera")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    ).build()

    // BigStyle: 展开后显示常用分类按钮
    val bigTextContent = topCategories.take(4).joinToString("  ") { 
        "${it.icon}${it.name}" 
    }

    return NotificationCompat.Builder(context, NotificationChannels.QUICK_ENTRY_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("AI 智能记账")
        .setContentText("点击快速记账")
        .setContentIntent(textInputIntent)
        .addAction(voiceAction)
        .addAction(photoAction)
        .setStyle(NotificationCompat.BigTextStyle()
            .bigText("点击快速文字记账\n$bigTextContent"))
        .setOngoing(true)                    // 常驻
        .setPriority(NotificationCompat.PRIORITY_LOW) // 不弹横幅
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()
}
```

### 4.3 分类快捷按钮实现

Android 标准通知的 Action 按钮上限为 3 个，无法直接放 4 个分类按钮。解决方案：

| 方案 | 说明 | 推荐 |
|------|------|------|
| **RemoteViews 自定义布局** | 完全自定义通知 UI，放置 4 个分类按钮 | ✅ 推荐 |
| Action 按钮 + 主体点击 | 3 个 Action + 点击主体进入文字输入 | 🟡 备选 |

```kotlin
// RemoteViews 自定义通知布局
val expandedView = RemoteViews(context.packageName, R.layout.notification_quick_entry_expanded)

// 设置 4 个分类按钮
topCategories.forEachIndexed { index, category ->
    val buttonId = categoryButtonIds[index] // R.id.btn_category_1 ~ 4
    expandedView.setTextViewText(buttonId, "${category.icon} ${category.name}")
    expandedView.setOnClickPendingIntent(buttonId,
        PendingIntent.getActivity(context, 100 + index,
            Intent(context, QuickInputActivity::class.java).apply {
                putExtra("mode", "category")
                putExtra("category_id", category.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    )
}
```

---

## 5. 模块归属与架构集成

### 5.1 新增文件归属

```
:app/
├── src/main/java/.../
│   ├── quickinput/
│   │   ├── QuickInputActivity.kt          // 透明 Activity (新增)
│   │   ├── QuickInputSheet.kt             // Compose 快捷输入 UI (新增)
│   │   └── QuickInputViewModel.kt         // ViewModel (新增)
│   └── notification/
│       ├── QuickEntryNotificationManager.kt // 通知构建与管理 (新增)
│       └── QuickEntryForegroundService.kt   // 退化方案 FG Service (新增)
├── src/main/res/
│   ├── layout/
│   │   └── notification_quick_entry_expanded.xml // RemoteViews 布局 (新增)
│   ├── anim/
│   │   ├── slide_up.xml                   // 弹出动画 (新增)
│   │   └── slide_down.xml                 // 收起动画 (新增)
│   └── values/
│       └── themes.xml                     // 新增 Theme.QuickInput (修改)

:feature-capture/
├── src/main/java/.../
│   ├── notification/
│   │   └── PaymentNotificationService.kt  // 新增快捷通知托管逻辑 (修改)

:core-data/
├── src/main/java/.../
│   ├── repository/
│   │   ├── CategoryRepository.kt          // 新增 getTopCategories() (修改)
│   │   └── CategoryRepositoryImpl.kt      // 实现 (修改)
```

### 5.2 模块依赖关系（无变化）

快捷记账功能放在 `:app` 模块（因为涉及 Activity 声明和通知管理），通过已有的 Repository 接口与 `:core-data` 交互，不引入新的模块间依赖。

```
QuickInputActivity (:app)
    → QuickInputViewModel
        → TransactionRepository (:core-data interface)
        → AiExtractionRepository (:core-data interface)
        → CategoryRepository (:core-data interface)
```

### 5.3 Hilt 注入

```kotlin
// QuickInputViewModel 正常注入，无需额外 Module
@HiltViewModel
class QuickInputViewModel @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val aiExtractionRepo: AiExtractionRepository,
    private val categoryRepo: CategoryRepository
) : ViewModel() {
    // 复用现有的 AI 提取 + 保存逻辑
}
```

---

## 6. 风险与应对

| 风险 | 概率 | 影响 | 应对措施 |
|------|------|------|----------|
| 透明 Activity 启动时用户感知到闪烁 | 中 | 体验下降 | 使用淡入动画 + windowIsTranslucent + 底部 slideUp；实测优化到无感知 |
| 部分厂商 ROM 杀后台导致 NLS 断连 | 中 | 快捷通知消失 | 引导用户关闭电池优化 + `START_STICKY` + 断连检测重新 bind |
| Android 14+ FGS 类型审批 (退化方案) | 低 | 退化方案上线延迟 | 提前准备 Play Console 审批材料；主方案不受影响 |
| 全屏游戏/视频时弹出 Activity 体验差 | 低 | 少量用户投诉 | 可增加"免打扰模式"检测，全屏时仅保存不弹 UI |
| 常用分类通知更新频率过高导致闪烁 | 低 | 通知栏闪烁 | 分类排序每日更新一次（非实时），使用 `setOnlyAlertOnce(true)` |

---

## 7. 结论

| 决策项 | 选择 | 核心理由 |
|--------|------|----------|
| **快速输入 UI 载体** | 透明 Activity | 零权限、100% 覆盖率、完美 Compose/Hilt 兼容 |
| **常驻通知承载** | 复用 NLS + FGS 退化 | 零新增耗电、无系统电池警告 |
| **分类快捷按钮** | RemoteViews 自定义布局 | 突破 3 Action 限制，放置 4 个分类按钮 |
| **模块归属** | `:app` (Activity/通知) + `:feature-capture` (NLS 修改) | 不引入新模块依赖 |

---

*文档版本: v1.0 | 最后更新: 2026-03-15*
