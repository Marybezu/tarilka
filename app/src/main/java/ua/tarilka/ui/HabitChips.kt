package ua.tarilka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import ua.tarilka.data.Habits
import ua.tarilka.data.Habits.Level

/**
 * Colored badges for eating patterns: green = going well, amber = so-so, orange = worth attention.
 * Never red: these are hints, not grades. Tap a badge for what it means and what helps.
 */
/** What a badge opens: what it is, what was noticed, the trend, what helps, and when to get help. */
private class HabitInfo(val title: String, val parts: List<String>, val audit: Boolean = false)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitChips(
    h: Habits,
    modifier: Modifier = Modifier,
    sex: ua.tarilka.data.Sex = ua.tarilka.data.Sex.FEMALE,
    pregnant: Boolean = false,
    onSaveNote: ((String) -> Unit)? = null,
    /** Workouts are tracked (for a week or more): show the activity badge even at 0 minutes. */
    workoutsTracked: Boolean = false,
    /** A "What the colours mean" link under the badges. */
    showLegend: Boolean = true,
) {
    var info by remember { mutableStateOf<HabitInfo?>(null) }
    var audit by remember { mutableStateOf(false) }
    val p = h.prev?.takeIf { it.days >= Habits.MIN_DAYS }
    var legend by remember { mutableStateOf(false) }

    /**
     * One badge: "what: how much (of what target)", colored from that same number, plus a word for the trend.
     * [higherIsBetter] turns "more than before" into "better" or "worse".
     */
    @Composable
    fun Habit(
        label: String,
        level: Level,
        trend: Int,
        higherIsBetter: Boolean,
        prevText: String?,
        intro: String,
        noticed: String?,
        tips: String,
        help: String? = null,
        withAudit: Boolean = false,
    ) {
        val trendLine = when {
            prevText == null -> null
            trend > 0 -> stringResource(R.string.trend_up, prevText)
            trend < 0 -> stringResource(R.string.trend_down, prevText)
            else -> stringResource(R.string.trend_same, prevText)
        }
        val word = when {
            trend == 0 -> ""
            (trend > 0) == higherIsBetter -> " · " + stringResource(R.string.trend_better)
            else -> " · " + stringResource(R.string.trend_worse)
        }
        Badge(label + word, level) {
            info = HabitInfo(label, listOfNotNull(intro, noticed, trendLine, tips, help), withAudit)
        }
    }

    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Habit(
            stringResource(R.string.habit_veg, h.vegFruitG.f0()), h.vegLevel,
            h.trend({ it.vegFruitG }, 50.0), true, p?.let { "${it.vegFruitG.f0()} ${stringResource(R.string.unit_g)}" },
            stringResource(R.string.habit_veg_info), stringResource(R.string.veg_noticed, h.vegGoodDays, h.days),
            stringResource(R.string.veg_tips),
        )
        Habit(
            stringResource(R.string.habit_sweets, h.sweetsPct.f0()), h.sweetsLevel,
            h.trend({ it.sweetsPct }, 2.0), false, p?.let { "${it.sweetsPct.f0()}%" },
            stringResource(R.string.habit_sweets_info), stringResource(R.string.sweets_noticed, h.sweetFreeDays, h.days),
            stringResource(R.string.sweets_tips),
        )
        if (h.timesTracked) {
            Habit(
                if (h.latePct < 1) stringResource(R.string.habit_late_none) else stringResource(R.string.habit_late, h.latePct.f0()), h.lateLevel,
                h.trend({ it.latePct }, 5.0), false, p?.let { "${it.latePct.f0()}%" },
                stringResource(R.string.habit_late_info), stringResource(R.string.late_noticed, h.lateDays, h.days),
                stringResource(R.string.late_tips), if (h.lateLevel == Level.WATCH) stringResource(R.string.eating_help) else null,
            )
        }
        h.sleepAvg?.takeIf { h.sleepNights >= Habits.MIN_DAYS }?.let { hrs ->
            Habit(
                stringResource(R.string.habit_sleep, hrs.f1()), h.sleepLevel,
                // Closer to 8 hours is better, from either side.
                h.trend({ it.sleepAvg?.let { v -> -kotlin.math.abs(v - 8) } }, 0.3), true,
                p?.sleepAvg?.let { "${it.f1()} ${stringResource(R.string.unit_h)}" },
                stringResource(R.string.habit_sleep_info), stringResource(R.string.sleep_noticed, h.shortNights, h.sleepNights),
                stringResource(R.string.sleep_tips), if (h.sleepLevel == Level.WATCH) stringResource(R.string.sleep_help) else null,
            )
        }
        if (h.reasonMeals >= Habits.MIN_DAYS) {
            val top = h.notHunger.maxByOrNull { it.value }?.let { REASON_NAMES.getOrNull(it.key) }?.let { stringResource(it) } ?: "—"
            Habit(
                stringResource(R.string.habit_reasons, h.notHungerTotal, h.reasonMeals) +
                    (if (h.hardToStop > 0) " · " + stringResource(R.string.cause_hard_to_stop, h.hardToStop) else ""), h.reasonsLevel,
                h.trend({ if (it.reasonMeals > 0) it.notHungerTotal * 100.0 / it.reasonMeals else null }, 15.0), false,
                p?.takeIf { it.reasonMeals > 0 }?.let { "${it.notHungerTotal} / ${it.reasonMeals}" },
                stringResource(R.string.habit_reasons_info), stringResource(R.string.reasons_noticed, top, h.hardToStop),
                stringResource(R.string.reasons_tips), if (h.hardToStop > 0) stringResource(R.string.eating_help) else null,
            )
        }
        if (workoutsTracked || h.workoutCount > 0 || p?.workoutCount?.let { it > 0 } == true) {
            Habit(
                stringResource(R.string.habit_activity, h.activeMinPerWeek.f0()), h.activityLevel,
                h.trend({ it.activeMinPerWeek }, 20.0), true, p?.let { stringResource(R.string.minutes_n, it.activeMinPerWeek.toInt()) },
                stringResource(R.string.habit_activity_info), stringResource(R.string.activity_noticed, h.strengthPerWeek.f1()),
                stringResource(R.string.activity_tips),
            )
        }
        if (h.bowelDays >= Habits.MIN_DAYS) {
            val value = if (h.bowelMoveDays == h.bowelDays) stringResource(R.string.bowel_daily)
            else stringResource(R.string.bowel_days_of, h.bowelMoveDays, h.bowelDays)
            Habit(
                stringResource(R.string.habit_bowel, value) + (if (h.bowelLoose > 0) " · " + stringResource(R.string.cause_loose, h.bowelLoose) else ""), h.bowelLevel,
                h.trend({ if (it.bowelDays > 0) it.bowelMoveDays * 7.0 / it.bowelDays else null }, 1.0), true,
                p?.takeIf { it.bowelDays > 0 }?.let { "${it.bowelMoveDays} / ${it.bowelDays}" },
                stringResource(R.string.habit_bowel_info), stringResource(R.string.bowel_noticed, h.bowelHard, h.bowelLoose, h.fiberAvg.f0()),
                stringResource(R.string.bowel_tips), if (h.bowelLevel == Level.WATCH) stringResource(R.string.bowel_help) else null,
            )
        }
        if (h.alcoholPerWeek > 0) {
            val noticed = buildList {
                if (h.alcoholPerWeek > 7) add(stringResource(R.string.alcohol_many))
                if (h.perWeek(h.alcoholDays) >= 4) add(stringResource(R.string.alcohol_often))
                if (h.heavyDrinkDays > 0) add(stringResource(R.string.alcohol_heavy, h.heavyDrinkDays))
            }.joinToString("\n").ifEmpty { null }
            Habit(
                stringResource(R.string.habit_alcohol, h.alcoholPerWeek.f1(), h.alcoholDays) +
                    (if (h.heavyDrinkDays > 0) " · " + stringResource(R.string.cause_heavy, h.heavyDrinkDays) else ""), h.alcoholLevel,
                h.trend({ it.alcoholPerWeek }, 1.0), false, p?.let { stringResource(R.string.per_week, it.alcoholPerWeek.f1()) },
                stringResource(R.string.habit_alcohol_info), noticed,
                stringResource(R.string.alcohol_tips), if (h.alcoholLevel == Level.WATCH) stringResource(R.string.alcohol_help) else null,
                withAudit = true,
            )
        }
        if (h.fastFoodDays > 0) {
            Habit(
                stringResource(R.string.habit_fastfood, h.fastFoodDays), h.fastFoodLevel,
                h.trend({ it.perWeek(it.fastFoodDays) }, 1.0), false, p?.let { stringResource(R.string.days_n, it.fastFoodDays) },
                stringResource(R.string.habit_fastfood_info), null, stringResource(R.string.fastfood_tips),
            )
        }
    }
    if (showLegend) {
        TextButton(onClick = { legend = true }) { Text(stringResource(R.string.colors_title)) }
    }
    if (legend) {
        AlertDialog(
            onDismissRequest = { legend = false },
            title = { Text(stringResource(R.string.colors_title)) },
            text = { Text(stringResource(R.string.colors_info), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { legend = false }) { Text(stringResource(R.string.close)) } },
        )
    }
    info?.let { i ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(i.title) },
            text = {
                Text(
                    i.parts.joinToString("\n\n"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { info = null }) { Text(stringResource(R.string.close)) } },
            dismissButton = if (i.audit) {
                { TextButton(onClick = { info = null; audit = true }) { Text(stringResource(R.string.audit_button)) } }
            } else null,
        )
    }
    if (audit) AuditCDialog(sex, pregnant, onSaveNote) { audit = false }
}

@Composable
private fun Badge(text: String, level: Level, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = levelColor(level)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = if (dark) 0.28f else 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
