# 💾 Room 数据库 Schema 设计

## AI 智能记账 — Database Design

| 字段 | 值 |
|------|-----|
| 文档类型 | 数据库设计 |
| 版本 | v1.0 |
| 数据库版本号 | 1 |
| 数据库名称 | `ai_bookkeeper.db` |

---

## 1. ER 关系图

```
┌──────────────┐       ┌──────────────┐
│  categories  │       │   budgets    │
│──────────────│       │──────────────│
│ PK id        │◄──┐   │ PK id        │
│ name         │   │   │ FK categoryId│──┐
│ icon         │   │   │ amount       │  │
│ color        │   │   │ month        │  │
│ type         │   │   │ createdAt    │  │
│ parentId(FK) │───┘   └──────────────┘  │
│ isSystem     │◄────────────────────────┘
│ sortOrder    │
└──────┬───────┘
       │
       │ 1:N
       │
┌──────▼───────────────┐     ┌──────────────────────┐
│   transactions       │     │   monthly_stats      │
│──────────────────────│     │──────────────────────│
│ PK id                │     │ PK month             │
│ amount               │     │ totalIncome          │
│ type                 │     │ totalExpense         │
│ FK categoryId        │     │ categoryBreakdown    │
│ merchantName         │     │ updatedAt            │
│ note                 │     └──────────────────────┘
│ originalInput        │
│ date                 │
│ createdAt            │
│ updatedAt            │
│ source               │
│ status               │
│ syncStatus           │
│ aiConfidence         │
│ aiRawResponse        │
└──────────────────────┘
```

---

## 2. Entity 定义

### 2.1 TransactionEntity

```kotlin
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["date"]),
        Index(value = ["categoryId"]),
        Index(value = ["type"]),
        Index(value = ["status"]),
        Index(value = ["syncStatus"]),
        Index(value = ["date", "type"]) // 月度统计复合索引
    ],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "amount")
    val amount: Double,                          // 金额 (始终正值)

    @ColumnInfo(name = "type")
    val type: String,                            // "INCOME" | "EXPENSE"

    @ColumnInfo(name = "categoryId")
    val categoryId: Long?,                       // FK → categories.id

    @ColumnInfo(name = "merchantName")
    val merchantName: String? = null,            // 商户名 (如 "星巴克")

    @ColumnInfo(name = "note")
    val note: String? = null,                    // 用户备注

    @ColumnInfo(name = "originalInput")
    val originalInput: String? = null,           // 原始输入文本 (用于优化 AI)

    @ColumnInfo(name = "date")
    val date: Long,                              // 消费时间 (epoch millis)

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,                         // 记录创建时间

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,                         // 最后更新时间

    @ColumnInfo(name = "source")
    val source: String,                          // MANUAL|TEXT_AI|VOICE_AI|PHOTO_AI|AUTO_CAPTURE

    @ColumnInfo(name = "status")
    val status: String = "CONFIRMED",            // CONFIRMED | PENDING

    @ColumnInfo(name = "syncStatus")
    val syncStatus: String = "LOCAL",            // LOCAL | PENDING_SYNC | SYNCED

    @ColumnInfo(name = "aiConfidence")
    val aiConfidence: Float? = null,             // AI 提取置信度 0.0-1.0

    @ColumnInfo(name = "aiRawResponse")
    val aiRawResponse: String? = null            // AI 原始 JSON 响应 (调试/优化用)
)
```

**设计说明：**
- `amount` 始终为正值，收支方向由 `type` 决定
- 时间统一使用 `epoch millis (Long)` 存储，避免时区转换问题
- `originalInput` 保留原始用户输入，用于后续 AI 模型优化和训练
- `aiRawResponse` 保留 AI 原始返回，便于分析提取准确率
- Enum 使用 `String` 存储而非 ordinal，保证数据库迁移兼容性

### 2.2 CategoryEntity

```kotlin
@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["type"]),
        Index(value = ["parentId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,                            // 分类名 (如 "餐饮")

    @ColumnInfo(name = "icon")
    val icon: String,                            // 图标资源名 (如 "ic_food")

    @ColumnInfo(name = "color")
    val color: String,                           // 颜色 HEX (如 "#FF5722")

    @ColumnInfo(name = "type")
    val type: String,                            // "INCOME" | "EXPENSE"

    @ColumnInfo(name = "parentId")
    val parentId: Long? = null,                  // 父分类 ID (null = 一级分类)

    @ColumnInfo(name = "isSystem")
    val isSystem: Boolean = true,                // 系统预置分类不可删除

    @ColumnInfo(name = "sortOrder")
    val sortOrder: Int = 0                       // 排序权重
)
```

### 2.3 BudgetEntity

```kotlin
@Entity(
    tableName = "budgets",
    indices = [
        Index(value = ["month"]),
        Index(value = ["month", "categoryId"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "categoryId")
    val categoryId: Long? = null,                // null = 月度总预算

    @ColumnInfo(name = "amount")
    val amount: Double,                          // 预算金额

    @ColumnInfo(name = "month")
    val month: String,                           // "2026-03" (YearMonth ISO 格式)

    @ColumnInfo(name = "createdAt")
    val createdAt: Long
)
```

**设计说明：**
- `(month, categoryId)` 联合唯一索引，防止重复设置
- `categoryId = null` 表示月度总预算

### 2.4 MonthlyStatsEntity

```kotlin
@Entity(
    tableName = "monthly_stats"
)
data class MonthlyStatsEntity(
    @PrimaryKey
    @ColumnInfo(name = "month")
    val month: String,                           // "2026-03"

    @ColumnInfo(name = "totalIncome")
    val totalIncome: Double = 0.0,

    @ColumnInfo(name = "totalExpense")
    val totalExpense: Double = 0.0,

    @ColumnInfo(name = "categoryBreakdown")
    val categoryBreakdown: String = "{}",        // JSON: {"餐饮": 1500.0, "交通": 300.0}

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long
)
```

**设计说明：**
- 这是一个**计算缓存表**，由 Transaction 变更时异步更新
- `categoryBreakdown` 使用 JSON 字符串避免额外表，查询直接反序列化
- 统计页面优先读取缓存，避免每次实时聚合大量 Transaction

---

## 3. DAO 接口设计

### 3.1 TransactionDao

```kotlin
@Dao
interface TransactionDao {

    // === 插入 ===
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    // === 更新 ===
    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("UPDATE transactions SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long)

    @Query("UPDATE transactions SET syncStatus = :syncStatus WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, syncStatus: String)

    // === 删除 ===
    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    // === 查询 - 单条 ===
    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeById(id: Long): Flow<TransactionEntity?>

    // === 查询 - 列表 (响应式) ===
    @Query("""
        SELECT * FROM transactions 
        WHERE date BETWEEN :startMillis AND :endMillis 
        ORDER BY date DESC
    """)
    fun observeByDateRange(startMillis: Long, endMillis: Long): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions 
        WHERE date BETWEEN :startMillis AND :endMillis AND type = :type
        ORDER BY date DESC
    """)
    fun observeByDateRangeAndType(
        startMillis: Long, endMillis: Long, type: String
    ): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions 
        WHERE status = :status 
        ORDER BY createdAt DESC
    """)
    fun observeByStatus(status: String): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions 
        WHERE categoryId = :categoryId AND date BETWEEN :startMillis AND :endMillis
        ORDER BY date DESC
    """)
    fun observeByCategoryAndDateRange(
        categoryId: Long, startMillis: Long, endMillis: Long
    ): Flow<List<TransactionEntity>>

    // === 聚合查询 ===
    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM transactions 
        WHERE type = :type AND date BETWEEN :startMillis AND :endMillis
        AND status = 'CONFIRMED'
    """)
    suspend fun sumByTypeAndDateRange(type: String, startMillis: Long, endMillis: Long): Double

    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM transactions 
        WHERE type = :type AND date BETWEEN :startMillis AND :endMillis
        AND status = 'CONFIRMED'
    """)
    fun observeSumByTypeAndDateRange(
        type: String, startMillis: Long, endMillis: Long
    ): Flow<Double>

    @Query("""
        SELECT categoryId, SUM(amount) as total 
        FROM transactions 
        WHERE type = 'EXPENSE' AND date BETWEEN :startMillis AND :endMillis
        AND status = 'CONFIRMED'
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    fun observeExpenseBreakdown(
        startMillis: Long, endMillis: Long
    ): Flow<List<CategorySum>>

    @Query("""
        SELECT COUNT(*) FROM transactions 
        WHERE date BETWEEN :startMillis AND :endMillis
    """)
    suspend fun countByDateRange(startMillis: Long, endMillis: Long): Int

    // === 同步相关 ===
    @Query("SELECT * FROM transactions WHERE syncStatus = 'PENDING_SYNC' ORDER BY updatedAt ASC")
    suspend fun getPendingSyncTransactions(): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE syncStatus = 'PENDING_SYNC'")
    fun observePendingSyncCount(): Flow<Int>

    // === 搜索 ===
    @Query("""
        SELECT * FROM transactions 
        WHERE note LIKE '%' || :keyword || '%' 
           OR merchantName LIKE '%' || :keyword || '%'
        ORDER BY date DESC
        LIMIT :limit
    """)
    suspend fun search(keyword: String, limit: Int = 50): List<TransactionEntity>
}

// 聚合查询辅助类
data class CategorySum(
    val categoryId: Long?,
    val total: Double
)
```

### 3.2 CategoryDao

```kotlin
@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE type = :type AND parentId IS NULL ORDER BY sortOrder ASC")
    fun observeTopLevelByType(type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE parentId = :parentId ORDER BY sortOrder ASC")
    fun observeSubCategories(parentId: Long): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY type, sortOrder ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE name = :name AND type = :type LIMIT 1")
    suspend fun findByNameAndType(name: String, type: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}
```

### 3.3 BudgetDao

```kotlin
@Dao
interface BudgetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: BudgetEntity): Long

    @Update
    suspend fun update(budget: BudgetEntity)

    @Delete
    suspend fun delete(budget: BudgetEntity)

    @Query("SELECT * FROM budgets WHERE month = :month AND categoryId IS NULL LIMIT 1")
    fun observeMonthlyTotal(month: String): Flow<BudgetEntity?>

    @Query("SELECT * FROM budgets WHERE month = :month AND categoryId IS NOT NULL")
    fun observeCategoryBudgets(month: String): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE month = :month")
    fun observeAllForMonth(month: String): Flow<List<BudgetEntity>>
}
```

### 3.4 MonthlyStatsDao

```kotlin
@Dao
interface MonthlyStatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(stats: MonthlyStatsEntity)

    @Query("SELECT * FROM monthly_stats WHERE month = :month")
    fun observeByMonth(month: String): Flow<MonthlyStatsEntity?>

    @Query("SELECT * FROM monthly_stats ORDER BY month DESC LIMIT :limit")
    fun observeRecent(limit: Int = 12): Flow<List<MonthlyStatsEntity>>
}
```

---

## 4. 数据库实例

```kotlin
@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        BudgetEntity::class,
        MonthlyStatsEntity::class
    ],
    version = 1,
    exportSchema = true // 导出 schema JSON，用于迁移测试
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun monthlyStatsDao(): MonthlyStatsDao
}
```

---

## 5. TypeConverter

```kotlin
class Converters {
    // Room 内置支持 Long/String/Double 等基础类型
    // 枚举类通过 String 存储，不需要 TypeConverter
    // 仅在需要复杂类型时添加 Converter
}
```

**说明**: 本设计将所有枚举存储为 `String`、时间存储为 `Long (epoch millis)`、YearMonth 存储为 `String ("2026-03")`，无需自定义 TypeConverter，降低复杂度。

---

## 6. 预填充数据 (Seed Data)

### 6.1 分类预填充

通过 `RoomDatabase.Callback` 在首次创建时填充：

```kotlin
class PrepopulateCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        // 支出分类
        val expenseCategories = listOf(
            // id, name, icon, color, type, parentId, isSystem, sortOrder
            "(1, '餐饮', 'ic_food', '#FF5722', 'EXPENSE', NULL, 1, 1)",
            "(2, '交通', 'ic_transport', '#2196F3', 'EXPENSE', NULL, 1, 2)",
            "(3, '购物', 'ic_shopping', '#E91E63', 'EXPENSE', NULL, 1, 3)",
            "(4, '娱乐', 'ic_entertainment', '#9C27B0', 'EXPENSE', NULL, 1, 4)",
            "(5, '居住', 'ic_housing', '#795548', 'EXPENSE', NULL, 1, 5)",
            "(6, '医疗', 'ic_medical', '#F44336', 'EXPENSE', NULL, 1, 6)",
            "(7, '教育', 'ic_education', '#3F51B5', 'EXPENSE', NULL, 1, 7)",
            "(8, '通讯', 'ic_communication', '#00BCD4', 'EXPENSE', NULL, 1, 8)",
            "(9, '服饰', 'ic_clothing', '#FF9800', 'EXPENSE', NULL, 1, 9)",
            "(10, '其他', 'ic_other', '#607D8B', 'EXPENSE', NULL, 1, 10)",
        )

        // 收入分类
        val incomeCategories = listOf(
            "(11, '工资', 'ic_salary', '#4CAF50', 'INCOME', NULL, 1, 1)",
            "(12, '奖金', 'ic_bonus', '#8BC34A', 'INCOME', NULL, 1, 2)",
            "(13, '兼职', 'ic_parttime', '#CDDC39', 'INCOME', NULL, 1, 3)",
            "(14, '理财', 'ic_investment', '#009688', 'INCOME', NULL, 1, 4)",
            "(15, '红包', 'ic_redpacket', '#F44336', 'INCOME', NULL, 1, 5)",
            "(16, '其他', 'ic_other_income', '#607D8B', 'INCOME', NULL, 1, 6)",
        )

        val allCategories = expenseCategories + incomeCategories
        allCategories.forEach { values ->
            db.execSQL(
                "INSERT INTO categories (id, name, icon, color, type, parentId, isSystem, sortOrder) VALUES $values"
            )
        }
    }
}
```

### 6.2 预填充分类详细映射表

| ID | 名称 | 图标 | 颜色 | 类型 |
|----|------|------|------|------|
| 1 | 餐饮 | ic_food | #FF5722 | EXPENSE |
| 2 | 交通 | ic_transport | #2196F3 | EXPENSE |
| 3 | 购物 | ic_shopping | #E91E63 | EXPENSE |
| 4 | 娱乐 | ic_entertainment | #9C27B0 | EXPENSE |
| 5 | 居住 | ic_housing | #795548 | EXPENSE |
| 6 | 医疗 | ic_medical | #F44336 | EXPENSE |
| 7 | 教育 | ic_education | #3F51B5 | EXPENSE |
| 8 | 通讯 | ic_communication | #00BCD4 | EXPENSE |
| 9 | 服饰 | ic_clothing | #FF9800 | EXPENSE |
| 10 | 其他 | ic_other | #607D8B | EXPENSE |
| 11 | 工资 | ic_salary | #4CAF50 | INCOME |
| 12 | 奖金 | ic_bonus | #8BC34A | INCOME |
| 13 | 兼职 | ic_parttime | #CDDC39 | INCOME |
| 14 | 理财 | ic_investment | #009688 | INCOME |
| 15 | 红包 | ic_redpacket | #F44336 | INCOME |
| 16 | 其他 | ic_other_income | #607D8B | INCOME |

---

## 7. 数据库迁移策略

### 7.1 迁移规范

```kotlin
object Migrations {
    // 示例：v1 → v2 添加字段
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN newField TEXT DEFAULT NULL")
        }
    }

    val ALL: Array<Migration> = arrayOf(
        // MIGRATION_1_2,
        // 按顺序添加
    )
}
```

### 7.2 迁移原则

| 原则 | 说明 |
|------|------|
| **永不 fallbackToDestructiveMigration** | 生产环境绝不丢失用户数据 |
| **向前兼容** | 新增字段用 `DEFAULT` 值，不破坏旧数据 |
| **迁移测试** | 每个 Migration 必须有对应的 `MigrationTest` |
| **Schema 导出** | `exportSchema = true`，schema JSON 提交到 Git |
| **预发布验证** | Release 前在真实数据量设备上验证迁移耗时 |

### 7.3 Schema 导出配置

```kotlin
// build.gradle.kts (:core-data)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

Schema JSON 文件提交到 `core-data/schemas/` 目录。

---

## 8. 性能优化

### 8.1 索引策略

已在 Entity 定义中声明的索引：

| 表 | 索引 | 用途 |
|----|------|------|
| transactions | `date` | 按日期范围查询 |
| transactions | `categoryId` | 按分类筛选 |
| transactions | `type` | 收入/支出筛选 |
| transactions | `status` | 待确认列表 |
| transactions | `syncStatus` | 同步队列查询 |
| transactions | `(date, type)` | 月度统计复合查询 |
| categories | `type` | 按类型筛选分类 |
| categories | `parentId` | 子分类查询 |
| budgets | `month` | 按月查询预算 |
| budgets | `(month, categoryId)` UNIQUE | 防止重复 + 快速定位 |

### 8.2 查询优化

- 列表查询使用 `Flow` 响应式，避免轮询
- 月度统计使用 `monthly_stats` 缓存表，避免每次全量聚合
- 搜索使用 `LIKE` 模糊匹配（数据量 <10 万条时性能足够，后续可升级 FTS）
- 聚合查询仅计算 `status = 'CONFIRMED'` 的记录

### 8.3 数据量预估

| 场景 | 日均记录 | 年记录量 | 数据库大小 |
|------|----------|----------|------------|
| 轻度用户 | 3 条 | ~1,100 | ~500KB |
| 中度用户 | 8 条 | ~3,000 | ~1.5MB |
| 重度用户 | 15 条 | ~5,500 | ~3MB |

结论：Room + SQLite 完全可以胜任，无需分库分表。

---

*文档版本: v1.0 | 最后更新: 2026-03-15*
