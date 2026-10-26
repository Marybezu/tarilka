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
    /** Optional "why I eat": see [Reasons]. */
    val reason: Int? = null,
    /** Hunger before eating, 1 (not hungry) … 5 (very hungry). */
    val hunger: Int? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val hardToStop: Boolean = false,
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
    /** Time of weighing, minutes after midnight (optional). */
    val minutes: Int? = null,
)

/** Waist circumference (optional), cm: adds to BMI what it cannot show. */
@Entity(tableName = "waist")
data class WaistEntry(
    @PrimaryKey val day: Long,
    val cm: Double,
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

/** Hours slept, recorded for the day you woke up. */
@Entity(tableName = "sleep")
data class SleepEntry(
    @PrimaryKey val day: Long,
    val hours: Double,
    /** 0 poor, 1 okay, 2 good */
    val quality: Int? = null,
)

/** Bowel movements for a day (optional): how many times, and the form in plain words. */
@Entity(tableName = "bowel")
data class BowelEntry(
    @PrimaryKey val day: Long,
    val count: Int,
    /** Bristol scale in three words: 0 hard (types 1–2), 1 normal (3–5), 2 loose (6–7). */
    val form: Int? = null,
)

/** A workout typed in by hand (optional). */
@Entity(tableName = "workouts", indices = [Index("day")])
data class WorkoutEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    /** See [Workouts.TYPES]. */
    val type: Int,
    val minutes: Int,
    /** 0 light, 1 moderate, 2 vigorous */
    val intensity: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

object Workouts {
    /** walking, running, cycling, strength, yoga/stretching, swimming, other */
    val TYPES = listOf("walking", "running", "cycling", "strength", "yoga", "swimming", "other")
    const val STRENGTH = 3

    /** MET by type and intensity (Compendium of Physical Activities, rounded). */
    private val MET = listOf(
        listOf(2.8, 3.5, 5.0), listOf(7.0, 9.8, 11.5), listOf(4.0, 6.8, 10.0), listOf(3.5, 5.0, 6.0),
        listOf(2.5, 3.0, 4.0), listOf(5.0, 7.0, 9.8), listOf(3.0, 4.5, 7.0),
    )

    /** Rough energy use, shown for information only: never added to the day's food budget. */
    fun kcal(w: WorkoutEntry, weightKg: Double): Double =
        (MET.getOrNull(w.type) ?: MET.last()).getOrElse(w.intensity) { 4.5 } * weightKg * w.minutes / 60.0

    /** Minutes that count toward the WHO 150–300 a week: moderate ×1, vigorous ×2, light not counted. */
    fun whoMinutes(w: WorkoutEntry): Int = when (w.intensity) { 1 -> w.minutes; 2 -> w.minutes * 2; else -> 0 }
}

object Reasons {
    const val HUNGER = 0
    const val STRESS = 1
    const val BOREDOM = 2
    const val COMPANY = 3
    const val HABIT = 4
    val ALL = listOf(HUNGER, STRESS, BOREDOM, COMPANY, HABIT)
    val EN = listOf("hunger", "stress", "boredom", "company", "habit")
}

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
