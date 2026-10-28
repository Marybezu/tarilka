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
    /** Daily drinks, ml (EFSA adequate intake from drinks, see [Nutrition.targets]). */
    val waterMl: Double = 2000.0,
    /** Body mass index, kg/m². */
    val bmi: Double = 0.0,
    /** Why weight loss is not offered, if so. */
    val limit: Limit? = null,
    /** The goal actually used: "Lose" turns into "Maintain" when [limit] is set. */
    val goal: Goal = Goal.MAINTAIN,
    /** The person's own target was below the safety floor and was raised to it. */
    val customRaised: Boolean = false,
    /** Under 18 (also when pregnant or breastfeeding). */
    val teen: Boolean = false,
) {
    /**
     * Under 18 no calorie number is shown (AAP 2016/2023: no calorie counting for teenagers; adult formulas
     * underestimate growing bodies). Protein, fibre, vegetables and fluids stay.
     */
    val showKcal: Boolean get() = !teen
}

/** When a calorie deficit is not appropriate without a doctor. */
enum class Limit { PREGNANT, TEEN, UNDERWEIGHT }

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
        val bmi = bmi(w, p.heightCm)
        // No unsupervised dieting in pregnancy and breastfeeding (NICE PH27), under 18 (AAP 2016)
        // or with a BMI under 18.5 (WHO: underweight).
        val limit = limitFor(p.sex, p.pregnant, p.age, bmi)
        val goal = if (limit != null && p.goal == Goal.LOSE) Goal.MAINTAIN else p.goal
        // Pregnancy and breastfeeding need more: EFSA +70 / +260 / +500 kcal by trimester, +500 while breastfeeding.
        // One switch covers both, so about +300 is added; the doctor knows better.
        val extra = if (limit == Limit.PREGNANT) 300.0 else 0.0
        val goalKcal = extra + when (goal) {
            Goal.LOSE -> tdee - minOf(500.0, tdee * 0.2)
            Goal.MAINTAIN -> tdee
            Goal.GAIN -> tdee + minOf(400.0, tdee * 0.12)
        }
        // Safety floor: never below the basal metabolic rate or a general minimum, also for the
        // person's own target; with a limit, never below what the body uses in a day.
        val floor = if (limit != null) tdee + extra else max(bmr, if (p.sex == Sex.MALE) 1500.0 else 1200.0)
        val custom = p.customKcal?.toDouble()
        val kcal = max(custom ?: goalKcal, floor)
        val proteinPerKg = when (goal) {
            Goal.LOSE -> 1.6
            Goal.MAINTAIN -> 1.0 // EFSA: 0.83 g/kg at least; a little more for older adults
            Goal.GAIN -> 1.6
        }
        // Above a BMI of 25, protein is counted from the weight at a BMI of 25 (ESPEN), not the actual weight,
        // so it stays within about a third of the calories.
        val proteinWeight = if (bmi > 25) 25.0 * (p.heightCm / 100.0) * (p.heightCm / 100.0) else w
        val protein = proteinPerKg * proteinWeight
        val fat = kcal * 0.30 / 9
        val carbs = max(0.0, (kcal - protein * 4 - fat * 9) / 4)
        val fiber = max(25.0, kcal / 1000 * 14)
        // Drinks and soups: the EFSA adequate intake (2.0 / 2.5 L of water a day, about 80 % of it from drinks)
        // as the minimum; a bigger body that uses more energy needs a little more, so also about 1 ml of water
        // per kcal used a day, 80 % of it from drinks (an approximation, not an EFSA value), at most 3 L.
        // More in pregnancy and breastfeeding (+0.3 / +0.7 L) and with a very active day.
        val water = max(if (p.sex == Sex.MALE) 2000.0 else 1600.0, (tdee * 0.8).coerceAtMost(3000.0)) +
            (if (limit == Limit.PREGNANT) 500.0 else 0.0) + (if (p.activity >= 3) 500.0 else 0.0)
        return Targets(
            kcal, protein, fat, carbs, fiber, bmr, tdee, water,
            bmi = bmi, limit = limit, goal = goal, customRaised = custom != null && custom < floor, teen = p.age < 18,
        )
    }

    fun bmi(weightKg: Double, heightCm: Int): Double = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))

    fun limitFor(sex: Sex, pregnant: Boolean, age: Int, bmi: Double): Limit? = when {
        sex == Sex.FEMALE && pregnant -> Limit.PREGNANT
        age < 18 -> Limit.TEEN
        bmi < 18.5 -> Limit.UNDERWEIGHT
        else -> null
    }

    /** WHO adult categories: 0 under 18.5, 1 normal, 2 overweight (25), 3–5 obesity class I–III (30, 35, 40). */
    fun bmiClass(bmi: Double): Int = when {
        bmi < 18.5 -> 0
        bmi < 25 -> 1
        bmi < 30 -> 2
        bmi < 35 -> 3
        bmi < 40 -> 4
        else -> 5
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

/**
 * The safety rules for the AI prompts, for this person: who must not be told to eat less, and what is never said.
 * Kept in one place for the day advice and the period summary.
 */
fun Targets.aiSafety(): String = when (limit) {
    Limit.PREGNANT -> "The person is pregnant or breastfeeding: never suggest eating less or losing weight; weight gain in pregnancy is expected; the safest is no alcohol at all. "
    Limit.TEEN -> "The person is under 18: never suggest eating less or losing weight and never mention calories or calorie targets; talk about regular meals, vegetables, drinks, sleep and movement. "
    Limit.UNDERWEIGHT -> "BMI is under 18.5: never suggest eating less or losing weight; gently suggest a doctor if weight is falling. "
    null -> ""
} + (if (teen && limit != Limit.TEEN) "The person is under 18: never mention calories or calorie targets. " else "") +
    "Never comment on body shape or size, never suggest skipping meals or burning off food with exercise."
