package ua.tarilka.data

import kotlin.math.max

data class Targets(
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val fiber: Double,
    val bmr: Double,
    val tdee: Double,
    /** Daily fluid, ml: about 30 ml per kg of body weight. */
    val waterMl: Double = 2000.0,
)

object Nutrition {
    val ACTIVITY_FACTORS = doubleArrayOf(1.2, 1.375, 1.55, 1.725, 1.9)

    /** Mifflin–St Jeor basal metabolic rate, kcal/day. */
    fun bmr(p: Profile, weightKg: Double): Double {
        val base = 10 * weightKg + 6.25 * p.heightCm - 5 * p.age
        return if (p.sex == Sex.MALE) base + 5 else base - 161
    }

    fun targets(p: Profile, currentWeightKg: Double?): Targets {
        val w = currentWeightKg ?: p.weightKg
        val bmr = bmr(p, w)
        val tdee = bmr * ACTIVITY_FACTORS[p.activity.coerceIn(0, 4)]
        val goalKcal = when (p.goal) {
            Goal.LOSE -> tdee - minOf(500.0, tdee * 0.2)
            Goal.MAINTAIN -> tdee
            Goal.GAIN -> tdee + minOf(400.0, tdee * 0.12)
        }
        // Safety floor: never suggest eating below the basal metabolic rate
        // or below a general minimum.
        val floor = max(bmr, if (p.sex == Sex.MALE) 1500.0 else 1200.0)
        val kcal = (p.customKcal?.toDouble() ?: max(goalKcal, floor))
        val proteinPerKg = when (p.goal) {
            Goal.LOSE -> 1.6
            Goal.MAINTAIN -> 1.2
            Goal.GAIN -> 1.6
        }
        val protein = proteinPerKg * w
        val fat = kcal * 0.30 / 9
        val carbs = max(0.0, (kcal - protein * 4 - fat * 9) / 4)
        val fiber = max(25.0, kcal / 1000 * 14)
        val water = (w * 30).coerceIn(1500.0, 3500.0)
        return Targets(kcal, protein, fat, carbs, fiber, bmr, tdee, water)
    }

    /** Weight change in kg per week from a least-squares line through the points. */
    fun weeklyTrend(points: List<WeightEntry>): Double? {
        if (points.size < 3) return null
        val span = points.last().day - points.first().day
        if (span < 6) return null
        val n = points.size.toDouble()
        val mx = points.sumOf { it.day.toDouble() } / n
        val my = points.sumOf { it.kg } / n
        var num = 0.0
        var den = 0.0
        for (pt in points) {
            num += (pt.day - mx) * (pt.kg - my)
            den += (pt.day - mx) * (pt.day - mx)
        }
        if (den == 0.0) return null
        return num / den * 7
    }

    /** Rough energy of walking: ~0.04 kcal per step for 70 kg, scaled by weight. Shown only, not added to targets. */
    fun stepsKcal(steps: Long, weightKg: Double): Double = steps * 0.04 * weightKg / 70.0

}
