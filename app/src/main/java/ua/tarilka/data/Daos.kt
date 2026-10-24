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

    @Query("SELECT * FROM foods WHERE id = :id")
    suspend fun byId(id: Long): Food?

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
    suspend fun delete(entry: DiaryEntry)
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weights ORDER BY day")
    fun all(): Flow<List<WeightEntry>>

    @Query("SELECT * FROM weights ORDER BY day DESC LIMIT 1")
    fun latest(): Flow<WeightEntry?>

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

    /** Drinks logged as food (juice, kefir, coffee…) also count as fluid; 1 g ≈ 1 ml. */
    @Query(
        """SELECT COALESCE(SUM(d.grams), 0) FROM diary d JOIN foods f ON f.id = d.foodId
           WHERE d.day = :day AND f.category = 'drinks'"""
    )
    fun drinksFromDiary(day: Long): Flow<Double>

    @Insert
    suspend fun insert(entry: FluidEntry): Long

    @Query("SELECT * FROM fluids ORDER BY day, createdAt")
    suspend fun allOnce(): List<FluidEntry>

    @Query("DELETE FROM fluids")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(entries: List<FluidEntry>)

    @Delete
    suspend fun delete(entry: FluidEntry)

    @Query("SELECT day, SUM(ml) AS ml FROM fluids WHERE day BETWEEN :from AND :to GROUP BY day")
    fun totals(from: Long, to: Long): Flow<List<DayFluid>>
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
