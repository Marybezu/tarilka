package ua.tarilka.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Food::class, DiaryEntry::class, WeightEntry::class, FluidEntry::class, DayNote::class, SleepEntry::class, BowelEntry::class, WorkoutEntry::class, PeriodEntry::class, PeriodDay::class, WaistEntry::class],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun foods(): FoodDao
    abstract fun diary(): DiaryDao
    abstract fun weights(): WeightDao
    abstract fun fluids(): FluidDao
    abstract fun notes(): NoteDao
    abstract fun sleep(): SleepDao
    abstract fun bowel(): BowelDao
    abstract fun workouts(): WorkoutDao
    abstract fun cycle(): CycleDao
    abstract fun waist(): WaistDao

    companion object {
        const val NAME = "tarilka.db"
        const val VERSION = 7

        /** Plain when [rawKey] is null; otherwise encrypted with SQLCipher (app protection). */
        fun create(context: Context, rawKey: ByteArray? = null): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .apply {
                    if (rawKey != null) {
                        System.loadLibrary("sqlcipher")
                        openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(rawKey))
                    }
                }
                .build()

        /** Waist circumference. Existing data is kept. */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `waist` (`day` INTEGER NOT NULL, `cm` REAL NOT NULL, PRIMARY KEY(`day`))")
            }
        }

        /** Time of weighing. Existing data is kept. */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE weights ADD COLUMN minutes INTEGER")
            }
        }

        /** Menstrual cycle (optional). Existing data is kept. */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS periods (start INTEGER NOT NULL, end_day INTEGER, PRIMARY KEY(start))")
                db.execSQL("CREATE TABLE IF NOT EXISTS period_days (day INTEGER NOT NULL, flow INTEGER, pain INTEGER, PRIMARY KEY(day))")
            }
        }

        /** Bowel movements and workouts (both optional). Existing data is kept. */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS bowel (day INTEGER NOT NULL, count INTEGER NOT NULL, form INTEGER, PRIMARY KEY(day))")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS workouts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, day INTEGER NOT NULL, " +
                        "type INTEGER NOT NULL, minutes INTEGER NOT NULL, intensity INTEGER NOT NULL, createdAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workouts_day ON workouts(day)")
            }
        }

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
