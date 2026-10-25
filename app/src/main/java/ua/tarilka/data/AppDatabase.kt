package ua.tarilka.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Food::class, DiaryEntry::class, WeightEntry::class, FluidEntry::class, DayNote::class, SleepEntry::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun foods(): FoodDao
    abstract fun diary(): DiaryDao
    abstract fun weights(): WeightDao
    abstract fun fluids(): FluidDao
    abstract fun notes(): NoteDao
    abstract fun sleep(): SleepDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tarilka.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** "Why I eat" on diary entries, and sleep. Existing data is kept. */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diary ADD COLUMN reason INTEGER")
                db.execSQL("ALTER TABLE diary ADD COLUMN hunger INTEGER")
                db.execSQL("ALTER TABLE diary ADD COLUMN hardToStop INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS sleep (day INTEGER NOT NULL, hours REAL NOT NULL, quality INTEGER, PRIMARY KEY(day))")
            }
        }

        /** 0.1.x → recipes and day notes. Existing data is kept. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE foods ADD COLUMN recipe TEXT")
                // The very first builds had no fluids table yet; create it if missing.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS fluids (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "day INTEGER NOT NULL, ml REAL NOT NULL, createdAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_fluids_day ON fluids(day)")
                db.execSQL("CREATE TABLE IF NOT EXISTS notes (day INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(day))")
            }
        }
    }
}
