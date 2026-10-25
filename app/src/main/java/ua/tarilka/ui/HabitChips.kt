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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitChips(h: Habits, modifier: Modifier = Modifier, sex: ua.tarilka.data.Sex = ua.tarilka.data.Sex.FEMALE) {
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var alcoholInfo by remember { mutableStateOf(false) }
    var audit by remember { mutableStateOf(false) }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val veg = stringResource(R.string.habit_veg, h.vegFruitG.f0())
        val vegInfo = stringResource(R.string.habit_veg_info)
        Badge(veg, h.vegLevel) { info = veg to vegInfo }

        val sweets = stringResource(R.string.habit_sweets, h.sweetFreeDays, h.days)
        val sweetsInfo = stringResource(R.string.habit_sweets_info, h.sweetsPct.f0())
        Badge(sweets, h.sweetsLevel) { info = sweets to sweetsInfo }

        val late = if (h.latePct < 1) stringResource(R.string.habit_late_none) else stringResource(R.string.habit_late, h.latePct.f0())
        val lateInfo = stringResource(R.string.habit_late_info)
        Badge(late, h.lateLevel) { info = late to lateInfo }

        h.sleepAvg?.let { hrs ->
            val sl = stringResource(R.string.habit_sleep, hrs.f1())
            val slInfo = stringResource(R.string.habit_sleep_info)
            Badge(sl, h.sleepLevel) { info = sl to slInfo }
        }
        if (h.reasonMeals > 0) {
            val rs = stringResource(R.string.habit_reasons, h.notHungerTotal, h.reasonMeals)
            val top = h.notHunger.maxByOrNull { it.value }?.let { stringResource(REASON_NAMES[it.key]) }
            val rsInfo = stringResource(R.string.habit_reasons_info, top ?: "—", h.hardToStop)
            Badge(rs, h.reasonsLevel) { info = rs to rsInfo }
        }
        if (h.alcoholPerWeek > 0) {
            Badge(stringResource(R.string.habit_alcohol, h.alcoholPerWeek.f1(), h.alcoholDays), h.alcoholLevel) { alcoholInfo = true }
        }
        if (h.fastFoodDays > 0) {
            val ff = stringResource(R.string.habit_fastfood, h.fastFoodDays)
            val ffInfo = stringResource(R.string.habit_fastfood_info)
            Badge(ff, h.fastFoodLevel) { info = ff to ffInfo }
        }
    }
    if (alcoholInfo) {
        val weeks = h.periodDays / 7.0
        val text = buildList {
            add(stringResource(R.string.habit_alcohol_info))
            if (h.alcoholPerWeek > 7) add(stringResource(R.string.alcohol_many))
            if (h.alcoholDays / weeks >= 4) add(stringResource(R.string.alcohol_often))
            if (h.heavyDrinkDays > 0) add(stringResource(R.string.alcohol_heavy, h.heavyDrinkDays))
            h.alcoholPrevPerWeek?.let { prev ->
                when {
                    prev == 0.0 -> {}
                    h.alcoholPerWeek < prev * 0.9 -> add(stringResource(R.string.alcohol_trend_down, prev.f1()))
                    h.alcoholPerWeek > prev * 1.1 -> add(stringResource(R.string.alcohol_trend_up, prev.f1()))
                }
            }
            add(stringResource(R.string.alcohol_tips))
            if (h.heavyDrinkDays > 0 || h.alcoholLevel == Level.WATCH) add(stringResource(R.string.alcohol_help))
        }.joinToString("\n\n")
        AlertDialog(
            onDismissRequest = { alcoholInfo = false },
            title = { Text(stringResource(R.string.habit_alcohol, h.alcoholPerWeek.f1(), h.alcoholDays)) },
            text = {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { alcoholInfo = false }) { Text(stringResource(R.string.close)) } },
            dismissButton = { TextButton(onClick = { alcoholInfo = false; audit = true }) { Text(stringResource(R.string.audit_button)) } },
        )
    }
    if (audit) AuditCDialog(sex) { audit = false }
    info?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(title) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { info = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun Badge(text: String, level: Level, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = when (level) {
        Level.GOOD -> Color(0xFF43A047)
        Level.OK -> Color(0xFFFFB300)
        Level.WATCH -> Color(0xFFF4511E)
    }
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
