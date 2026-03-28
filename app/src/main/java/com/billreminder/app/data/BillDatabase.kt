package com.billreminder.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.billreminder.app.model.Bill

@Database(
    entities = [Bill::class, GeminiCache::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BillDatabase : RoomDatabase() {
    abstract fun billDao(): BillDao
    abstract fun geminiCacheDao(): GeminiCacheDao

    companion object {
        @Volatile private var INSTANCE: BillDatabase? = null

        /**
         * v2 → v3:
         *  - Add `geminiConfidence` column to the `bills` table (default -1.0 for
         *    all existing rows, meaning "not evaluated").
         *  - Create the new `gemini_cache` table that persists Gemini API results
         *    to avoid redundant calls on subsequent syncs.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE bills ADD COLUMN geminiConfidence REAL NOT NULL DEFAULT -1.0"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS gemini_cache (
                        emailId TEXT NOT NULL PRIMARY KEY,
                        isInvoice INTEGER NOT NULL,
                        isReceipt INTEGER NOT NULL,
                        isDuplicate INTEGER NOT NULL,
                        provider TEXT,
                        amount REAL,
                        dueDate TEXT,
                        confidence REAL NOT NULL,
                        reason TEXT,
                        checkedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): BillDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BillDatabase::class.java,
                    "bill_reminder_db"
                )
                .addMigrations(MIGRATION_2_3)
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
