package ua.tarilka.data

import ua.tarilka.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** A recommendation: a string resource plus integer format args. */
data class Tip(val res: Int, val args: List<Any> = emptyList(), val kind: Kind = Kind.INFO) {
    enum class Kind { GOOD, INFO, WARN }
}

/** Simple, transparent rules. The AI only rephrases; these numbers decide. */
object Tips {
    fun forDay(
        totals: Totals,
        targets: Targets,
        entries: Int,
        dayFinished: Boolean,
        profile: Profile,
        weeklyTrendKg: Double?,
        currentWeight: Double?,
        fluidMl: Double = 0.0,
        afternoon: Boolean = false,
        steps: Long? = null,
        stepsGoal: Int = 8_000,
    ): List<Tip> {
        val tips = mutableListOf<Tip>()
        if (entries == 0) {
            tips += Tip(R.string.tip_empty)
        } else {
            val left = (targets.kcal - totals.kcal).roundToInt()
            when {
                totals.kcal > targets.kcal * 1.1 ->
                    tips += Tip(R.string.tip_over, listOf(-left), Tip.Kind.WARN)
                !dayFinished && left > 0 ->
                    tips += Tip(R.string.tip_remaining, listOf(left))
                dayFinished && totals.kcal < targets.kcal * 0.7 ->
                    tips += Tip(R.string.tip_under, listOf(totals.kcal.roundToInt()), Tip.Kind.WARN)
                abs(left) <= targets.kcal * 0.1 ->
                    tips += Tip(R.string.tip_on_target, kind = Tip.Kind.GOOD)
            }
            if (dayFinished || totals.kcal >= targets.kcal * 0.6) {
                if (totals.protein < targets.protein * 0.8) {
                    tips += Tip(
                        R.string.tip_protein_low,
                        listOf(totals.protein.roundToInt(), targets.protein.roundToInt()),
                        Tip.Kind.WARN,
                    )
                }
                if (totals.fiber < targets.fiber * 0.6) {
                    tips += Tip(
                        R.string.tip_fiber_low,
                        listOf(totals.fiber.roundToInt(), targets.fiber.roundToInt()),
                    )
                }
                if (totals.fat > targets.fat * 1.25) {
                    tips += Tip(R.string.tip_fat_high, listOf(totals.fat.roundToInt()))
                }
                if (totals.protein >= targets.protein * 0.95 && totals.fiber >= targets.fiber * 0.9) {
                    tips += Tip(R.string.tip_balance_good, kind = Tip.Kind.GOOD)
                }
            }
        }
        if ((dayFinished || afternoon) && fluidMl < targets.waterMl * 0.6) {
            tips += Tip(R.string.tip_water_low, listOf(fluidMl.roundToInt(), targets.waterMl.roundToInt()))
        }
        if (steps != null) {
            when {
                steps >= stepsGoal -> tips += Tip(R.string.tip_steps_good, listOf(steps.toInt()), Tip.Kind.GOOD)
                dayFinished && steps < 4_000 -> tips += Tip(R.string.tip_steps_low, listOf(steps.toInt()))
            }
        }
        if (weeklyTrendKg != null && currentWeight != null) {
            val oneTenth = (weeklyTrendKg * 10).roundToInt() / 10.0
            // The goal in use: "Lose" is not used in pregnancy, under 18 or with a low BMI.
            val goal = targets.goal
            when {
                weeklyTrendKg < -currentWeight * 0.01 ->
                    tips += Tip(R.string.tip_weight_fast, listOf(oneTenth), Tip.Kind.WARN)
                // Never praise weight loss with a BMI under 18.5: a calm word and a pointer to help instead.
                targets.limit == Limit.UNDERWEIGHT && weeklyTrendKg < -0.1 ->
                    tips += Tip(R.string.tip_weight_down_low_bmi, listOf(oneTenth))
                goal == Goal.LOSE && weeklyTrendKg > 0.1 ->
                    tips += Tip(R.string.tip_weight_up, listOf(oneTenth))
                goal == Goal.LOSE && weeklyTrendKg < -0.1 ->
                    tips += Tip(R.string.tip_weight_down_good, listOf(oneTenth), Tip.Kind.GOOD)
                goal == Goal.GAIN && weeklyTrendKg < 0 ->
                    tips += Tip(R.string.tip_weight_gain_none, listOf(oneTenth))
            }
        }
        return tips
    }
}
