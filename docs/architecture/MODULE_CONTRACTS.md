# 📜 模块间接口契约

## AI 智能记账 — Interface Contracts

| 字段 | 值 |
|------|-----|
| 文档类型 | 接口契约定义 |
| 版本 | v1.0 |

---

## 1. 总体原则

| 原则 | 说明 |
|------|------|
| **依赖倒置** | Feature 模块依赖 `:core-data` 暴露的接口（interface），不依赖实现类 |
| **单向依赖** | Feature → Core，Feature 之间不直接依赖 |
| **数据隔离** | Entity（数据库层）不暴露给 Feature，Feature 只使用 Domain Model |
| **Flow 优先** | 列表/实时数据通过 `Flow` 返回，一次性操作通过 `suspend` 函数 |
| **Result 封装** | 可能失败的操作返回 `Result<T>` 而非抛异常 |

---

## 2. Domain Models (共享数据模型)

所有模块共用的数据模型定义在 `:core-data` 的 `model/` 包下：

### 2.1 Transaction

```kotlin
data class Transaction(
    val id: Long = 0,
    val amount: Double,
    val type: TransactionType,
    val categoryId: Long?,
    val categoryName: String? = null,      // JOIN 查询时填充
    val categoryIcon: String? = null,
    val categoryColor: String? = null,
    val merchantName: String? = null,
    val note: String? = null,
    val originalInput: String? = null,
    val date: LocalDateTime,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val source: TransactionSource,
    val status: TransactionStatus,
    val syncStatus: SyncStatus,
    val aiConfidence: Float? = null
)

enum class TransactionType { INCOME, EXPENSE }

enum class TransactionSource {
    MANUAL,          // 手动表单
    TEXT_AI,         // 文字 AI 提取
    VOICE_AI,        // 语音 AI 提取
    PHOTO_AI,        // 拍照 AI 提取
    AUTO_CAPTURE     // 通知自动捕获
}

enum class TransactionStatus {
    CONFIRMED,       // 已确认
    PENDING          // 待确认 (自动捕获)
}

enum class SyncStatus {
    LOCAL,           // 仅本地
    PENDING_SYNC,    // 待同步
    SYNCED           // 已同步
}
```

### 2.2 Category

```kotlin
data class Category(
    val id: Long = 0,
    val name: String,
    val icon: String,
    val color: String,
    val type: TransactionType,
    val parentId: Long? = null,
    val isSystem: Boolean = true,
    val sortOrder: Int = 0,
    val children: List<Category> = emptyList()  // 二级分类
)
```

### 2.3 Budget

```kotlin
data class Budget(
    val id: Long = 0,
    val categoryId: Long?,
    val amount: Double,
    val month: YearMonth,
    val spent: Double = 0.0,                    // 运行时计算
    val progress: Float = 0f                     // spent / amount
)
```

### 2.4 ExtractionResult

```kotlin
data class ExtractionResult(
    val amount: Double?,
    val type: String,                            // "INCOME" | "EXPENSE"
    val category: String,                        // 分类名 (中文)
    val merchantName: String? = null,
    val date: LocalDate,
    val note: String? = null,
    val confidence: Float,
    val source: ExtractionSource
)

enum class ExtractionSource {
    AZURE_AI,        // Azure OpenAI 在线提取
    LOCAL_RULE       // 本地正则提取
}
```

### 2.5 MonthlyStatsSummary

```kotlin
data class MonthlyStatsSummary(
    val month: YearMonth,
    val totalIncome: Double,
    val totalExpense: Double,
    val balance: Double,                         // income - expense
    val categoryBreakdown: List<CategoryExpense>,
    val dailyExpenses: List<DailyExpense>
)

data class CategoryExpense(
    val categoryId: Long,
    val categoryName: String,
    val categoryColor: String,
    val amount: Double,
    val percentage: Float                        // 占总支出百分比
)

data class DailyExpense(
    val date: LocalDate,
    val amount: Double
)
```

---

## 3. Repository 接口

### 3.1 TransactionRepository

```kotlin
interface TransactionRepository {

    // === 创建 ===
    suspend fun create(transaction: Transaction): Result<Long>

    // === 读取 ===
    suspend fun getById(id: Long): Transaction?
    fun observeById(id: Long): Flow<Transaction?>

    fun observeByDateRange(
        start: LocalDateTime,
        end: LocalDateTime
    ): Flow<List<Transaction>>

    fun observeByMonth(yearMonth: YearMonth): Flow<List<Transaction>>

    fun observePendingTransactions(): Flow<List<Transaction>>

    fun observeByCategoryAndMonth(
        categoryId: Long,
        yearMonth: YearMonth
    ): Flow<List<Transaction>>

    // === 更新 ===
    suspend fun update(transaction: Transaction): Result<Unit>
    suspend fun confirmTransaction(id: Long): Result<Unit>
    suspend fun confirmAll(ids: List<Long>): Result<Unit>

    // === 删除 ===
    suspend fun delete(id: Long): Result<Unit>

    // === 搜索 ===
    suspend fun search(keyword: String): List<Transaction>

    // === 统计 ===
    fun observeMonthlyIncome(yearMonth: YearMonth): Flow<Double>
    fun observeMonthlyExpense(yearMonth: YearMonth): Flow<Double>
    fun observeExpenseBreakdown(yearMonth: YearMonth): Flow<List<CategoryExpense>>

    // === 同步 ===
    suspend fun getPendingSync(): List<Transaction>
    suspend fun markSynced(ids: List<Long>)
}
```

### 3.2 AiExtractionRepository

```kotlin
interface AiExtractionRepository {

    /**
     * 从文本中提取记账信息
     * 自动选择在线/离线策略
     */
    suspend fun extract(input: String): Result<ExtractionResult>

    /**
     * 强制使用在线 AI 提取（用于重新分析）
     */
    suspend fun extractOnline(input: String): Result<ExtractionResult>

    /**
     * 从 OCR 文本中提取记账信息
     * 使用 OCR 专用 prompt
     */
    suspend fun extractFromOcr(ocrText: String): Result<ExtractionResult>
}
```

### 3.3 CategoryRepository

```kotlin
interface CategoryRepository {

    fun observeAllCategories(): Flow<List<Category>>

    fun observeExpenseCategories(): Flow<List<Category>>

    fun observeIncomeCategories(): Flow<List<Category>>

    fun observeSubCategories(parentId: Long): Flow<List<Category>>

    suspend fun getById(id: Long): Category?

    /**
     * 根据名称和类型查找分类
     * AI 提取返回分类名时用于映射到 categoryId
     */
    suspend fun findByNameAndType(name: String, type: TransactionType): Category?

    suspend fun create(category: Category): Result<Long>

    suspend fun update(category: Category): Result<Unit>

    suspend fun delete(id: Long): Result<Unit>
}
```

### 3.4 BudgetRepository

```kotlin
interface BudgetRepository {

    fun observeMonthlyBudget(yearMonth: YearMonth): Flow<Budget?>

    fun observeCategoryBudgets(yearMonth: YearMonth): Flow<List<Budget>>

    suspend fun setMonthlyBudget(yearMonth: YearMonth, amount: Double): Result<Unit>

    suspend fun setCategoryBudget(
        yearMonth: YearMonth,
        categoryId: Long,
        amount: Double
    ): Result<Unit>

    suspend fun deleteBudget(id: Long): Result<Unit>

    /**
     * 检查预算是否超支，返回需要提醒的预算列表
     */
    suspend fun checkBudgetAlerts(yearMonth: YearMonth): List<BudgetAlert>
}

data class BudgetAlert(
    val budget: Budget,
    val level: AlertLevel    // WARNING_80 | EXCEEDED_100
)

enum class AlertLevel { WARNING_80, EXCEEDED_100 }
```

### 3.5 StatsRepository

```kotlin
interface StatsRepository {

    fun observeMonthlyStats(yearMonth: YearMonth): Flow<MonthlyStatsSummary>

    fun observeWeeklyExpenses(startOfWeek: LocalDate): Flow<List<DailyExpense>>

    /**
     * 刷新月度统计缓存
     * Transaction 变更时调用
     */
    suspend fun refreshMonthlyStats(yearMonth: YearMonth)
}
```

---

## 4. Feature 模块对外接口

### 4.1 导航路由定义

每个 Feature 模块暴露自己的导航路由常量和 `NavGraphBuilder` 扩展函数：

```kotlin
// :feature-input
object InputRoutes {
    const val HOME = "home"
    const val TEXT_INPUT = "text_input"
    const val VOICE_INPUT = "voice_input"
    const val MANUAL_FORM = "manual_form"
    const val CONFIRM = "confirm/{extractionJson}"
    const val DETAIL = "transaction/{transactionId}"
}

fun NavGraphBuilder.inputNavGraph(navController: NavController) {
    composable(InputRoutes.HOME) { HomeScreen(navController) }
    composable(InputRoutes.TEXT_INPUT) { TextInputScreen(navController) }
    // ...
}
```

```kotlin
// :feature-capture
object CaptureRoutes {
    const val CAMERA = "capture/camera"
    const val OCR_RESULT = "capture/ocr_result"
}

fun NavGraphBuilder.captureNavGraph(navController: NavController) {
    composable(CaptureRoutes.CAMERA) { CaptureScreen(navController) }
    // ...
}
```

```kotlin
// :feature-stats
object StatsRoutes {
    const val OVERVIEW = "stats"
    const val BUDGET = "stats/budget"
    const val SETTINGS = "settings"
    const val CATEGORY_DETAIL = "stats/category/{categoryId}/{yearMonth}"
}

fun NavGraphBuilder.statsNavGraph(navController: NavController) {
    composable(StatsRoutes.OVERVIEW) { StatsScreen(navController) }
    // ...
}
```

### 4.2 App 导航图集成

```kotlin
// :app - AppNavGraph.kt
@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = InputRoutes.HOME
    ) {
        inputNavGraph(navController)
        captureNavGraph(navController)
        statsNavGraph(navController)
    }
}
```

---

## 5. 跨模块通信规范

### 5.1 导航传参

| 方式 | 场景 | 示例 |
|------|------|------|
| **路由参数** | 简单标识符 | `transaction/{transactionId}` |
| **Navigation Arguments** | 复杂对象 | `confirm` 路由传 ExtractionResult JSON |
| **共享 ViewModel** | 同一 NavBackStackEntry | Activity 级 ViewModel (谨慎使用) |

```kotlin
// 传参示例：AI 提取结果 → 确认页面
val extractionJson = Json.encodeToString(extractionResult)
val encodedJson = URLEncoder.encode(extractionJson, "UTF-8")
navController.navigate("confirm/$encodedJson")

// 接收参数
composable(
    route = "confirm/{extractionJson}",
    arguments = listOf(navArgument("extractionJson") { type = NavType.StringType })
) { backStackEntry ->
    val json = URLDecoder.decode(
        backStackEntry.arguments?.getString("extractionJson") ?: "",
        "UTF-8"
    )
    val result = Json.decodeFromString<ExtractionResult>(json)
    ConfirmScreen(result, navController)
}
```

### 5.2 Event Bus (避免使用)

**不使用** EventBus / 全局事件总线。模块间通信统一通过：
1. Navigation 路由跳转 + 参数传递
2. 共享 Repository (Flow 数据流)
3. `SavedStateHandle` 回传结果

---

## 6. 错误处理契约

### 6.1 Result 封装

```kotlin
// :core-common
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val exception: AppException) : AppResult<Nothing>()
    data class Loading(val progress: Float? = null) : AppResult<Nothing>()
}

sealed class AppException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {
    class NetworkError(message: String, cause: Throwable? = null)
        : AppException(message, cause)
    class AiExtractionError(message: String, cause: Throwable? = null)
        : AppException(message, cause)
    class DatabaseError(message: String, cause: Throwable? = null)
        : AppException(message, cause)
    class ValidationError(message: String)
        : AppException(message)
    class PermissionError(message: String)
        : AppException(message)
}
```

### 6.2 UI 错误展示约定

| 错误类型 | UI 处理 |
|----------|---------|
| `NetworkError` | Snackbar "网络连接失败，已使用离线模式" |
| `AiExtractionError` | Snackbar "AI 识别失败" + 降级到手动表单 |
| `DatabaseError` | Dialog "数据操作失败，请重试" |
| `ValidationError` | 表单内联错误提示 |
| `PermissionError` | 权限引导 Dialog |

---

## 7. 通知监听服务接口

### 7.1 NotificationParser 策略接口

```kotlin
// :feature-capture
interface NotificationParser {
    /**
     * 判断是否可以解析该通知
     */
    fun canParse(packageName: String, title: String?): Boolean

    /**
     * 从通知中提取支付信息
     */
    fun parse(
        packageName: String,
        title: String?,
        text: String?,
        extras: Map<String, String>
    ): NotificationParseResult?
}

data class NotificationParseResult(
    val amount: Double,
    val merchantName: String?,
    val type: TransactionType,
    val rawText: String
)

// 具体实现
class WeChatPayParser : NotificationParser {
    override fun canParse(packageName: String, title: String?) =
        packageName == "com.tencent.mm" && title?.contains("支付") == true

    override fun parse(...): NotificationParseResult? {
        // 解析微信支付通知格式
    }
}

class AlipayParser : NotificationParser {
    override fun canParse(packageName: String, title: String?) =
        packageName == "com.eg.android.AlipayGphone"

    override fun parse(...): NotificationParseResult? {
        // 解析支付宝通知格式
    }
}
```

### 7.2 Service 与 Repository 交互

```kotlin
class PaymentNotificationService : NotificationListenerService() {

    @Inject lateinit var transactionRepo: TransactionRepository
    @Inject lateinit var parsers: Set<@JvmSuppressWildcards NotificationParser>

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val title = sbn.notification.extras.getString("android.title")
        val text = sbn.notification.extras.getString("android.text")

        val parser = parsers.firstOrNull { it.canParse(packageName, title ?: "") }
            ?: return

        val result = parser.parse(packageName, title, text, emptyMap())
            ?: return

        // 异步保存为待确认记录
        serviceScope.launch {
            transactionRepo.create(
                Transaction(
                    amount = result.amount,
                    type = result.type,
                    merchantName = result.merchantName,
                    source = TransactionSource.AUTO_CAPTURE,
                    status = TransactionStatus.PENDING,
                    // ...
                )
            )
        }
    }
}
```

---

## 8. 同步模块接口 (v1.0 预留)

### 8.1 SyncManager

```kotlin
// :feature-sync
interface SyncManager {
    /**
     * 触发一次同步
     */
    suspend fun syncNow(): Result<SyncReport>

    /**
     * 观察同步状态
     */
    fun observeSyncState(): Flow<SyncState>

    /**
     * 获取待同步数量
     */
    fun observePendingCount(): Flow<Int>
}

data class SyncReport(
    val uploaded: Int,
    val downloaded: Int,
    val conflicts: Int,
    val timestamp: Instant
)

enum class SyncState {
    IDLE, SYNCING, SUCCESS, ERROR
}
```

### 8.2 v1.0 实现

v1.0 提供 `NoOpSyncManager` 空实现，所有记录保持 `LOCAL` 状态：

```kotlin
class NoOpSyncManager @Inject constructor() : SyncManager {
    override suspend fun syncNow() = Result.success(SyncReport(0, 0, 0, Instant.now()))
    override fun observeSyncState() = flowOf(SyncState.IDLE)
    override fun observePendingCount() = flowOf(0)
}
```

---

## 9. 依赖关系矩阵

```
                    core-common  core-data  feature-input  feature-capture  feature-stats  feature-sync
core-common              -          -            -               -               -              -
core-data                ✅          -            -               -               -              -
feature-input            ✅          ✅            -               -               -              -
feature-capture          ✅          ✅            -               -               -              -
feature-stats            ✅          ✅            -               -               -              -
feature-sync             ✅          ✅            -               -               -              -
app                      ✅          ✅            ✅               ✅               ✅              ✅

✅ = 依赖   -  = 无依赖
```

**关键约束**: Feature 模块之间**绝不**直接依赖。如需交互，通过 Navigation 路由或共享 Repository。

---

*文档版本: v1.0 | 最后更新: 2026-03-15*
