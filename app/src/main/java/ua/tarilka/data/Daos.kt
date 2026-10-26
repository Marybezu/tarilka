package ua.tarilka.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodDao {
    @Query(
        """SELECT * FROM foods WHERE searchKey LIKE '%' || :q || '%'
           ORDER BY favorite DESC, (code IS NULL) DESC,
                    (searchKey LIKE :q || '%') DESC, length(nameUk) LIMIT 80"""
    )
    fun search(q: String): Flow<List<Food>>

    @Query("SELECT * FROM foods ORDER BY category, nameUk")
    fun all(): Flow<List<Food>>

    @Query("SELECT * FROM foods")
    suspend fun allOnce(): List<Food>

    @Query(
        """SELECT f.* FROM foods f JOIN
           (SELECT foodId, MAX(createdAt) AS lastUsed FROM diary WHERE foodId IS NOT NULL GROUP BY foodId) d
           ON f.id = d.foodId ORDER BY d.lastUsed DESC LIMIT 15"""
    )
    fun recent(): Flow<List<Food>>

    @Query("SELECT id, code, favorite FROM foods WHERE code IS NOT NULL")
    suspend fun codes(): List<CodeId>

    @Upsert
    suspend fun upsertAll(foods: List<Food>)

    @Query("SELECT * FROM foods WHERE code IS NULL")
    suspend fun customOnce(): List<Food>

    @Query("DELETE FROM foods WHERE code IS NULL")
    suspend fun deleteCustom()

    @Query("SELECT code FROM foods WHERE code IS NOT NULL AND favorite = 1")
    suspend fun favoriteCodes(): List<String>

    @Query("UPDATE foods SET favorite = (code IN (:codes)) WHERE code IS NOT NULL")
    suspend fun setFavorites(codes: List<String>)

    @Insert
    suspend fun insert(food: Food): Long

    @Update
    suspend fun update(food: Food)

    @Delete
    suspend fun delete(food: Food)
}

data class CodeId(val id: Long, val code: String, val favorite: Boolean)

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary WHERE day = :day ORDER BY meal, createdAt")
    fun forDay(day: Long): Flow<List<DiaryEntry>>

    @Query(
        """SELECT day, SUM(kcal) AS kcal, SUM(protein) AS protein, SUM(fat) AS fat,
                  SUM(carbs) AS carbs, SUM(fiber) AS fiber
           FROM diary WHERE day BETWEEN :from AND :to GROUP BY day ORDER BY day"""
    )
    fun totals(from: Long, to: Long): Flow<List<DayTotal>>

    @Insert
    suspend fun insert(entry: DiaryEntry): Long

    @Insert
    suspend fun insertAll(entries: List<DiaryEntry>)

    @Query("SELECT * FROM diary ORDER BY day, meal, createdAt")
    suspend fun allOnce(): List<DiaryEntry>

    @Query("DELETE FROM diary")
    suspend fun deleteAll()

    @Update
    suspend fun update(entry: DiaryEntry)

    @Delete
    suspend fun deleteMany(entries: List<DiaryEntry>)

    @Query("SELECT COUNT(*) FROM diary WHERE day = :day AND meal = :meal")
    suspend fun countMeal(day: Long, meal: Int): Int

    @Query("UPDATE diary SET reason = :reason, hunger = :hunger, hardToStop = :hardToStop WHERE id IN (:ids)")
    suspend fun setReason(ids: List<Long>, reason: Int?, hunger: Int?, hardToStop: Boolean)

    @Query("SELECT * FROM diary WHERE day BETWEEN :from AND :to ORDER BY day, createdAt")
    suspend fun range(from: Long, to: Long): List<DiaryEntry>

    @Query("SELECT MIN(day) FROM diary")
    suspend fun firstDay(): Long?

    @Query("SELECT * FROM diary WHERE day BETWEEN :from AND :to ORDER BY day, createdAt")
    fun rangeFlow(from: Long, to: Long): Flow<List<DiaryEntry>>

    @Delete
    suspend fun delete(entry: DiaryEntry)
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weights ORDER BY day")
    fun all(): Flow<List<WeightEntry>>

    @Upsert
    suspend fun upsert(entry: WeightEntry)

    @Query("SELECT * FROM weights ORDER BY day")
    suspend fun allOnce(): List<WeightEntry>

    @Query("DELETE FROM weights")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(entry: WeightEntry)
}

@Dao
interface FluidDao {
    @Query("SELECT * FROM fluids WHERE day = :day ORDER BY createdAt")
    fun forDay(day: Long): Flow<List<FluidEntry>>

    /**
     * Drinks logged as food also count as fluid (1 g ≈ 1 ml): non-alcoholic drinks
     * and milk drinks; not spirits, beer or wine, and not dry powders.
     */
    @Query(
        """SELECT COALESCE(SUM(CASE WHEN f.code IN (:soups) THEN d.grams * 0.8 ELSE d.grams END), 0) FROM diary d JOIN foods f ON f.id = d.foodId
           WHERE d.day = :day AND f.kcal < 150 AND (
             (f.category = 'drinks' AND f.code NOT IN (:alcohol)) OR f.code IN (:milkDrinks) OR f.code IN (:soups))"""
    )
    fun drinksFromDiary(
        day: Long,
        alcohol: List<String>,
        milkDrinks: List<String>,
        soups: List<String>,
    ): Flow<Double>

    /** Drinks logged as food, per day (same rules as [drinksFromDiary]). */
    @Query(
        """SELECT d.day AS day, SUM(CASE WHEN f.code IN (:soups) THEN d.grams * 0.8 ELSE d.grams END) AS ml FROM diary d JOIN foods f ON f.id = d.foodId
           WHERE d.day BETWEEN :from AND :to AND f.kcal < 150 AND (
             (f.category = 'drinks' AND f.code NOT IN (:alcohol)) OR f.code IN (:milkDrinks) OR f.code IN (:soups))
           GROUP BY d.day"""
    )
    fun drinksFromDiaryRange(from: Long, to: Long, alcohol: List<String>, milkDrinks: List<String>, soups: List<String>): Flow<List<DayFluid>>

    @Insert
    suspend fun insert(entry: FluidEntry): Long

    @Query("SELECT COALESCE(SUM(ml), 0) FROM fluids WHERE day = :day")
    suspend fun mlOnce(day: Long): Double

    @Query("SELECT * FROM fluids ORDER BY day, createdAt")
    suspend fun allOnce(): List<FluidEntry>

    @Query("DELETE FROM fluids")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(entries: List<FluidEntry>)

    @Delete
    suspend fun delete(entry: FluidEntry)

    @Delete
    suspend fun deleteMany(entries: List<FluidEntry>)

    @Query("SELECT day, SUM(ml) AS ml FROM fluids WHERE day BETWEEN :from AND :to GROUP BY day")
    fun totals(from: Long, to: Long): Flow<List<DayFluid>>

    @Query("SELECT day, SUM(ml) AS ml FROM fluids WHERE day BETWEEN :from AND :to GROUP BY day")
    suspend fun totalsOnce(from: Long, to: Long): List<DayFluid>
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE day = :day")
    fun forDay(day: Long): Flow<DayNote?>

    @Query("SELECT * FROM notes ORDER BY day")
    fun all(): Flow<List<DayNote>>

    @Query("SELECT * FROM notes ORDER BY day")
    suspend fun allOnce(): List<DayNote>

    @Upsert
    suspend fun upsert(note: DayNote)

    @Query("DELETE FROM notes WHERE day = :day")
    suspend fun delete(day: Long)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()
}

@Dao
interface SleepDao {
    @Query("SELECT * FROM sleep WHERE day = :day")
    fun forDay(day: Long): Flow<SleepEntry?>

    @Query("SELECT * FROM sleep WHERE day BETWEEN :from AND :to ORDER BY day")
    fun rangeFlow(from: Long, to: Long): Flow<List<SleepEntry>>

    @Query("SELECT * FROM sleep WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun range(from: Long, to: Long): List<SleepEntry>

    @Query("SELECT * FROM sleep ORDER BY day")
    suspend fun allOnce(): List<SleepEntry>

    @Upsert
    suspend fun upsert(entry: SleepEntry)

    @Query("DELETE FROM sleep WHERE day = :day")
    suspend fun delete(day: Long)

    @Query("DELETE FROM sleep")
    suspend fun deleteAll()
}

@Dao
interface BowelDao {
    @Query("SELECT * FROM bowel WHERE day = :day")
    fun forDay(day: Long): Flow<BowelEntry?>

    @Query("SELECT * FROM bowel WHERE day BETWEEN :from AND :to ORDER BY day")
    fun rangeFlow(from: Long, to: Long): Flow<List<BowelEntry>>

    @Query("SELECT * FROM bowel WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun range(from: Long, to: Long): List<BowelEntry>

    @Query("SELECT * FROM bowel ORDER BY day")
    suspend fun allOnce(): List<BowelEntry>

    @Upsert
    suspend fun upsert(entry: BowelEntry)

    @Query("DELETE FROM bowel WHERE day = :day")
    suspend fun delete(day: Long)

    @Query("DELETE FROM bowel")
    suspend fun deleteAll()
}

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts WHERE day = :day ORDER BY createdAt")
    fun forDay(day: Long): Flow<List<WorkoutEntry>>

    @Query("SELECT * FROM workouts WHERE day BETWEEN :from AND :to ORDER BY day")
    fun rangeFlow(from: Long, to: Long): Flow<List<WorkoutEntry>>

    @Query("SELECT * FROM workouts WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun range(from: Long, to: Long): List<WorkoutEntry>

    @Query("SELECT * FROM workouts ORDER BY day, createdAt")
    suspend fun allOnce(): List<WorkoutEntry>

    @Insert
    suspend fun insert(entry: WorkoutEntry): Long

    @Insert
    suspend fun insertAll(entries: List<WorkoutEntry>)

    @Delete
    suspend fun delete(entry: WorkoutEntry)

    @Query("DELETE FROM workouts")
    suspend fun deleteAll()
}

@Dao
interface CycleDao {
    @Query("SELECT * FROM periods ORDER BY start")
    fun periods(): Flow<List<PeriodEntry>>

    @Query("SELECT * FROM periods ORDER BY start")
    suspend fun periodsOnce(): List<PeriodEntry>

    @Query("SELECT * FROM period_days ORDER BY day")
    fun days(): Flow<List<PeriodDay>>

    @Query("SELECT * FROM period_days ORDER BY day")
    suspend fun daysOnce(): List<PeriodDay>

    @Upsert
    suspend fun upsert(p: PeriodEntry)

    @Upsert
    suspend fun upsertDay(d: PeriodDay)

    @Query("DELETE FROM periods WHERE start = :start")
    suspend fun delete(start: Long)

    @Query("DELETE FROM period_days WHERE day BETWEEN :from AND :to")
    suspend fun deleteDays(from: Long, to: Long)

    @Query("DELETE FROM periods")
    suspend fun deleteAll()

    @Query("DELETE FROM period_days")
    suspend fun deleteAllDays()
}

object FluidRules {
    val ALCOHOL = listOf("dr06", "dr07", "dr08", "dr09", "dr23", "dr24", "dr25")
    val MILK_DRINKS = listOf(
        "d01", "d02", "d03", "d04", "d05", "d06", "d21", "d41", "d51", "d52", "d53", "d54", "d58", "d61",
        "l15", "n20",
    )
    /** Soups and borscht: about 80 % of their weight is water. */
    val SOUPS = listOf("u01", "u02", "u03", "u04", "u05", "u06", "u07", "u32", "u33", "u34", "u35", "u36", "u37", "u73", "u74", "u86", "u87")
}
