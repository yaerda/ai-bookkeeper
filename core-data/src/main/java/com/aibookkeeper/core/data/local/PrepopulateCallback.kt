package com.aibookkeeper.core.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

class PrepopulateCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)

        // Expense categories
        val expenseCategories = listOf(
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

        // Income categories
        val incomeCategories = listOf(
            "(11, '工资', 'ic_salary', '#4CAF50', 'INCOME', NULL, 1, 1)",
            "(12, '奖金', 'ic_bonus', '#8BC34A', 'INCOME', NULL, 1, 2)",
            "(13, '兼职', 'ic_parttime', '#CDDC39', 'INCOME', NULL, 1, 3)",
            "(14, '理财', 'ic_investment', '#009688', 'INCOME', NULL, 1, 4)",
            "(15, '红包', 'ic_redpacket', '#F44336', 'INCOME', NULL, 1, 5)",
            "(16, '其他', 'ic_other_income', '#607D8B', 'INCOME', NULL, 1, 6)",
        )

        (expenseCategories + incomeCategories).forEach { values ->
            db.execSQL(
                "INSERT INTO categories (id, name, icon, color, type, parentId, isSystem, sortOrder) VALUES $values"
            )
        }

        PaymentPagePatternSeedData.insertDefaults(db)
    }
}
