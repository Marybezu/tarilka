package ua.tarilka.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

/** A food from the database. All nutrient values are per 100 g. */
@Entity(tableName = "foods", indices = [Index("searchKey"), Index(value = ["code"], unique = true)])
data class Food(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable code for foods that ship with the app (null for user-created foods). */
    val code: String? = null,
    val category: String,
    val nameUk: String,
    val nameEn: String,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val fiber: Double,
    val portionG: Double? = null,
    val portionUk: String? = null,
    val portionEn: String? = null,
    /** USDA, LABEL, RECIPE, CUSTOM or AI. */
    val source: String,
    val favorite: Boolean = false,
    val searchKey: String = buildSearchKey(nameUk, nameEn),
    /** For the user's own dishes: {"items":[{"id":1,"g":200}],"cooked":850} */
    val recipe: String? = null,
) {
    fun name(): String = localized(nameUk, nameEn)
    fun portionName(): String? = if (isUkrainian()) portionUk else portionEn
    val isCustom: Boolean get() = code == null

    companion object {
        fun buildSearchKey(uk: String, en: String) =
            "$uk $en".lowercase(Locale.ROOT).replace('ʼ', '\'').replace('’', '\'')
    }
}

/** One eaten item. Nutrients are stored for the eaten amount so history never changes. */
@Entity(tableName = "diary", indices = [Index("day")])
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** LocalDate.toEpochDay() */
    val day: Long,
    /** 0 breakfast, 1 lunch, 2 dinner, 3 snack */
    val meal: Int,
    val foodId: Long?,
    val nameUk: String,
    val nameEn: String,
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val fiber: Double,
    val fromAi: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun name(): String = localized(nameUk, nameEn)

    companion object {
        fun of(food: Food, grams: Double, day: Long, meal: Int, fromAi: Boolean = false): DiaryEntry {
            val k = grams / 100.0
            return DiaryEntry(
                day = day, meal = meal, foodId = food.id,
                nameUk = food.nameUk, nameEn = food.nameEn, grams = grams,
                kcal = food.kcal * k, protein = food.protein * k, fat = food.fat * k,
                carbs = food.carbs * k, fiber = food.fiber * k, fromAi = fromAi,
            )
        }
    }
}

@Entity(tableName = "weights")
data class WeightEntry(
    @PrimaryKey val day: Long,
    val kg: Double,
)

/** Water and other drinks without calories. */
@Entity(tableName = "fluids", indices = [Index("day")])
data class FluidEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val ml: Double,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A short free-text note for a day (how you felt, hunger, mood…). */
@Entity(tableName = "notes")
data class DayNote(
    @PrimaryKey val day: Long,
    val text: String,
)

data class DayFluid(val day: Long, val ml: Double)

/** Sum of nutrients for one day. */
data class DayTotal(
    val day: Long,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val fiber: Double,
)

data class Totals(
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val fat: Double = 0.0,
    val carbs: Double = 0.0,
    val fiber: Double = 0.0,
) {
    companion object {
        fun of(entries: List<DiaryEntry>) = Totals(
            kcal = entries.sumOf { it.kcal },
            protein = entries.sumOf { it.protein },
            fat = entries.sumOf { it.fat },
            carbs = entries.sumOf { it.carbs },
            fiber = entries.sumOf { it.fiber },
        )
    }
}

fun isUkrainian(): Boolean = Locale.getDefault().language == "uk"
fun localized(uk: String, en: String): String = if (isUkrainian()) uk else en
