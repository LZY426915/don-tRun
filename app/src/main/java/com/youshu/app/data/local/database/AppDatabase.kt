package com.youshu.app.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.youshu.app.data.local.dao.AiModelConfigDao
import com.youshu.app.data.local.dao.CategoryDao
import com.youshu.app.data.local.dao.ItemDao
import com.youshu.app.data.local.dao.LocationDao
import com.youshu.app.data.local.entity.AiModelConfig
import com.youshu.app.data.local.entity.Category
import com.youshu.app.data.local.entity.Item
import com.youshu.app.data.local.entity.Location

@Database(
    entities = [Item::class, Category::class, Location::class, AiModelConfig::class],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao
    abstract fun categoryDao(): CategoryDao
    abstract fun locationDao(): LocationDao
    abstract fun aiModelConfigDao(): AiModelConfigDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN rating INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN ratedAt INTEGER")
                SeedHelper.normalizeLegacyData(db)
                SeedHelper.ensureSeedData(db)
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN deletedAt INTEGER")
                SeedHelper.normalizeLegacyData(db)
                SeedHelper.ensureSeedData(db)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN imagePaths TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    UPDATE items
                    SET imagePaths = CASE
                        WHEN TRIM(COALESCE(imagePath, '')) = '' THEN ''
                        ELSE imagePath
                    END
                    """.trimIndent()
                )
                SeedHelper.normalizeLegacyData(db)
                SeedHelper.ensureSeedData(db)
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedHelper.createAiModelConfigTable(db)
                SeedHelper.ensureInitialAiModelsV5(db)
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedHelper.addAiModelRoutingColumns(db)
                SeedHelper.normalizeAiModelDefaults(db)
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedHelper.ensureReviewNoteColumn(db)
                SeedHelper.upgradeQwenVisionModel(db)
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedHelper.ensureReviewNoteColumn(db)
                SeedHelper.upgradeQwenVisionModel(db)
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedHelper.ensureWeatherModel(db)
            }
        }

        fun buildDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "youshu.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9
                    )
                    .addCallback(PrepopulateCallback())
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }

    private class PrepopulateCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            SeedHelper.ensureSeedData(db)
            SeedHelper.ensureInitialAiModels(db)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            SeedHelper.normalizeLegacyData(db)
            SeedHelper.ensureSeedData(db)
        }
    }
}

private object SeedHelper {
    private val defaultCategories = listOf("食品", "药品", "日用品", "数码", "衣物", "文具", "工具", "其他")

    private val roomHierarchy = linkedMapOf(
        "厨房" to listOf("冰箱", "橱柜", "台面"),
        "客厅" to listOf("电视柜", "茶几", "书架"),
        "卧室" to listOf("衣柜", "床头柜", "梳妆台"),
        "卫生间" to listOf("洗手台", "淋浴间"),
        "阳台" to listOf("储物柜"),
        "书房" to listOf("书桌", "书柜"),
        "储物间" to listOf("收纳架")
    )

    private val categoryReplacements = listOf(
        "椋熷搧" to "食品",
        "鑽搧" to "药品",
        "鏃ョ敤鍝�" to "日用品",
        "鏁扮爜" to "数码",
        "琛ｇ墿" to "衣物",
        "鏂囧叿" to "文具",
        "宸ュ叿" to "工具",
        "鍏朵粬" to "其他"
    )

    private val locationReplacements = listOf(
        "鎴戠殑瀹�" to "我的家",
        "鍘ㄦ埧" to "厨房",
        "鍐扮" to "冰箱",
        "姗辨煖" to "橱柜",
        "鍙伴潰" to "台面",
        "瀹㈠巺" to "客厅",
        "鐢佃鏌�" to "电视柜",
        "鑼跺嚑" to "茶几",
        "涔︽灦" to "书架",
        "鍗у" to "卧室",
        "琛ｆ煖" to "衣柜",
        "搴婂ご鏌�" to "床头柜",
        "姊冲鍙�" to "梳妆台",
        "鍗敓闂�" to "卫生间",
        "娲楁墜鍙�" to "洗手台",
        "娣嬫荡闂�" to "淋浴间",
        "闃冲彴" to "阳台",
        "鍌ㄧ墿鏌�" to "储物柜",
        "涔︽埧" to "书房",
        "涔︽" to "书桌",
        "涔︽煖" to "书柜",
        "鍌ㄧ墿闂�" to "储物间",
        "鏀剁撼鏋�" to "收纳架"
    )

    fun ensureSeedData(db: SupportSQLiteDatabase) {
        defaultCategories.forEach { ensureCategory(db, it) }

        val homeId = ensureHomeRoot(db)
        roomHierarchy.forEach { (room, children) ->
            val roomId = ensureLocation(db, room, homeId)
            children.forEach { child ->
                ensureLocation(db, child, roomId)
            }
        }
    }

    fun normalizeLegacyData(db: SupportSQLiteDatabase) {
        categoryReplacements.forEach { (legacy, correct) ->
            db.execSQL(
                "UPDATE categories SET name = '${correct.sql()}' WHERE name = '${legacy.sql()}'"
            )
        }
        locationReplacements.forEach { (legacy, correct) ->
            db.execSQL(
                "UPDATE locations SET name = '${correct.sql()}' WHERE name = '${legacy.sql()}'"
            )
        }
        db.execSQL("UPDATE items SET unit = '件' WHERE unit IN ('浠�', '娑?', '')")
    }

    fun createAiModelConfigTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ai_model_configs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                alias TEXT NOT NULL,
                provider TEXT NOT NULL,
                endpoint TEXT NOT NULL,
                apiKey TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    fun addAiModelRoutingColumns(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ai_model_configs ADD COLUMN modelName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE ai_model_configs ADD COLUMN purpose TEXT NOT NULL DEFAULT 'text_search'")
    }

    fun ensureReviewNoteColumn(db: SupportSQLiteDatabase) {
        if (!hasColumn(db, "items", "reviewNote")) {
            db.execSQL("ALTER TABLE items ADD COLUMN reviewNote TEXT NOT NULL DEFAULT ''")
        }
    }

    fun normalizeAiModelDefaults(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE ai_model_configs
            SET alias = 'DeepSeek 文字搜索',
                provider = 'DeepSeek',
                endpoint = 'https://api.deepseek.com',
                modelName = 'deepseek-v4-flash',
                purpose = 'text_search'
            WHERE alias IN ('默认云端模型', 'DeepSeek 文字搜索')
            """.trimIndent()
        )
        db.execSQL(
            """
            UPDATE ai_model_configs
            SET alias = '通义千问图片识别',
                provider = 'Qwen',
                endpoint = 'https://dashscope.aliyuncs.com/compatible-mode/v1',
                modelName = 'qwen3-vl-plus',
                purpose = 'image_recognition'
            WHERE alias IN ('本地模型', '通义千问图片识别')
            """.trimIndent()
        )
    }

    fun upgradeQwenVisionModel(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE ai_model_configs
            SET modelName = 'qwen3-vl-plus',
                provider = 'Qwen',
                endpoint = 'https://dashscope.aliyuncs.com/compatible-mode/v1',
                purpose = 'image_recognition'
            WHERE purpose = 'image_recognition'
                AND provider = 'Qwen'
                AND modelName IN ('', 'qwen-vl-plus')
            """.trimIndent()
        )
    }

    fun ensureInitialAiModels(db: SupportSQLiteDatabase) {
        val count = db.query("SELECT COUNT(*) FROM ai_model_configs").useCursor { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        if (count > 0) return

        val now = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT INTO ai_model_configs (
                alias, provider, endpoint, modelName, purpose, apiKey, createdAt, updatedAt
            )
            VALUES (
                'DeepSeek 文字搜索',
                'DeepSeek',
                'https://api.deepseek.com',
                'deepseek-v4-flash',
                'text_search',
                '',
                $now,
                $now
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO ai_model_configs (
                alias, provider, endpoint, modelName, purpose, apiKey, createdAt, updatedAt
            )
            VALUES (
                '通义千问图片识别',
                'Qwen',
                'https://dashscope.aliyuncs.com/compatible-mode/v1',
                'qwen3-vl-plus',
                'image_recognition',
                '',
                $now,
                $now
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO ai_model_configs (
                alias, provider, endpoint, modelName, purpose, apiKey, createdAt, updatedAt
            )
            VALUES (
                '高德天气',
                '高德',
                'https://restapi.amap.com/v3',
                'weather-live',
                'weather',
                '',
                $now,
                $now
            )
            """.trimIndent()
        )
    }

    fun ensureWeatherModel(db: SupportSQLiteDatabase) {
        val count = db.query(
            "SELECT COUNT(*) FROM ai_model_configs WHERE purpose = 'weather'"
        ).useCursor { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        if (count > 0) return

        val now = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT INTO ai_model_configs (
                alias, provider, endpoint, modelName, purpose, apiKey, createdAt, updatedAt
            )
            VALUES (
                '高德天气',
                '高德',
                'https://restapi.amap.com/v3',
                'weather-live',
                'weather',
                '',
                $now,
                $now
            )
            """.trimIndent()
        )
    }

    fun ensureInitialAiModelsV5(db: SupportSQLiteDatabase) {
        val count = db.query("SELECT COUNT(*) FROM ai_model_configs").useCursor { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        if (count > 0) return

        val now = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT INTO ai_model_configs (alias, provider, endpoint, apiKey, createdAt, updatedAt)
            VALUES ('DeepSeek 文字搜索', 'DeepSeek', 'https://api.deepseek.com', '', $now, $now)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO ai_model_configs (alias, provider, endpoint, apiKey, createdAt, updatedAt)
            VALUES ('通义千问图片识别', 'Qwen', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '', $now, $now)
            """.trimIndent()
        )
    }

    private fun ensureCategory(db: SupportSQLiteDatabase, name: String) {
        if (findCategoryId(db, name) == null) {
            db.execSQL("INSERT INTO categories (name, icon) VALUES ('${name.sql()}', '')")
        }
    }

    private fun ensureHomeRoot(db: SupportSQLiteDatabase): Long {
        val canonicalName = "我的家"
        val legacyName = "鎴戠殑瀹�"

        db.execSQL(
            "UPDATE locations SET name = '${canonicalName.sql()}' " +
                "WHERE parentId IS NULL AND name IN ('${canonicalName.sql()}', '${legacyName.sql()}')"
        )

        val rootIds = mutableListOf<Long>()
        db.query(
            "SELECT id FROM locations " +
                "WHERE parentId IS NULL AND name = '${canonicalName.sql()}' ORDER BY id ASC"
        ).useCursor { cursor ->
            while (cursor.moveToNext()) {
                rootIds += cursor.getLong(0)
            }
        }

        if (rootIds.isEmpty()) {
            db.execSQL("INSERT INTO locations (name, parentId) VALUES ('${canonicalName.sql()}', NULL)")
            return requireNotNull(findLocationId(db, canonicalName, null))
        }

        val keeperId = rootIds.first()
        rootIds.drop(1).forEach { duplicateId ->
            db.execSQL("UPDATE locations SET parentId = $keeperId WHERE parentId = $duplicateId")
            db.execSQL("UPDATE items SET locationId = $keeperId WHERE locationId = $duplicateId")
            db.execSQL("DELETE FROM locations WHERE id = $duplicateId")
        }

        return keeperId
    }

    private fun ensureLocation(db: SupportSQLiteDatabase, name: String, parentId: Long?): Long {
        findLocationId(db, name, parentId)?.let { return it }

        val parentSql = parentId?.toString() ?: "NULL"
        db.execSQL(
            "INSERT INTO locations (name, parentId) VALUES ('${name.sql()}', $parentSql)"
        )
        return requireNotNull(findLocationId(db, name, parentId))
    }

    private fun findCategoryId(db: SupportSQLiteDatabase, name: String): Long? {
        return db.query(
            "SELECT id FROM categories WHERE name = '${name.sql()}' LIMIT 1"
        ).useCursor { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun findLocationId(
        db: SupportSQLiteDatabase,
        name: String,
        parentId: Long?
    ): Long? {
        val parentClause = parentId?.let { "parentId = $it" } ?: "parentId IS NULL"
        return db.query(
            "SELECT id FROM locations WHERE name = '${name.sql()}' AND $parentClause LIMIT 1"
        ).useCursor { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
        return db.query("PRAGMA table_info(${table.sql()})").useCursor { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == column) return@useCursor true
            }
            false
        }
    }

    private fun String.sql(): String = replace("'", "''")
}

private inline fun <T> android.database.Cursor.useCursor(block: (android.database.Cursor) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}
