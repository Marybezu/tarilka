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
    /** Average hours of sleep on days with a record, or null. */
    val sleepAvg: Double? = null,
    /** Meals with a "why I eat" answer; the next three count only those. */
    val reasonMeals: Int = 0,
    /** Meals eaten for another reason than hunger, by reason. */
    val notHunger: Map<Int, Int> = emptyMap(),
    val hardToStop: Int = 0,
    /** Days with any alcohol. */
    val alcoholDays: Int = 0,
    /** Days with 6 or more standard drinks (WHO: heavy episodic drinking, 60 g of alcohol). */
    val heavyDrinkDays: Int = 0,
    /** Days with 400 g or more of vegetables and fruit. */
    val vegGoodDays: Int = 0,
    /** Days with a quarter or more of calories after 22:00. */
    val lateDays: Int = 0,
    val sleepNights: Int = 0,
    /** Nights under 6 hours. */
    val shortNights: Int = 0,
    /** Days with a bowel record, days with a movement, hard and loose days. */
    val bowelDays: Int = 0,
    val bowelMoveDays: Int = 0,
    val bowelHard: Int = 0,
    val bowelLoose: Int = 0,
    /** WHO activity minutes a week (moderate ×1, vigorous ×2) and strength days a week, from typed-in workouts. */
    val workoutCount: Int = 0,
    val activeMinPerWeek: Double = 0.0,
    val strengthPerWeek: Double = 0.0,
    /** False when the person switched off meal times: no late-eating figures then. */
    val timesTracked: Boolean = true,
    /** The period before, for trend arrows; set by the caller. */
    val prev: Habits? = null,
) {
    val notHungerTotal: Int get() = notHunger.values.sum()
    enum class Level { GOOD, OK, WATCH }

    val sweetsLevel get() = level(sweetsPct < 5, sweetsPct <= 10)
    val vegLevel get() = level(vegFruitG >= 400, vegFruitG >= 200)
    val lateLevel get() = level(latePct < 10, latePct < 25)
    /**
     * There is no safe amount (WHO 2023), so any alcohol is amber. Orange: more than 7 drinks a week,
     * alcohol on 4 or more days a week, or any day with 6 or more drinks.
     */
    /**
     * Days a weekly rate is based on: the logged days (at least a week, at most the period).
     * A year with 30 logged days must not dilute 14 drinks a week into 1.
     */
    val span: Int get() = maxOf(days, 7).coerceAtMost(periodDays)
    fun perWeek(count: Int) = count * 7.0 / span

    val alcoholLevel get() = when {
        alcoholPerWeek == 0.0 -> Level.GOOD
        alcoholPerWeek > 7 || perWeek(alcoholDays) >= 4 || heavyDrinkDays > 0 -> Level.WATCH
        else -> Level.OK
    }
    val fastFoodLevel get() = level(fastFoodDays == 0, perWeek(fastFoodDays) <= 1)
    /** 7–9 h is the adult range (AASM); regularly much longer sleep is worth a look too, but never orange. */
    val sleepLevel get() = sleepAvg?.let { if (it > 9.5) Level.OK else level(it >= 7, it >= 6) } ?: Level.OK
    /** Hard-to-stop episodes twice a week or more are worth attention; eating for other reasons now and then is normal. */
    val reasonsLevel get() = if (perWeek(hardToStop) >= 2) Level.WATCH else level(notHungerTotal == 0 && hardToStop == 0, true)

    /** Under 3 movements a week is constipation by the usual definition; loose stools on several days are worth noticing too. */
    val bowelLevel get() = when {
        bowelDays == 0 -> Level.OK
        bowelMoveDays * 7.0 / bowelDays < 3 || bowelLoose * 7.0 / bowelDays >= 3 -> Level.WATCH
        bowelHard > 0 || bowelLoose > 0 -> Level.OK
        else -> Level.GOOD
    }
    /** WHO: 150–300 minutes of moderate activity a week (or half as much vigorous). */
    val activityLevel get() = level(activeMinPerWeek >= 150, activeMinPerWeek >= 75)

    /** Hours of sleep, % of calories, drinks a week…: compare with the period before. +1 more, −1 less, 0 about the same or unknown. */
    fun trend(value: (Habits) -> Double?, minDiff: Double): Int {
        val p = prev?.takeIf { it.days >= 2 } ?: return 0
        val a = value(this) ?: return 0
        val b = value(p) ?: return 0
        return if (a - b >= minDiff) 1 else if (b - a >= minDiff) -1 else 0
    }

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

        /** WHO brief intervention for alcohol: what people can do on their own. */
        val ALCOHOL_TECHNIQUES = listOf(
            "Decide in advance how much and on which days; write it down.",
            "At least 2–3 alcohol-free days every week.",
            "Drink slowly, alternate each drink with a glass of water, never on an empty stomach.",
            "Smaller glasses; alcohol-free beer or wine, or sparkling water with lemon.",
            "Notice what pushes you to drink (stress, company, evenings) and plan something else for that moment.",
            "Tell someone close about your goal; it makes it easier to keep.",
        )

        /** In the sweets category but without (much) sugar: chips, popcorn, pretzels, cocoa, unsweetened muesli, protein bars. */
        val NOT_SUGARY = setOf("c12", "c13", "c19", "c31", "c32", "c34", "c43", "c44")

        fun isSweet(f: Food) = (f.category == "sweets" && f.code !in NOT_SUGARY) || f.code in SUGARY_DRINKS
        fun isVegFruit(f: Food) = (f.category == "veg" || f.category == "fruit") && f.code !in STARCHY

        fun of(
            entries: List<DiaryEntry>,
            foods: Map<Long, Food>,
            periodDays: Int,
            zone: ZoneId = ZoneId.systemDefault(),
            sleep: Map<Long, Double> = emptyMap(),
            bowel: List<BowelEntry> = emptyList(),
            workouts: List<WorkoutEntry> = emptyList(),
            withTimes: Boolean = true,
        ): Habits {
            fun food(e: DiaryEntry) = e.foodId?.let(foods::get)
            fun sweet(e: DiaryEntry) = food(e)?.let(::isSweet) == true
            val byDay = entries.groupBy { it.day }
            val days = byDay.size
            val kcal = entries.sumOf { it.kcal }
            fun isLate(e: DiaryEntry) = withTimes && Instant.ofEpochMilli(e.createdAt).atZone(zone).hour.let { it >= 22 || it < 4 }
            val late = entries.filter(::isLate).sumOf { it.kcal }
            fun alcoholG(e: DiaryEntry) = food(e)?.code?.let(ALCOHOL_G::get)?.let { it * e.grams / 100 } ?: 0.0
            val alcohol = entries.sumOf(::alcoholG) / 10
            val alcoholByDay = byDay.values.map { list -> list.sumOf(::alcoholG) }
            // One answer covers everything added at once (same answer, added within two minutes).
            val meals = entries.filter { it.reason != null || it.hunger != null || it.hardToStop }
                .groupBy { listOf(it.day, it.meal, it.reason, it.hunger, it.hardToStop, it.createdAt / 120_000) }.values.map { it.first() }
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
                alcoholPerWeek = alcohol * 7 / maxOf(days, 7).coerceAtMost(periodDays),
                fastFoodDays = byDay.values.count { list -> list.any { food(it)?.category == "fastfood" } },
                sleepAvg = sleep.values.takeIf { it.isNotEmpty() }?.average(),
                reasonMeals = meals.size,
                notHunger = meals.mapNotNull { it.reason }.filter { it != Reasons.HUNGER }.groupingBy { it }.eachCount(),
                hardToStop = meals.count { it.hardToStop },
                alcoholDays = alcoholByDay.count { it > 0 },
                heavyDrinkDays = alcoholByDay.count { it >= 60 },
                vegGoodDays = byDay.values.count { list -> list.filter { food(it)?.let(::isVegFruit) == true }.sumOf { it.grams } >= 400 },
                lateDays = byDay.values.count { list ->
                    val k = list.sumOf { it.kcal }
                    k > 0 && list.filter(::isLate).sumOf { it.kcal } >= k / 4
                },
                bowelDays = bowel.size,
                bowelMoveDays = bowel.count { it.count > 0 },
                bowelHard = bowel.count { it.form == 0 },
                bowelLoose = bowel.count { it.form == 2 },
                workoutCount = workouts.size,
                // Workouts are logged on their own, not with food: over the whole period.
                activeMinPerWeek = workouts.sumOf(Workouts::whoMinutes) * 7.0 / maxOf(periodDays, 7),
                strengthPerWeek = workouts.filter { it.type == Workouts.STRENGTH }.map { it.day }.distinct().size * 7.0 /
                    maxOf(periodDays, 7),
                timesTracked = withTimes,
                sleepNights = sleep.size,
                shortNights = sleep.values.count { it < 6 },
            )
        }
    }
}
