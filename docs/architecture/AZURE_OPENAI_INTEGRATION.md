# 🤖 Azure OpenAI 集成方案

## AI 智能记账 — AI 提取与离线回退

| 字段 | 值 |
|------|-----|
| 文档类型 | 技术集成方案 |
| 版本 | v1.0 |
| 模型 | Azure OpenAI GPT-4o-mini (默认) |
| 回退 | 本地正则规则引擎 |

---

## 1. 架构概览

```
用户输入 (文字/语音转文字/OCR文本)
         │
         ▼
┌─────────────────────────┐
│  ExtractionStrategyMgr  │ ← 策略管理器
│  (网络检测 + 超时控制)   │
└────────┬────────────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
┌────────┐ ┌──────────┐
│ Online │ │ Offline  │
│ Azure  │ │ Local    │
│ OpenAI │ │ Regex    │
│        │ │ Engine   │
└───┬────┘ └────┬─────┘
    │           │
    └─────┬─────┘
          ▼
  ExtractionResult
  {amount, type, category,
   merchant, date, note,
   confidence, source}
```

---

## 2. Azure OpenAI API 集成

### 2.1 API 配置

```kotlin
// 环境配置 (BuildConfig 注入)
object AzureOpenAiConfig {
    val ENDPOINT: String = BuildConfig.AZURE_OPENAI_ENDPOINT
        // e.g. "https://<resource>.openai.azure.com/"
    val API_KEY: String = BuildConfig.AZURE_OPENAI_API_KEY
    val DEPLOYMENT: String = BuildConfig.AZURE_OPENAI_DEPLOYMENT
        // e.g. "gpt-4o-mini"
    val API_VERSION: String = "2024-10-21"
    val TIMEOUT_SECONDS: Long = 5
    val MAX_TOKENS: Int = 256
}
```

### 2.2 Retrofit Service 定义

```kotlin
interface AzureOpenAiService {

    @POST("openai/deployments/{deployment}/chat/completions")
    suspend fun chatCompletion(
        @Path("deployment") deployment: String,
        @Query("api-version") apiVersion: String,
        @Header("api-key") apiKey: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}
```

### 2.3 请求/响应 DTO

```kotlin
@Serializable
data class ChatCompletionRequest(
    val messages: List<ChatMessage>,
    val temperature: Float = 0.1f,          // 低温度保证稳定输出
    val max_tokens: Int = 256,
    val response_format: ResponseFormat? = ResponseFormat("json_object")
)

@Serializable
data class ChatMessage(
    val role: String,       // "system" | "user"
    val content: String
)

@Serializable
data class ResponseFormat(
    val type: String        // "json_object"
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice>
)

@Serializable
data class Choice(
    val message: ChatMessage,
    val finish_reason: String
)
```

### 2.4 AI 提取结果模型

```kotlin
@Serializable
data class AiExtractionResponse(
    val amount: Double?,
    val type: String?,          // "INCOME" | "EXPENSE"
    val category: String?,      // 分类名 (中文)
    val merchant: String?,      // 商户名
    val date: String?,          // ISO 日期 "2026-03-15"
    val note: String?,          // 备注
    val confidence: Float = 0.5f
)
```

---

## 3. Prompt 工程

### 3.1 System Prompt

```kotlin
object PromptTemplates {

    val SYSTEM_PROMPT = """
你是一个专业的记账助手。你的任务是从用户输入的自然语言中提取记账信息。

请严格返回以下 JSON 格式（不要返回任何其他内容）：
{
  "amount": <数字，金额，必须为正数>,
  "type": "<INCOME 或 EXPENSE>",
  "category": "<分类名，从以下选择>",
  "merchant": "<商户名，如果有的话，否则 null>",
  "date": "<ISO 日期格式 YYYY-MM-DD，如果提到了时间；否则 null 表示今天>",
  "note": "<简短备注>",
  "confidence": <0.0-1.0 的置信度>
}

可用的支出分类: 餐饮, 交通, 购物, 娱乐, 居住, 医疗, 教育, 通讯, 服饰, 其他
可用的收入分类: 工资, 奖金, 兼职, 理财, 红包, 其他

规则：
1. 如果没有明确说收入，默认为支出 (EXPENSE)
2. 金额必须为正数
3. 如果无法确定金额，amount 设为 null
4. 根据上下文推断最合适的分类
5. "昨天" = 当前日期 -1天，"前天" = -2天
6. confidence 根据信息完整度打分：金额明确+分类明确=0.9+，金额模糊=0.5-
""".trimIndent()

    fun buildUserPrompt(input: String, currentDate: String): String {
        return "今天是 $currentDate。用户输入：「$input」"
    }

    // OCR 文本的增强 prompt
    val OCR_SYSTEM_PROMPT = """
你是一个专业的记账助手。你的任务是从 OCR 识别出的小票/截图文字中提取记账信息。

OCR 文字可能有识别错误，请尽量理解并修正。
如果是购物小票，请提取总金额（不是单品金额）。
如果有多笔消费，请提取总金额并在 note 中说明。

请严格返回 JSON 格式（与标准格式相同）。
""".trimIndent()
}
```

### 3.2 Prompt 设计原则

| 原则 | 说明 |
|------|------|
| **JSON 强制输出** | 使用 `response_format: json_object` 保证结构化返回 |
| **低温度** | `temperature: 0.1` 确保相同输入返回一致结果 |
| **分类约束** | 限定可选分类列表，避免 AI 生成不存在的分类 |
| **日期上下文** | 传入当前日期，帮助 AI 理解"昨天""上周"等相对时间 |
| **置信度自评** | AI 自评 confidence，用于 UI 提示需要用户确认的字段 |

---

## 4. 离线回退策略 (Offline Fallback)

### 4.1 策略管理器

```kotlin
@Singleton
class ExtractionStrategyManager @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val azureExtractor: AzureOpenAiExtractor,
    private val localExtractor: LocalRuleExtractor
) {
    suspend fun extract(input: String): Result<ExtractionResult> {
        // 策略 1: 有网络 → 尝试 Azure OpenAI
        if (networkMonitor.isOnline.value) {
            val result = azureExtractor.extract(input)
            if (result.isSuccess) return result
            // Azure 失败 (超时/错误) → 回退本地
        }

        // 策略 2: 无网络或 Azure 失败 → 本地规则引擎
        return localExtractor.extract(input).also { result ->
            // 标记为本地提取，网络恢复后可重新 AI 解析
            result.getOrNull()?.let { it.copy(source = ExtractionSource.LOCAL_RULE) }
        }
    }
}
```

### 4.2 在线提取器

```kotlin
class AzureOpenAiExtractor @Inject constructor(
    private val apiService: AzureOpenAiService,
    private val config: AzureOpenAiConfig
) : AiExtractor {

    override suspend fun extract(input: String): Result<ExtractionResult> {
        return try {
            withTimeout(config.TIMEOUT_SECONDS * 1000L) {
                val request = ChatCompletionRequest(
                    messages = listOf(
                        ChatMessage("system", PromptTemplates.SYSTEM_PROMPT),
                        ChatMessage("user", PromptTemplates.buildUserPrompt(
                            input, LocalDate.now().toString()
                        ))
                    ),
                    temperature = 0.1f,
                    max_tokens = config.MAX_TOKENS
                )

                val response = apiService.chatCompletion(
                    deployment = config.DEPLOYMENT,
                    apiVersion = config.API_VERSION,
                    apiKey = config.API_KEY,
                    request = request
                )

                val json = response.choices.first().message.content
                val parsed = Json.decodeFromString<AiExtractionResponse>(json)
                Result.success(parsed.toExtractionResult(ExtractionSource.AZURE_AI))
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(AiExtractionException("AI 提取超时", e))
        } catch (e: Exception) {
            Result.failure(AiExtractionException("AI 提取失败: ${e.message}", e))
        }
    }
}
```

### 4.3 本地规则引擎 (离线回退)

```kotlin
class LocalRuleExtractor @Inject constructor() : AiExtractor {

    // 金额提取正则
    private val amountPatterns = listOf(
        Regex("""(\d+\.?\d*)\s*[元块圆]"""),            // 35元, 35.5块
        Regex("""[¥￥]\s*(\d+\.?\d*)"""),                // ¥35, ￥35.5
        Regex("""花了\s*(\d+\.?\d*)"""),                  // 花了35
        Regex("""(\d+\.?\d*)\s*$"""),                     // 末尾数字
        Regex("""(?:^|\s)(\d+\.?\d*)(?:\s|$)"""),        // 独立数字
    )

    // 分类关键词映射
    private val categoryKeywords = mapOf(
        "餐饮" to listOf("饭", "餐", "吃", "食", "外卖", "咖啡", "奶茶", "火锅",
                         "星巴克", "麦当劳", "肯德基", "美团", "饿了么"),
        "交通" to listOf("打车", "出租", "地铁", "公交", "滴滴", "高铁", "火车",
                         "飞机", "机票", "油费", "停车", "加油"),
        "购物" to listOf("买", "购", "淘宝", "京东", "拼多多", "超市"),
        "娱乐" to listOf("电影", "游戏", "KTV", "唱歌", "旅游", "门票"),
        "居住" to listOf("房租", "水费", "电费", "物业", "燃气"),
        "医疗" to listOf("医院", "药", "看病", "挂号", "体检"),
        "教育" to listOf("课", "学费", "培训", "书"),
        "通讯" to listOf("话费", "流量", "宽带"),
        "服饰" to listOf("衣服", "鞋", "裤", "帽"),
    )

    // 收入关键词
    private val incomeKeywords = listOf(
        "工资", "薪水", "收入", "收到", "入账", "奖金",
        "兼职", "稿费", "红包", "报销", "退款", "利息"
    )

    // 日期关键词
    private val dateKeywords = mapOf(
        "今天" to 0L,
        "昨天" to -1L,
        "前天" to -2L,
    )

    override suspend fun extract(input: String): Result<ExtractionResult> {
        val amount = extractAmount(input)
        val type = if (incomeKeywords.any { input.contains(it) }) "INCOME" else "EXPENSE"
        val category = extractCategory(input, type)
        val date = extractDate(input)

        val confidence = when {
            amount != null && category != "其他" -> 0.7f
            amount != null -> 0.5f
            else -> 0.2f
        }

        return Result.success(
            ExtractionResult(
                amount = amount,
                type = type,
                category = category,
                merchantName = null,   // 本地引擎不提取商户
                date = date,
                note = input,
                confidence = confidence,
                source = ExtractionSource.LOCAL_RULE
            )
        )
    }

    private fun extractAmount(input: String): Double? {
        for (pattern in amountPatterns) {
            val match = pattern.find(input)
            if (match != null) {
                return match.groupValues[1].toDoubleOrNull()
            }
        }
        return null
    }

    private fun extractCategory(input: String, type: String): String {
        if (type == "INCOME") {
            return when {
                input.contains("工资") || input.contains("薪水") -> "工资"
                input.contains("奖金") -> "奖金"
                input.contains("兼职") || input.contains("稿费") -> "兼职"
                input.contains("利息") || input.contains("理财") -> "理财"
                input.contains("红包") -> "红包"
                else -> "其他"
            }
        }

        for ((category, keywords) in categoryKeywords) {
            if (keywords.any { input.contains(it) }) {
                return category
            }
        }
        return "其他"
    }

    private fun extractDate(input: String): LocalDate {
        for ((keyword, offset) in dateKeywords) {
            if (input.contains(keyword)) {
                return LocalDate.now().plusDays(offset)
            }
        }
        return LocalDate.now()
    }
}
```

### 4.4 回退流程图

```
                       用户输入文本
                           │
                    ┌──────▼──────┐
                    │ 检查网络状态  │
                    └──────┬──────┘
                     ╱          ╲
                在线              离线
                 │                 │
         ┌───────▼────────┐       │
         │ Azure OpenAI   │       │
         │ (5s timeout)   │       │
         └───────┬────────┘       │
              ╱      ╲            │
          成功        失败/超时    │
           │            │         │
           │     ┌──────▼─────────▼──────┐
           │     │  本地正则引擎 (离线)    │
           │     │  confidence: 0.2-0.7   │
           │     └──────────┬────────────┘
           │                │
           │      标记 source=LOCAL_RULE
           │      网络恢复后可重新提取
           │                │
     ┌─────▼────────────────▼─────┐
     │      ExtractionResult       │
     │  展示预览卡片，用户确认       │
     └─────────────────────────────┘
```

---

## 5. 网络恢复重提取

### 5.1 机制设计

对于离线状态下通过本地规则引擎提取的记录，在网络恢复后可选择重新提取：

```kotlin
class PendingReExtractionWorker(
    context: Context,
    params: WorkerParameters,
    private val transactionRepo: TransactionRepository,
    private val aiRepo: AiExtractionRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 查找本地提取且置信度较低的记录
        val pendingItems = transactionRepo.getLocalExtractedWithLowConfidence(
            maxConfidence = 0.7f,
            limit = 10
        )

        pendingItems.forEach { transaction ->
            transaction.originalInput?.let { input ->
                val result = aiRepo.extractOnline(input)
                if (result.isSuccess && result.getOrNull()!!.confidence > transaction.aiConfidence ?: 0f) {
                    // 更新为 AI 提取结果，但保持用户已确认的字段不变
                    transactionRepo.updateAiFields(
                        id = transaction.id,
                        result = result.getOrNull()!!
                    )
                }
            }
        }

        return Result.success()
    }
}
```

### 5.2 触发时机

| 触发条件 | 说明 |
|----------|------|
| 网络恢复 | `NetworkMonitor` 监听到连接恢复时，触发 WorkManager 任务 |
| 应用启动 | 冷启动时检查是否有待重提取记录 |
| 用户手动 | 记录详情页提供"重新 AI 分析"按钮 |

---

## 6. 错误处理与重试

### 6.1 错误分类

| 错误类型 | HTTP 状态 | 处理方式 |
|----------|-----------|----------|
| 网络不可达 | - | 直接回退本地引擎 |
| 请求超时 | Timeout | 回退本地引擎，后台重试 |
| 认证失败 | 401 | 记录错误日志，回退本地，提示检查配置 |
| 限流 | 429 | 指数退避重试 (1s, 2s, 4s)，最多 3 次 |
| 服务端错误 | 5xx | 回退本地，后台延迟重试 |
| JSON 解析失败 | 200 但格式异常 | 回退本地，记录原始响应用于调试 |

### 6.2 重试策略

```kotlin
class RetryPolicy {
    companion object {
        const val MAX_RETRIES = 3
        const val INITIAL_DELAY_MS = 1000L
        const val BACKOFF_MULTIPLIER = 2.0

        suspend fun <T> withRetry(
            maxRetries: Int = MAX_RETRIES,
            shouldRetry: (Exception) -> Boolean = { it.isRetryable() },
            block: suspend () -> T
        ): T {
            var lastException: Exception? = null
            var delay = INITIAL_DELAY_MS

            repeat(maxRetries) { attempt ->
                try {
                    return block()
                } catch (e: Exception) {
                    lastException = e
                    if (!shouldRetry(e) || attempt == maxRetries - 1) throw e
                    delay(delay)
                    delay = (delay * BACKOFF_MULTIPLIER).toLong()
                }
            }
            throw lastException!!
        }
    }
}

private fun Exception.isRetryable(): Boolean = when (this) {
    is HttpException -> code() in listOf(429, 500, 502, 503)
    is IOException -> true
    else -> false
}
```

---

## 7. OkHttp 配置

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG)
                    HttpLoggingInterceptor.Level.BODY
                else
                    HttpLoggingInterceptor.Level.NONE
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideAzureOpenAiService(client: OkHttpClient): AzureOpenAiService {
        return Retrofit.Builder()
            .baseUrl(AzureOpenAiConfig.ENDPOINT)
            .client(client)
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }
                    .asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(AzureOpenAiService::class.java)
    }
}
```

---

## 8. 性能预算

| 指标 | 目标 | 实际预估 |
|------|------|----------|
| Azure OpenAI 调用延迟 (GPT-4o-mini) | < 3s | ~1-2s |
| 本地正则提取延迟 | < 100ms | ~10-50ms |
| 超时回退总延迟 | < 5.5s | 5s timeout + 50ms fallback |
| ML Kit OCR 延迟 | < 2s | ~500ms-1.5s |
| 端到端文字记账 (在线) | < 3s | ~2-3s |
| 端到端文字记账 (离线) | < 500ms | ~100-200ms |

---

## 9. 成本估算

### GPT-4o-mini 调用成本 (Azure)

| 场景 | 输入 Token | 输出 Token | 单次成本 | 日均调用 | 月成本 |
|------|-----------|-----------|---------|---------|--------|
| 文字提取 | ~300 | ~100 | ~$0.00006 | 8 次 | ~$0.015 |
| OCR 提取 | ~500 | ~150 | ~$0.0001 | 2 次 | ~$0.006 |
| 通知提取 | ~200 | ~80 | ~$0.00004 | 5 次 | ~$0.006 |
| **合计 (单用户)** | | | | **15 次/天** | **~$0.03/月** |

结论：GPT-4o-mini 成本极低，完全满足个人记账应用的需求。

---

## 10. 安全考量

| 措施 | 说明 |
|------|------|
| API Key 不硬编码 | 通过 BuildConfig 从 `local.properties` 注入 |
| 最小化数据传输 | 仅发送用户输入文本，不发送图片 / 个人信息 |
| 传输加密 | HTTPS (Azure 强制) |
| 日志脱敏 | Release 模式禁用 HTTP Body 日志 |
| 请求限频 | 客户端限制每分钟最多 30 次调用，防滥用 |

---

*文档版本: v1.0 | 最后更新: 2026-03-15*
