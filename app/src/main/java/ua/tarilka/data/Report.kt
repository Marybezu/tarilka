package ua.tarilka.data

import ua.tarilka.data.Habits.Level
import kotlin.math.abs

/**
 * One line of the week / month / year summary: the period's value, its target, a colour,
 * the change against the period before, and a small series for the chart (one value per day,
 * week or month, each as a per-day average so bars of different lengths compare).
 */
data class Metric(
    val key: Key,
    val value: Double,
    val target: Double?,
    val level: Level,
    /** The period before, only when it has enough data. */
    val prev: Double?,
    /** +1 better, -1 worse, 0 about the same or unknown. */
    val change: Int,
    val series: List<Double?>,
    /** Target line for the chart, in the series' per-day units. */
    val seriesTarget: Double?,
    /** When the colour comes from something other than the value itself, what it is and how often. */
    val cause: Cause? = null,
    val causeCount: Int = 0,
) {
    enum class Cause { HARD_TO_STOP, LOOSE, HEAVY_DRINKING }
    enum class Key { KCAL, PROTEIN, FIBER, FLUID, VEG, SWEETS, LATE, SLEEP, STEPS, ACTIVITY, ALCOHOL, FASTFOOD, REASONS, BOWEL, WEIGHT }
}

/** Raw numbers for one period (current, previous or one bar of the chart). */
data class PeriodData(
    val habits: Habits,
    /** Average fluid per day with any fluid, ml. */
    val fluidMl: Double?,
    /** Average steps per day with steps. */
    val steps: Double?,
    val stepsDays: Int = 0,
    val fluidDays: Int = 0,
    /** Average weight, kg, and the weekly trend, kg. */
    val weight: Double? = null,
    val weightTrend: Double? = null,
    /** Workout minutes (WHO-weighted) per day, when workouts are tracked or logged. */
    val activityPerDay: Double? = null,
)

object Report {
    private val MIN = Habits.MIN_DAYS

    /** Builds the lines that have data, in a fixed order. */
    fun build(
        cur: PeriodData,
        prev: PeriodData?,
        bars: List<PeriodData>,
        targets: Targets,
        stepsGoal: Int,
    ): List<Metric> {
        val h = cur.habits
        val ph = prev?.habits?.takeIf { it.days >= MIN }
        val out = mutableListOf<Metric>()
        fun ratioLevel(v: Double, t: Double, good: Double, ok: Double) =
            if (v >= t * good) Level.GOOD else if (v >= t * ok) Level.OK else Level.WATCH

        /** One measure: its value, level, the value before, and how a change is judged ([higherIsBetter], [closenessTo]). */
        fun add(
            key: Metric.Key, value: Double?, target: Double?, level: Level, prevValue: Double?,
            minDiff: Double, higherIsBetter: Boolean, series: (PeriodData) -> Double?, seriesTarget: Double?,
            closenessTo: Double? = null, cause: Metric.Cause? = null, causeCount: Int = 0,
        ) {
            value ?: return
            val change = prevValue?.let { p ->
                val a = closenessTo?.let { -abs(value - it) } ?: value
                val b = closenessTo?.let { -abs(p - it) } ?: p
                val up = closenessTo != null || higherIsBetter
                when {
                    a - b >= minDiff -> if (up) 1 else -1
                    b - a >= minDiff -> if (up) -1 else 1
                    else -> 0
                }
            } ?: 0
            out += Metric(key, value, target, level, prevValue, change, bars.map(series), seriesTarget, cause, causeCount)
        }

        if (h.days >= MIN) {
            val k = targets.kcal
            // Under 18 no calorie measure (see Targets.showKcal).
            // When eating less is not advised (pregnancy, low BMI), more than the target is never "worth attention".
            val noLess = targets.limit != null
            if (targets.showKcal) add(
                Metric.Key.KCAL, h.kcalAvg, k,
                if (noLess && h.kcalAvg > k) Level.GOOD
                else abs(h.kcalAvg - k).let { if (it <= k * 0.1) Level.GOOD else if (it <= k * 0.25) Level.OK else Level.WATCH },
                ph?.kcalAvg, k * 0.05, true, { it.habits.foodOrNull { kcalAvg } }, k, closenessTo = if (noLess) null else k,
            )
            add(
                Metric.Key.PROTEIN, h.proteinAvg, targets.protein, ratioLevel(h.proteinAvg, targets.protein, 0.9, 0.7),
                ph?.proteinAvg, targets.protein * 0.1, true, { it.habits.foodOrNull { proteinAvg } }, targets.protein,
            )
            add(
                Metric.Key.FIBER, h.fiberAvg, targets.fiber, ratioLevel(h.fiberAvg, targets.fiber, 0.9, 0.6),
                ph?.fiberAvg, 2.0, true, { it.habits.foodOrNull { fiberAvg } }, targets.fiber,
            )
        }
        if (cur.fluidMl != null && cur.fluidDays >= MIN) {
            add(
                Metric.Key.FLUID, cur.fluidMl, targets.waterMl, ratioLevel(cur.fluidMl, targets.waterMl, 0.9, 0.7),
                prev?.fluidMl?.takeIf { prev.fluidDays >= MIN }, 150.0, true, { it.fluidMl }, targets.waterMl,
            )
        }
        if (h.days >= MIN) {
            add(Metric.Key.VEG, h.vegFruitG, 400.0, h.vegLevel, ph?.vegFruitG, 50.0, true, { it.habits.foodOrNull { vegFruitG } }, 400.0)
            add(Metric.Key.SWEETS, h.sweetsPct, 10.0, h.sweetsLevel, ph?.sweetsPct, 2.0, false, { it.habits.foodOrNull { sweetsPct } }, 10.0)
            if (h.timesTracked) {
                add(Metric.Key.LATE, h.latePct, 25.0, h.lateLevel, ph?.latePct, 5.0, false, { it.habits.foodOrNull { latePct } }, 25.0)
            }
        }
        if (h.sleepNights >= MIN) {
            add(
                Metric.Key.SLEEP, h.sleepAvg, if (h.teen) 8.0 else 7.0, h.sleepLevel, prev?.habits?.takeIf { it.sleepNights >= MIN }?.sleepAvg,
                0.3, true, { it.habits.sleepAvg }, if (h.teen) 8.0 else 7.0, closenessTo = if (h.teen) 9.0 else 8.0,
            )
        }
        if (cur.steps != null && cur.stepsDays >= MIN) {
            add(
                Metric.Key.STEPS, cur.steps, stepsGoal.toDouble(), ratioLevel(cur.steps, stepsGoal.toDouble(), 1.0, 0.5),
                prev?.steps?.takeIf { prev.stepsDays >= MIN }, 500.0, true, { it.steps }, stepsGoal.toDouble(),
            )
        }
        if (cur.activityPerDay != null && h.periodDays >= 7) {
            add(
                Metric.Key.ACTIVITY, h.activeMinPerWeek, if (h.teen) 420.0 else 150.0, h.activityLevel, prev?.activityPerDay?.let { ph?.activeMinPerWeek },
                20.0, true, { it.activityPerDay }, (if (h.teen) 420.0 else 150.0) / 7,
            )
        }
        if (h.days >= MIN && h.alcoholPerWeek > 0) {
            add(
                Metric.Key.ALCOHOL, h.alcoholPerWeek, null,
                // Under 18 and in pregnancy no amount is advised: the same colour as on the Today screen.
                if (!targets.showKcal || targets.limit == Limit.PREGNANT) Habits.Level.WATCH else h.alcoholLevel, ph?.alcoholPerWeek, 1.0, false, { it.habits.foodOrNull { alcoholPerWeek / 7 } }, null,
                cause = Metric.Cause.HEAVY_DRINKING.takeIf { h.heavyDrinkDays > 0 }, causeCount = h.heavyDrinkDays,
            )
        }
        if (h.days >= MIN && h.fastFoodDays > 0) {
            add(
                Metric.Key.FASTFOOD, h.perWeek(h.fastFoodDays), 1.0, h.fastFoodLevel, ph?.let { it.perWeek(it.fastFoodDays) },
                1.0, false, { d -> d.habits.foodOrNull { fastFoodDays.toDouble() / periodDays } }, null,
            )
        }
        if (h.reasonMeals >= MIN) {
            fun share(x: Habits) = x.notHungerTotal * 100.0 / x.reasonMeals
            add(
                Metric.Key.REASONS, share(h), 50.0, h.reasonsLevel, prev?.habits?.takeIf { it.reasonMeals >= MIN }?.let(::share),
                15.0, false, { d -> d.habits.takeIf { it.reasonMeals > 0 }?.let(::share) }, 50.0,
                cause = Metric.Cause.HARD_TO_STOP.takeIf { h.hardToStop > 0 }, causeCount = h.hardToStop,
            )
        }
        if (h.bowelDays >= MIN) {
            fun perWeek(x: Habits) = x.bowelMoveDays * 7.0 / x.bowelDays
            add(
                Metric.Key.BOWEL, perWeek(h), 3.0, h.bowelLevel, prev?.habits?.takeIf { it.bowelDays >= MIN }?.let(::perWeek),
                1.0, true, { d -> d.habits.takeIf { it.bowelDays > 0 }?.let { it.bowelMoveDays.toDouble() / it.bowelDays } }, 3.0 / 7,
                cause = Metric.Cause.LOOSE.takeIf { h.bowelLoose > 0 }, causeCount = h.bowelLoose,
            )
        }
        if (cur.weightTrend != null) {
            val t = cur.weightTrend
            val w = cur.weight ?: 0.0
            val level = when {
                w > 0 && t < -w * 0.01 -> Level.WATCH
                // In pregnancy, under 18 and with a low BMI going up is expected or welcome.
                // (Under 18 growth is normal, but quick gain is not praised.)
                targets.limit != null -> if (t >= -0.1 && (targets.limit != Limit.TEEN || t <= 0.5)) Level.GOOD else Level.OK
                // Losing weight is not praised close to underweight (as on Today).
                targets.goal == Goal.LOSE && t < -0.1 && targets.bmi >= 20 -> Level.GOOD
                targets.goal == Goal.GAIN && t > 0.1 -> Level.GOOD
                targets.goal == Goal.MAINTAIN && abs(t) < 0.2 -> Level.GOOD
                else -> Level.OK
            }
            out += Metric(Metric.Key.WEIGHT, t, null, level, null, 0, bars.map { it.weight }, null)
        }
        return out
    }

    private inline fun Habits.foodOrNull(f: Habits.() -> Double): Double? = if (days > 0) f() else null

    /** "What changed most for the better", "what is worth attention", "what is steadily good": at most one each. */
    data class Highlights(val improved: Metric?, val attention: Metric?, val steady: Metric?)

    fun highlights(metrics: List<Metric>): Highlights {
        fun rel(m: Metric): Double {
            val p = m.prev ?: return 0.0
            val base = m.target ?: abs(p).coerceAtLeast(1.0)
            return abs(m.value - p) / base.coerceAtLeast(1e-6)
        }
        fun gap(m: Metric): Double = m.target?.let { abs(m.value - it) / it.coerceAtLeast(1e-6) } ?: 0.0
        val improved = metrics.filter { it.change > 0 }.maxByOrNull(::rel)
        val attention = metrics.filter { it.level == Level.WATCH }.maxByOrNull(::gap)
        // "Steadily" needs the period before to compare with.
        val steady = metrics.filter { it.level == Level.GOOD && it.prev != null && it.change >= 0 && it != improved }
            .firstOrNull()
        return Highlights(improved, attention, steady)
    }
}
