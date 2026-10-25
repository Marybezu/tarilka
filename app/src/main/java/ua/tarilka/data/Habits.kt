package ua.tarilka.data

import java.time.Instant
import java.time.ZoneId

/**
 * Eating patterns over a period, counted from the diary alone: nothing extra to type.
 * Norms: WHO (free sugars under 10 % of energy, ideally 5 %; at least 400 g of vegetables
 * and fruit a day, potatoes not counted); night eating as in the night eating syndrome
 * criterion (25 % or more of the day's food late in the evening).
 */
data class Habits(
    /** Days in the period with any food logged. */
    val days: Int,
    val periodDays: Int,
    val kcalAvg: Double,
    val proteinAvg: Double,
    val fiberAvg: Double,
    /** Share of calories from sweets and sugary drinks, %. */
    val sweetsPct: Double,
    val sweetFreeDays: Int,
    /** Vegetables and fruit per logged day, g. */
    val vegFruitG: Double,
    /** Share of calories logged between 22:00 and 04:00, %. */
    val latePct: Double,
    /** Standard drinks (10 g of alcohol) per week. */
    val alcoholPerWeek: Double,
    val fastFoodDays: Int,
) {
    enum class Level { GOOD, OK, WATCH }

    val sweetsLevel get() = level(sweetsPct < 5, sweetsPct <= 10)
    val vegLevel get() = level(vegFruitG >= 400, vegFruitG >= 200)
    val lateLevel get() = level(latePct < 10, latePct < 25)
    val alcoholLevel get() = level(alcoholPerWeek == 0.0, alcoholPerWeek <= 7)
    val fastFoodLevel get() = level(fastFoodDays == 0, fastFoodDays * 7.0 / periodDays <= 1)

    private fun level(good: Boolean, ok: Boolean) = if (good) Level.GOOD else if (ok) Level.OK else Level.WATCH

    companion object {
        /** Juices count as free sugars for the WHO; tomato juice and coconut water do not. */
        val SUGARY_DRINKS = setOf("dr03", "dr04", "dr05", "dr10", "dr12", "dr17", "dr18", "dr20", "dr21", "dr26", "dr27", "dr29")
        val STARCHY = setOf("v01", "v02", "v03", "v04", "v05", "v32", "v33")
        /** Grams of alcohol per 100 g (USDA). */
        val ALCOHOL_G = mapOf("dr06" to 3.9, "dr07" to 10.6, "dr08" to 10.3, "dr09" to 33.4, "dr23" to 3.1, "dr24" to 15.3, "dr25" to 21.7)

        /**
         * Self-help techniques from cognitive behavioural approaches to overeating (NICE NG69 guided self-help).
         * The AI picks from this list instead of inventing advice.
         */
        val TECHNIQUES = listOf(
            "Regular eating: 3 meals and 1–2 planned snacks, no gaps longer than about 4 hours; this is the strongest help against evening and night overeating.",
            "Avoid strict restriction: very tight dieting often leads to a restrict-then-overeat cycle.",
            "When the urge to eat is not hunger, wait 10–15 minutes and do something else (a short walk, a call, a glass of water); the urge usually passes.",
            "Name the state before eating: hungry, tired, bored, sad, angry or stressed? Often it is not hunger.",
            "Eat sitting at a table, from a plate, without a screen.",
            "Sweets: plan a portion instead of a total ban, keep them out of sight, swap sugary drinks for water.",
            "Evening: plan an evening snack and include protein at dinner.",
            "Alcohol: alcohol-free days, a glass of water between drinks.",
            "After a slip: one evening does not cancel the week; guilt itself often triggers the next episode.",
        )

        fun isSweet(f: Food) = f.category == "sweets" || f.code in SUGARY_DRINKS
        fun isVegFruit(f: Food) = (f.category == "veg" || f.category == "fruit") && f.code !in STARCHY

        fun of(entries: List<DiaryEntry>, foods: Map<Long, Food>, periodDays: Int, zone: ZoneId = ZoneId.systemDefault()): Habits {
            fun food(e: DiaryEntry) = e.foodId?.let(foods::get)
            fun sweet(e: DiaryEntry) = food(e)?.let(::isSweet) == true
            val byDay = entries.groupBy { it.day }
            val days = byDay.size
            val kcal = entries.sumOf { it.kcal }
            val late = entries.filter {
                val h = Instant.ofEpochMilli(it.createdAt).atZone(zone).hour
                h >= 22 || h < 4
            }.sumOf { it.kcal }
            val alcohol = entries.sumOf { e -> food(e)?.code?.let(ALCOHOL_G::get)?.let { it * e.grams / 100 } ?: 0.0 } / 10
            fun pct(part: Double) = if (kcal > 0) part / kcal * 100 else 0.0
            fun perDay(total: Double) = if (days > 0) total / days else 0.0
            return Habits(
                days = days,
                periodDays = periodDays,
                kcalAvg = perDay(kcal),
                proteinAvg = perDay(entries.sumOf { it.protein }),
                fiberAvg = perDay(entries.sumOf { it.fiber }),
                sweetsPct = pct(entries.filter(::sweet).sumOf { it.kcal }),
                sweetFreeDays = byDay.values.count { list -> list.none(::sweet) },
                vegFruitG = perDay(entries.filter { food(it)?.let(::isVegFruit) == true }.sumOf { it.grams }),
                latePct = pct(late),
                alcoholPerWeek = alcohol * 7 / periodDays,
                fastFoodDays = byDay.values.count { list -> list.any { food(it)?.category == "fastfood" } },
            )
        }
    }
}
