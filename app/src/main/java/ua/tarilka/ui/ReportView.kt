package ua.tarilka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import ua.tarilka.data.Habits.Level
import ua.tarilka.data.Metric
import ua.tarilka.data.Metric.Key
import ua.tarilka.ui.theme.Numbers

/** Green / amber / orange, never red: the same colours as the badges. */
fun levelColor(level: Level): Color = when (level) {
    Level.GOOD -> Color(0xFF43A047)
    Level.OK -> Color(0xFFFFB300)
    Level.WATCH -> Color(0xFFF4511E)
}

/**
 * The week / month / year summary: "the main points" first, then one line per measure with
 * its value and target, a word on the change, and a small bar chart.
 */
@Composable
fun ReportView(report: MainViewModel.PeriodReport) {
    var open by remember { mutableStateOf<Metric?>(null) }
    val metrics = report.metrics
    if (metrics.isEmpty()) {
        Text(stringResource(R.string.summaries_few), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The main points, from simple rules (no AI needed).
        val hl = report.highlights
        val lines = buildList {
            hl.improved?.let { add(stringResource(R.string.report_improved, name(it.key), number(it.key, it.prev ?: 0.0), number(it.key, it.value))) }
            hl.attention?.let { add(stringResource(R.string.report_attention, name(it.key), valueText(it))) }
            hl.steady?.let { add(stringResource(R.string.report_steady, name(it.key))) }
        }
        Text(stringResource(R.string.report_main), style = MaterialTheme.typography.titleSmall)
        Text(
            if (lines.isEmpty() || metrics.none { it.prev != null }) (lines + stringResource(R.string.report_no_compare)).joinToString("\n")
            else lines.joinToString("\n"),
            style = MaterialTheme.typography.bodyMedium,
        )
        // Bar labels once, above all the charts.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            report.barLabels.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        metrics.forEach { m -> MetricRow(m) { open = m } }
        Text(stringResource(R.string.report_bars_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    open?.let { m ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(name(m.key)) },
            text = {
                Text(
                    info(m.key).joinToString("\n\n"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun MetricRow(m: Metric, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("● ", color = levelColor(m.level), style = MaterialTheme.typography.bodyMedium)
            Text(name(m.key), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(": " + valueText(m), style = MaterialTheme.typography.bodyMedium.merge(Numbers), modifier = Modifier.weight(1f))
        }
        val change = when {
            m.key == Key.WEIGHT || m.prev == null -> null
            m.change > 0 -> stringResource(R.string.report_better, number(m.key, m.prev))
            m.change < 0 -> stringResource(R.string.report_worse, number(m.key, m.prev))
            else -> stringResource(R.string.report_same, number(m.key, m.prev))
        }
        change?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Bars(m.series, m.seriesTarget, levelColor(m.level))
    }
}

/** Small bars, one per day / week / month, with the target as a dashed line. Empty days are a short tick. */
@Composable
private fun Bars(series: List<Double?>, target: Double?, color: Color) {
    val empty = MaterialTheme.colorScheme.outlineVariant
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(34.dp).padding(top = 4.dp)) {
        val values = series.filterNotNull()
        val max = (values + listOfNotNull(target)).maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val slot = size.width / series.size.coerceAtLeast(1)
        val w = slot * 0.6f
        series.forEachIndexed { i, v ->
            val x = i * slot + (slot - w) / 2
            if (v == null) {
                drawRect(empty, Offset(x, size.height - 2.dp.toPx()), Size(w, 2.dp.toPx()))
            } else {
                val h = (v / max * size.height).toFloat().coerceAtLeast(2.dp.toPx())
                drawRoundRect(color.copy(alpha = 0.7f), Offset(x, size.height - h), Size(w, h), CornerRadius(3.dp.toPx()))
            }
        }
        target?.let {
            val y = (size.height - it / max * size.height).toFloat()
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
    }
}

@Composable
private fun name(k: Key): String = stringResource(
    when (k) {
        Key.KCAL -> R.string.m_kcal
        Key.PROTEIN -> R.string.m_protein
        Key.FIBER -> R.string.m_fiber
        Key.FLUID -> R.string.m_fluid
        Key.VEG -> R.string.m_veg
        Key.SWEETS -> R.string.m_sweets
        Key.LATE -> R.string.m_late
        Key.SLEEP -> R.string.m_sleep
        Key.STEPS -> R.string.m_steps
        Key.ACTIVITY -> R.string.m_activity
        Key.ALCOHOL -> R.string.m_alcohol
        Key.FASTFOOD -> R.string.m_fastfood
        Key.REASONS -> R.string.m_reasons
        Key.BOWEL -> R.string.m_bowel
        Key.WEIGHT -> R.string.m_weight
    },
)

/** The bare number in the measure's unit (for "it was …"). */
@Composable
private fun number(k: Key, v: Double): String = when (k) {
    Key.FLUID -> (v / 1000).f1() + " " + stringResource(R.string.unit_l)
    Key.SLEEP -> v.f1() + " " + stringResource(R.string.unit_h)
    Key.ALCOHOL, Key.FASTFOOD, Key.BOWEL -> v.f1()
    Key.SWEETS, Key.LATE, Key.REASONS -> v.f0() + "%"
    Key.WEIGHT -> (if (v > 0) "+" else "") + v.f1()
    else -> v.f0()
}

@Composable
private fun valueText(m: Metric): String {
    val v = m.value
    val t = m.target ?: 0.0
    return when (m.key) {
        Key.KCAL -> stringResource(R.string.mv_kcal, v.f0(), t.f0())
        Key.PROTEIN, Key.FIBER -> stringResource(R.string.mv_grams, v.f0(), t.f0())
        Key.FLUID -> stringResource(R.string.mv_litres, (v / 1000).f1(), (t / 1000).f1())
        Key.VEG -> stringResource(R.string.mv_grams, v.f0(), "400")
        Key.SWEETS, Key.LATE -> stringResource(R.string.mv_pct_kcal, v.f0())
        Key.SLEEP -> stringResource(R.string.mv_sleep, v.f1())
        Key.STEPS -> stringResource(R.string.mv_of, v.f0(), t.f0())
        Key.ACTIVITY -> stringResource(R.string.mv_activity, v.f0())
        Key.ALCOHOL -> stringResource(R.string.per_week, v.f1())
        Key.FASTFOOD -> stringResource(R.string.mv_days_week, v.f1())
        Key.REASONS -> stringResource(R.string.mv_reasons, v.f0())
        Key.BOWEL -> stringResource(R.string.mv_days_week, v.f1())
        Key.WEIGHT -> stringResource(R.string.mv_weight, number(Key.WEIGHT, v))
    }
}

@Composable
private fun info(k: Key): List<String> = when (k) {
    Key.KCAL -> listOf(stringResource(R.string.mi_kcal))
    Key.PROTEIN -> listOf(stringResource(R.string.mi_protein))
    Key.FIBER -> listOf(stringResource(R.string.mi_fiber))
    Key.FLUID -> listOf(stringResource(R.string.mi_fluid), stringResource(R.string.water_own_note))
    Key.STEPS -> listOf(stringResource(R.string.steps_goal_hint))
    Key.WEIGHT -> listOf(stringResource(R.string.mi_weight))
    Key.VEG -> listOf(stringResource(R.string.habit_veg_info), stringResource(R.string.veg_tips))
    Key.SWEETS -> listOf(stringResource(R.string.habit_sweets_info), stringResource(R.string.sweets_tips))
    Key.LATE -> listOf(stringResource(R.string.habit_late_info), stringResource(R.string.late_tips))
    Key.SLEEP -> listOf(stringResource(R.string.habit_sleep_info), stringResource(R.string.sleep_tips))
    Key.ACTIVITY -> listOf(stringResource(R.string.habit_activity_info), stringResource(R.string.activity_tips))
    Key.ALCOHOL -> listOf(stringResource(R.string.habit_alcohol_info), stringResource(R.string.alcohol_tips))
    Key.FASTFOOD -> listOf(stringResource(R.string.habit_fastfood_info), stringResource(R.string.fastfood_tips))
    Key.REASONS -> listOf(stringResource(R.string.habit_reasons_info), stringResource(R.string.reasons_tips))
    Key.BOWEL -> listOf(stringResource(R.string.habit_bowel_info), stringResource(R.string.bowel_tips))
}
