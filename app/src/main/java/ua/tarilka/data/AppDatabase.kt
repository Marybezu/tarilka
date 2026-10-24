package ua.tarilka.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Food::class, DiaryEntry::class, WeightEntry::class, FluidEntry::class, DayNote::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun foods(): FoodDao
    abstract fun diary(): DiaryDao
    abstract fun weights(): WeightDao
    abstract fun fluids(): FluidDao
    abstract fun notes(): NoteDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tarilka.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** 0.1.x → recipes and day notes. Existing data is kept. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE foods ADD COLUMN recipe TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS notes (day INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(day))")
            }
        }
    }
}
