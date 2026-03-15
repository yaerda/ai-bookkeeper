# 🏗️ 系统架构设计文档

## AI 智能记账 — Android App

| 字段 | 值 |
|------|-----|
| 文档类型 | 技术架构设计 |
| 版本 | v1.0 |
| 作者 | 架构师 Agent |
| 创建日期 | 2026-03-15 |
| 状态 | 初版 |

---

## 1. 架构概览

### 1.1 架构风格

采用 **MVVM + Clean Architecture + 离线优先** 的分层架构，结合 Gradle 多模块实现关注点分离。

```
┌─────────────────────────────────────────────────────┐
│                   Presentation Layer                 │
│   Jetpack Compose UI  ←→  ViewModel (StateFlow)     │
├─────────────────────────────────────────────────────┤
│                    Domain Layer                      │
│         UseCase / Repository Interface               │
├─────────────────────────────────────────────────────┤
│                     Data Layer                       │
│  Room (Local)  │  Retrofit (Remote)  │  ML Kit       │
└─────────────────────────────────────────────────────┘
```

### 1.2 核心设计原则

| 原则 | 说明 |
|------|------|
| **离线优先** | Room 为单一数据源 (Single Source of Truth)，网络层只做 AI 推理和远程同步 |
| **单向数据流** | UI → ViewModel → UseCase → Repository → DataSource |
| **依赖倒置** | 上层模块只依赖抽象接口，不依赖具体实现 |
| **模块化** | 功能模块独立编译，降低耦合、加速构建 |
| **可降级** | AI 功能具备离线回退能力，任何网络依赖功能均有本地替代方案 |

---

## 2. Gradle 多模块结构

### 2.1 模块依赖图

```
                        :app
                     ╱   │   ╲
                   ╱     │     ╲
                 ╱       │       ╲
    :feature-input  :feature-capture  :feature-stats
         │    ╲      │     ╱            │
         │     ╲     │    ╱             │
         │      ╲    │   ╱              │
         │    :feature-sync             │
         │         │                    │
         ├─────────┼────────────────────┤
         │         │                    │
         └────── :core-data ───────────┘
                   │
              :core-common
```

### 2.2 模块职责

| 模块 | 职责 | 主要依赖 |
|------|------|----------|
| **`:app`** | Application 入口、导航图、Hilt Application 组件、主题配置 | 所有 feature 模块 |
| **`:core-common`** | 通用工具类、扩展函数、常量、基类、Result 封装 | Kotlin stdlib |
| **`:core-data`** | Room 数据库、DAO、Entity、Repository 实现、Azure OpenAI 客户端、ML Kit 封装 | Room, Retrofit, ML Kit, Hilt, :core-common |
| **`:feature-input`** | 文字/语音记账 UI 与 ViewModel、AI 提取交互流程 | :core-data, :core-common |
| **`:feature-capture`** | 拍照/相册 OCR、通知监听 Service | :core-data, :core-common |
| **`:feature-stats`** | 统计图表、预算管理 UI | :core-data, :core-common |
| **`:feature-sync`** | 同步队列管理、云端同步接口（v1.0 预留） | :core-data, :core-common |

### 2.3 模块详细内容

#### :app
```
:app/
├── src/main/
│   ├── java/.../
│   │   ├── AiBookkeeperApp.kt          // @HiltAndroidApp Application
│   │   ├── MainActivity.kt             // Single Activity
│   │   ├── navigation/
│   │   │   ├── AppNavGraph.kt           // NavHost 路由定义
│   │   │   └── BottomNavBar.kt          // 底部导航栏
│   │   └── di/
│   │       └── AppModule.kt             // App 级 Hilt Module
│   └── res/
│       ├── values/themes.xml
│       └── ...
└── build.gradle.kts
```

#### :core-common
```
:core-common/
├── src/main/java/.../
│   ├── result/
│   │   └── Result.kt                   // sealed class Result<T>
│   ├── extensions/
│   │   ├── DateExtensions.kt
│   │   ├── StringExtensions.kt
│   │   └── FlowExtensions.kt
│   ├── constants/
│   │   └── AppConstants.kt
│   └── base/
│       └── BaseViewModel.kt
└── build.gradle.kts
```

#### :core-data
```
:core-data/
├── src/main/java/.../
│   ├── local/
│   │   ├── AppDatabase.kt              // @Database
│   │   ├── dao/
│   │   │   ├── TransactionDao.kt
│   │   │   ├── CategoryDao.kt
│   │   │   ├── BudgetDao.kt
│   │   │   └── MonthlyStatsDao.kt
│   │   ├── entity/
│   │   │   ├── TransactionEntity.kt
│   │   │   ├── CategoryEntity.kt
│   │   │   ├── BudgetEntity.kt
│   │   │   └── MonthlyStatsEntity.kt
│   │   ├── converter/
│   │   │   └── Converters.kt           // TypeConverter
│   │   └── migration/
│   │       └── Migrations.kt
│   ├── remote/
│   │   ├── openai/
│   │   │   ├── AzureOpenAiService.kt   // Retrofit interface
│   │   │   ├── AzureOpenAiClient.kt    // 封装调用逻辑
│   │   │   ├── dto/
│   │   │   │   ├── ChatRequest.kt
│   │   │   │   └── ChatResponse.kt
│   │   │   └── prompt/
│   │   │       └── PromptTemplates.kt  // System/User prompt 模板
│   │   └── sync/
│   │       └── SyncApiService.kt       // 预留云同步接口
│   ├── repository/
│   │   ├── TransactionRepository.kt    // interface
│   │   ├── TransactionRepositoryImpl.kt
│   │   ├── CategoryRepository.kt
│   │   ├── CategoryRepositoryImpl.kt
│   │   ├── AiExtractionRepository.kt   // interface
│   │   ├── AiExtractionRepositoryImpl.kt
│   │   ├── BudgetRepository.kt
│   │   └── BudgetRepositoryImpl.kt
│   ├── model/
│   │   ├── Transaction.kt              // Domain model
│   │   ├── Category.kt
│   │   ├── Budget.kt
│   │   ├── ExtractionResult.kt         // AI 提取结果
│   │   ├── TransactionType.kt          // enum INCOME/EXPENSE
│   │   ├── TransactionSource.kt        // enum MANUAL/TEXT_AI/...
│   │   ├── TransactionStatus.kt        // enum CONFIRMED/PENDING
│   │   └── SyncStatus.kt               // enum LOCAL/PENDING_SYNC/SYNCED
│   ├── mapper/
│   │   ├── TransactionMapper.kt        // Entity ↔ Domain
│   │   └── CategoryMapper.kt
│   ├── ai/
│   │   ├── AiExtractor.kt              // AI 提取策略接口
│   │   ├── AzureOpenAiExtractor.kt     // 在线 AI 提取
│   │   ├── LocalRuleExtractor.kt       // 离线正则提取
│   │   └── ExtractionStrategyManager.kt // 策略管理器
│   └── di/
│       ├── DatabaseModule.kt            // @Module Room 提供
│       ├── NetworkModule.kt             // @Module Retrofit/OkHttp
│       └── RepositoryModule.kt          // @Module 绑定 Repository
└── build.gradle.kts
```

#### :feature-input
```
:feature-input/
├── src/main/java/.../
│   ├── text/
│   │   ├── TextInputScreen.kt          // Compose UI
│   │   └── TextInputViewModel.kt
│   ├── voice/
│   │   ├── VoiceInputScreen.kt
│   │   ├── VoiceInputViewModel.kt
│   │   └── SpeechRecognizerHelper.kt
│   ├── form/
│   │   ├── ManualFormScreen.kt
│   │   └── ManualFormViewModel.kt
│   ├── confirm/
│   │   ├── ConfirmScreen.kt            // AI 提取结果确认
│   │   └── ConfirmViewModel.kt
│   ├── home/
│   │   ├── HomeScreen.kt
│   │   └── HomeViewModel.kt
│   ├── detail/
│   │   ├── TransactionDetailScreen.kt
│   │   └── TransactionDetailViewModel.kt
│   └── navigation/
│       └── InputNavGraph.kt
└── build.gradle.kts
```

#### :feature-capture
```
:feature-capture/
├── src/main/java/.../
│   ├── ocr/
│   │   ├── CaptureScreen.kt
│   │   ├── CaptureViewModel.kt
│   │   └── OcrProcessor.kt             // ML Kit 文字识别
│   ├── notification/
│   │   ├── PaymentNotificationService.kt // NotificationListenerService
│   │   ├── NotificationParser.kt        // 通知解析
│   │   ├── WeChatParser.kt              // 微信通知策略
│   │   └── AlipayParser.kt              // 支付宝通知策略
│   ├── permission/
│   │   └── PermissionHelper.kt
│   └── navigation/
│       └── CaptureNavGraph.kt
└── build.gradle.kts
```

#### :feature-stats
```
:feature-stats/
├── src/main/java/.../
│   ├── overview/
│   │   ├── StatsScreen.kt
│   │   └── StatsViewModel.kt
│   ├── budget/
│   │   ├── BudgetScreen.kt
│   │   └── BudgetViewModel.kt
│   ├── chart/
│   │   ├── PieChartComposable.kt
│   │   └── TrendChartComposable.kt
│   ├── settings/
│   │   ├── SettingsScreen.kt
│   │   └── SettingsViewModel.kt
│   └── navigation/
│       └── StatsNavGraph.kt
└── build.gradle.kts
```

#### :feature-sync
```
:feature-sync/
├── src/main/java/.../
│   ├── queue/
│   │   ├── SyncQueueManager.kt
│   │   └── SyncWorker.kt               // WorkManager
│   ├── conflict/
│   │   └── ConflictResolver.kt
│   └── di/
│       └── SyncModule.kt
└── build.gradle.kts
```

---

## 3. 依赖注入 (Hilt) 设计

### 3.1 Component 层级

```
@HiltAndroidApp  AiBookkeeperApp
   └── @AndroidEntryPoint  MainActivity
        ├── @HiltViewModel  HomeViewModel
        ├── @HiltViewModel  TextInputViewModel
        ├── @HiltViewModel  VoiceInputViewModel
        ├── @HiltViewModel  CaptureViewModel
        ├── @HiltViewModel  StatsViewModel
        └── ...
```

### 3.2 Module 划分

| Module | 安装位置 | 提供 |
|--------|----------|------|
| `DatabaseModule` | `@InstallIn(SingletonComponent)` | `AppDatabase`, 所有 DAO |
| `NetworkModule` | `@InstallIn(SingletonComponent)` | `OkHttpClient`, `Retrofit`, `AzureOpenAiService` |
| `RepositoryModule` | `@InstallIn(SingletonComponent)` | 所有 Repository 接口绑定 |
| `AiModule` | `@InstallIn(SingletonComponent)` | `AiExtractor`, `ExtractionStrategyManager` |
| `AppModule` | `@InstallIn(SingletonComponent)` | `Context`, 全局配置 |

### 3.3 关键绑定示例

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTransactionRepository(
        impl: TransactionRepositoryImpl
    ): TransactionRepository

    @Binds
    @Singleton
    abstract fun bindAiExtractionRepository(
        impl: AiExtractionRepositoryImpl
    ): AiExtractionRepository

    @Binds
    @Singleton
    abstract fun bindCategoryRepository(
        impl: CategoryRepositoryImpl
    ): CategoryRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context, AppDatabase::class.java, "ai_bookkeeper.db"
        )
        .addMigrations(*Migrations.ALL)
        .addCallback(PrepopulateCallback()) // 预填充分类数据
        .build()
    }

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()
}
```

---

## 4. 数据流设计

### 4.1 文字记账核心数据流

```
┌──────────┐     ┌────────────┐     ┌──────────────────┐     ┌────────────────┐
│  Compose  │────>│  ViewModel │────>│  AiExtraction     │────>│ Azure OpenAI   │
│    UI     │     │            │     │  Repository       │     │ (Remote)       │
│           │<────│  StateFlow │<────│                   │<────│                │
└──────────┘     └────────────┘     │  ┌──fallback──┐   │     └────────────────┘
                                     │  │ LocalRule  │   │
                                     │  │ Extractor  │   │
                                     │  └────────────┘   │
                                     └────────┬──────────┘
                                              │ save
                                     ┌────────▼──────────┐
                                     │  Transaction      │
                                     │  Repository       │
                                     │  (Room DAO)       │
                                     └───────────────────┘
```

### 4.2 状态管理模式

每个 ViewModel 使用 `StateFlow` 管理 UI 状态：

```kotlin
// UiState 定义范式
data class TextInputUiState(
    val inputText: String = "",
    val isLoading: Boolean = false,
    val extractionResult: ExtractionResult? = null,
    val error: UiError? = null,
    val isSaved: Boolean = false
)

// ViewModel 范式
@HiltViewModel
class TextInputViewModel @Inject constructor(
    private val aiExtractionRepo: AiExtractionRepository,
    private val transactionRepo: TransactionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TextInputUiState())
    val uiState: StateFlow<TextInputUiState> = _uiState.asStateFlow()

    fun onSubmitText(text: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            aiExtractionRepo.extract(text)
                .onSuccess { result ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        extractionResult = result
                    )}
                }
                .onFailure { error ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = UiError.fromThrowable(error)
                    )}
                }
        }
    }
}
```

### 4.3 账单列表响应式数据流

```kotlin
// DAO 返回 Flow，Room 自动监听数据变化
@Query("SELECT * FROM transactions WHERE date BETWEEN :start AND :end ORDER BY date DESC")
fun getTransactionsByDateRange(start: Long, end: Long): Flow<List<TransactionEntity>>

// Repository 层做 Entity → Domain 映射
class TransactionRepositoryImpl @Inject constructor(
    private val dao: TransactionDao,
    private val mapper: TransactionMapper
) : TransactionRepository {
    override fun getTransactionsForMonth(yearMonth: YearMonth): Flow<List<Transaction>> {
        val start = yearMonth.atDay(1).atStartOfDay()
        val end = yearMonth.atEndOfMonth().atTime(23, 59, 59)
        return dao.getTransactionsByDateRange(
            start.toEpochSecond(ZoneOffset.UTC),
            end.toEpochSecond(ZoneOffset.UTC)
        ).map { entities -> entities.map(mapper::toDomain) }
    }
}
```

---

## 5. 技术选型决策

### 5.1 第三方库清单

| 类别 | 库 | 版本策略 | 选型理由 |
|------|-----|----------|----------|
| **UI** | Jetpack Compose BOM | Latest stable | 官方声明式 UI，与 Material 3 深度集成 |
| **导航** | Navigation Compose | Latest stable | 官方方案，类型安全路由 |
| **DI** | Hilt | Latest stable | 官方推荐，编译期校验，ViewModel 原生支持 |
| **数据库** | Room | Latest stable | 官方 ORM，Flow 响应式，编译期 SQL 校验 |
| **网络** | Retrofit + OkHttp | 2.9.x / 4.12.x | 行业标准，拦截器生态成熟 |
| **JSON** | Kotlinx Serialization | Latest stable | Kotlin 原生，比 Gson/Moshi 更好的空安全支持 |
| **图片** | Coil | 3.x | Kotlin-first，Compose 原生支持，轻量 |
| **OCR** | ML Kit Text Recognition | Latest | Google 官方，免费，支持中文，离线模型 |
| **图表** | Vico | Latest stable | Compose 原生图表库，Material 3 风格，轻量 |
| **后台任务** | WorkManager | Latest stable | 官方后台任务方案，保证可靠执行 |
| **语音** | Android SpeechRecognizer | 系统 API | 无需额外依赖，系统级语音识别 |
| **日期** | java.time (desugaring) | API 26+ | 原生支持，无需 ThreeTenABP |
| **测试** | JUnit 5 + Turbine + MockK | Latest | Kotlin 友好的测试框架组合 |

### 5.2 关键决策记录 (ADR)

#### ADR-001: 图表库选用 Vico 而非 MPAndroidChart
- **状态**: 已决定
- **上下文**: 需要月度饼图和趋势折线图
- **决策**: 使用 Vico
- **理由**: MPAndroidChart 基于 View 系统，在 Compose 中需要 AndroidView 包装；Vico 是 Compose 原生实现，Material 3 风格一致，API 更简洁
- **风险**: Vico 社区较小，复杂图表支持不如 MPAndroidChart

#### ADR-002: JSON 序列化使用 Kotlinx Serialization
- **状态**: 已决定
- **决策**: 使用 `kotlinx.serialization` 替代 Gson
- **理由**: 编译期生成序列化代码（性能更好）、Kotlin 原生空安全支持、无反射

#### ADR-003: Azure OpenAI 模型选择 GPT-4o-mini
- **状态**: 已决定
- **决策**: 默认使用 `gpt-4o-mini`，预留 `gpt-4o` 切换能力
- **理由**: 记账信息提取任务相对简单，`gpt-4o-mini` 性价比更高（约 1/30 成本），延迟更低（~1-2s vs ~3-5s）。对于复杂小票 OCR 文本可考虑升级到 `gpt-4o`
- **回答 PRD Q1**: 推荐 GPT-4o-mini

#### ADR-004: 统计图表用 Vico
- **状态**: 已决定
- **回答 PRD Q4**: 使用 Vico (Compose 原生方案)

---

## 6. 离线策略与网络降级

详见 [Azure OpenAI 集成方案](./AZURE_OPENAI_INTEGRATION.md) 第 4 节。

### 6.1 整体离线策略

```
┌─────────────────────────────────────────────────────────┐
│                   离线策略总览                            │
├──────────────┬──────────────────────────────────────────┤
│ 功能         │ 离线行为                                  │
├──────────────┼──────────────────────────────────────────┤
│ 手动记账     │ ✅ 完全可用 (Room 本地存储)                │
│ AI 文字提取  │ ⚡ 降级为本地正则引擎                      │
│ 语音输入     │ ⚠️ 依赖系统语音识别 (部分机型支持离线)     │
│ 拍照 OCR     │ ✅ ML Kit 支持离线模型                     │
│ 通知监听     │ ✅ 完全可用 (本地 Service)                 │
│ 统计图表     │ ✅ 完全可用 (本地数据)                     │
│ 云同步       │ ❌ 入队待同步 (PENDING_SYNC)               │
└──────────────┴──────────────────────────────────────────┘
```

### 6.2 网络状态监听

```kotlin
// 使用 ConnectivityManager 监听网络状态
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val isOnline: StateFlow<Boolean> // 全局可观察的网络状态

    // 网络恢复时触发：
    // 1. 重新提取 PENDING 状态记录的 AI 分析
    // 2. 触发同步队列上传
}
```

---

## 7. 安全设计

### 7.1 API Key 管理

| 方案 | 说明 |
|------|------|
| **开发环境** | `local.properties` 存储 Azure OpenAI Key，通过 `BuildConfig` 注入，不提交 Git |
| **生产环境** | CI/CD 通过环境变量注入，或使用 Android Keystore 加密存储 |
| **运行时** | OkHttp Interceptor 统一注入 Authorization Header |

```properties
# local.properties (git ignored)
AZURE_OPENAI_API_KEY=your-key-here
AZURE_OPENAI_ENDPOINT=https://your-resource.openai.azure.com/
AZURE_OPENAI_DEPLOYMENT=gpt-4o-mini
```

### 7.2 数据安全

- Room 数据库使用 SQLCipher 加密 (P2 阶段)
- 仅文本数据发送到 Azure OpenAI，图片不上传
- 通知监听的原始通知内容不持久化，仅提取结构化数据后存储
- ProGuard/R8 混淆保护

---

## 8. 构建与 CI 配置

### 8.1 Gradle 配置策略

```kotlin
// settings.gradle.kts
include(":app")
include(":core-common")
include(":core-data")
include(":feature-input")
include(":feature-capture")
include(":feature-stats")
include(":feature-sync")
```

### 8.2 版本目录 (libs.versions.toml)

使用 Gradle Version Catalog 统一管理依赖版本：

```toml
[versions]
kotlin = "2.0.x"
compose-bom = "2025.x"
hilt = "2.51.x"
room = "2.6.x"
retrofit = "2.9.x"
okhttp = "4.12.x"
kotlinx-serialization = "1.7.x"
vico = "2.x"
mlkit-text = "16.x"
coil = "3.x"
workmanager = "2.9.x"
navigation = "2.8.x"

[libraries]
# ... 统一声明

[plugins]
# ... 统一声明
```

### 8.3 Git 分支策略

```
main          ← 生产发布分支 (tag: v1.0, v1.1, ...)
  └── develop ← 开发主分支
       ├── feature/text-input     (US-1.1)
       ├── feature/ai-extraction  (US-2.1)
       ├── feature/voice-input    (US-3.1)
       ├── feature/photo-ocr      (US-4.1)
       ├── feature/notification   (US-5.1)
       └── feature/stats          (US-6.1)
```

- **命名规范**: `feature/<功能名>`, `bugfix/<问题描述>`, `hotfix/<紧急修复>`
- **合并策略**: Squash merge to develop, merge commit to main
- **Code Review**: 所有 PR 至少 1 人 review

---

## 9. 技术风险与应对

| 风险 | 概率 | 影响 | 应对方案 |
|------|------|------|----------|
| Azure OpenAI 延迟 >5s | 中 | 用户体验差 | 5s 超时回退本地引擎 + 后台重试 |
| 微信/支付宝通知格式变更 | 高 | 自动捕获失效 | 策略模式 + 可热更新的解析规则 |
| ML Kit 中文 OCR 准确率不足 | 中 | 拍照记账不准 | 用户可编辑 OCR 文本 + AI 二次理解 |
| Room 数据库迁移失败 | 低 | 数据丢失 | 严格版本迁移 + 迁移测试 + 导出备份 |
| Hilt 多模块编译缓慢 | 中 | 开发效率降低 | KSP 替代 KAPT + Gradle Build Cache |
| 设备碎片化 | 中 | 兼容性问题 | 最低 API 26 + 主流设备测试矩阵 |

---

## 10. 相关文档

| 文档 | 说明 |
|------|------|
| [数据库 Schema 设计](./DATABASE_SCHEMA.md) | Room Entity、DAO、迁移策略详细设计 |
| [Azure OpenAI 集成方案](./AZURE_OPENAI_INTEGRATION.md) | API 集成、Prompt 设计、离线回退策略 |
| [模块间接口契约](./MODULE_CONTRACTS.md) | Repository 接口、数据模型、模块间通信契约 |

---

*文档版本: v1.0 | 最后更新: 2026-03-15*
