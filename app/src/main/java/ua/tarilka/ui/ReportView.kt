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
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Text(stringResource(R.string.report_main), style = MaterialTheme.typography.titleSmall)
        Text(highlightLines(ctx, metrics, report.highlights).joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
        AdviceBlock(report.advice)
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
        val change = metricChange(androidx.compose.ui.platform.LocalContext.current, m)
        change?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Bars(m.series, m.seriesTarget, levelColor(m.level), fromZero = m.key != Key.WEIGHT)
    }
}

/** Small bars, one per day / week / month, with the target as a dashed line. Empty days are a short tick. */
@Composable
private fun Bars(series: List<Double?>, target: Double?, color: Color, fromZero: Boolean = true) {
    val empty = MaterialTheme.colorScheme.outlineVariant
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(34.dp).padding(top = 4.dp)) {
        val values = series.filterNotNull()
        // Weight: from just below the lowest value, so 70 and 72 kg look different.
        val base = if (fromZero) 0.0 else (values.minOrNull() ?: 0.0) - 1.0
        val max = ((values + listOfNotNull(target)).maxOrNull()?.takeIf { it > base } ?: (base + 1.0)) - base
        val slot = size.width / series.size.coerceAtLeast(1)
        val w = slot * 0.6f
        series.forEachIndexed { i, v ->
            val x = i * slot + (slot - w) / 2
            if (v == null) {
                drawRect(empty, Offset(x, size.height - 2.dp.toPx()), Size(w, 2.dp.toPx()))
            } else {
                val h = ((v - base) / max * size.height).toFloat().coerceAtLeast(2.dp.toPx())
                drawRoundRect(color.copy(alpha = 0.7f), Offset(x, size.height - h), Size(w, h), CornerRadius(3.dp.toPx()))
            }
        }
        target?.let {
            val y = (size.height - (it - base) / max * size.height).toFloat()
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
    }
}

@Composable
private fun name(k: Key): String = metricName(androidx.compose.ui.platform.LocalContext.current, k)

@Composable
private fun valueText(m: Metric): String = metricValue(androidx.compose.ui.platform.LocalContext.current, m)

/** The measure's name; plain functions so the PDF report says exactly the same. */
fun metricName(c: android.content.Context, k: Key): String = c.getString(
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
fun metricNumber(c: android.content.Context, k: Key, v: Double): String = when (k) {
    Key.KCAL -> v.f0() + " " + c.getString(R.string.unit_kcal)
    Key.PROTEIN, Key.FIBER, Key.VEG -> v.f0() + " " + c.getString(R.string.unit_g)
    Key.FLUID -> (v / 1000).f1() + " " + c.getString(R.string.unit_l)
    Key.SLEEP -> v.f1() + " " + c.getString(R.string.unit_h)
    Key.ACTIVITY -> c.getString(R.string.mv_min_week, v.f0())
    Key.ALCOHOL -> c.getString(R.string.per_week, v.f1())
    Key.FASTFOOD, Key.BOWEL -> c.getString(R.string.mv_days_week, v.f1())
    Key.SWEETS, Key.LATE, Key.REASONS -> v.f0() + "%"
    Key.WEIGHT -> (if (v > 0) "+" else "") + v.f1()
    Key.STEPS -> v.f0()
}

fun metricValue(c: android.content.Context, m: Metric): String {
    val v = m.value
    val t = m.target ?: 0.0
    // The colour may come from this, not from the number: say so beside it.
    val cause = when (m.cause) {
        Metric.Cause.HARD_TO_STOP -> " · " + c.getString(R.string.cause_hard_to_stop, m.causeCount)
        Metric.Cause.LOOSE -> " · " + c.getString(R.string.cause_loose, m.causeCount)
        Metric.Cause.HEAVY_DRINKING -> " · " + c.getString(R.string.cause_heavy, m.causeCount)
        null -> ""
    }
    return cause.let { suffix -> metricValueOnly(c, m, v, t) + suffix }
}

private fun metricValueOnly(c: android.content.Context, m: Metric, v: Double, t: Double): String {
    return when (m.key) {
        Key.KCAL -> c.getString(R.string.mv_kcal, v.f0(), t.f0())
        Key.PROTEIN, Key.FIBER -> c.getString(R.string.mv_grams, v.f0(), t.f0())
        Key.FLUID -> c.getString(R.string.mv_litres, (v / 1000).f1(), (t / 1000).f1())
        Key.VEG -> c.getString(R.string.mv_grams, v.f0(), "400")
        Key.SWEETS, Key.LATE -> c.getString(R.string.mv_pct_kcal, v.f0())
        Key.SLEEP -> c.getString(R.string.mv_sleep, v.f1())
        Key.STEPS -> c.getString(R.string.mv_of, v.f0(), t.f0())
        Key.ACTIVITY -> c.getString(R.string.mv_activity, v.f0(), t.f0())
        Key.ALCOHOL -> c.getString(R.string.per_week, v.f1())
        Key.FASTFOOD -> c.getString(R.string.mv_days_week, v.f1())
        Key.REASONS -> c.getString(R.string.mv_reasons, v.f0())
        Key.BOWEL -> c.getString(R.string.mv_days_week, v.f1())
        Key.WEIGHT -> c.getString(R.string.mv_weight, metricNumber(c, Key.WEIGHT, v))
    }
}

/** "better than before (was …)", or null without a comparison. */
fun metricChange(c: android.content.Context, m: Metric): String? {
    val p = m.prev ?: return null
    if (m.key == Key.WEIGHT) return null
    val was = metricNumber(c, m.key, p)
    return c.getString(if (m.change > 0) R.string.report_better else if (m.change < 0) R.string.report_worse else R.string.report_same, was)
}

/** The main points as lines. */
fun highlightLines(c: android.content.Context, metrics: List<Metric>, hl: ua.tarilka.data.Report.Highlights): List<String> {
    val lines = buildList {
        hl.improved?.let { add(c.getString(R.string.report_improved, metricName(c, it.key), metricNumber(c, it.key, it.prev ?: 0.0), metricNumber(c, it.key, it.value))) }
        hl.attention?.let { add(c.getString(R.string.report_attention, metricName(c, it.key), metricValue(c, it))) }
        hl.steady?.let { add(c.getString(R.string.report_steady, metricName(c, it.key))) }
    }
    return if (metrics.none { it.prev != null }) lines + c.getString(R.string.report_no_compare) else lines
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

/** "What could change" (at most two) and "what is already good" (one), with the person's own foods. */
@Composable
fun AdviceBlock(advice: ua.tarilka.data.Advice.Result) {
    val c = androidx.compose.ui.platform.LocalContext.current
    if (advice.change.isNotEmpty()) {
        Text(stringResource(R.string.advice_change), style = MaterialTheme.typography.titleSmall)
        advice.change.forEach { Text("• " + adviceText(c, it), style = MaterialTheme.typography.bodyMedium) }
    }
    advice.good?.let {
        Text(stringResource(R.string.advice_good), style = MaterialTheme.typography.titleSmall)
        Text("• " + adviceText(c, it), style = MaterialTheme.typography.bodyMedium)
    }
}

/** One suggestion in words; the guideline it follows is named at the end. */
fun adviceText(c: android.content.Context, i: ua.tarilka.data.Advice.Item): String {
    val foods = i.foods.joinToString(", ")
    val text = if (i.good) when (i.rule) {
        ua.tarilka.data.Advice.Rule.VEG_FRUIT -> c.getString(R.string.advice_good_veg, i.amount.f0())
        ua.tarilka.data.Advice.Rule.LEGUMES -> c.getString(R.string.advice_good_legumes, i.amount.f1())
        ua.tarilka.data.Advice.Rule.FISH -> c.getString(R.string.advice_good_fish, i.amount.f1())
        ua.tarilka.data.Advice.Rule.REFINED_GRAINS -> c.getString(R.string.advice_good_whole, i.amount.f0(), foods)
        else -> ""
    } else when (i.rule) {
        ua.tarilka.data.Advice.Rule.PROCESSED_MEAT -> c.getString(R.string.advice_processed, i.amount.f1(), foods)
        ua.tarilka.data.Advice.Rule.RED_MEAT -> c.getString(R.string.advice_red, i.amount.f0(), foods)
        ua.tarilka.data.Advice.Rule.SUGAR -> c.getString(R.string.advice_sugar, i.amount.f0(), foods)
        ua.tarilka.data.Advice.Rule.SFA_FOODS -> c.getString(R.string.advice_sfa, i.amount.f0(), foods)
        ua.tarilka.data.Advice.Rule.FASTFOOD -> c.getString(R.string.advice_fastfood, i.amount.f1(), foods)
        ua.tarilka.data.Advice.Rule.REFINED_GRAINS -> c.getString(R.string.advice_refined, i.amount.f0(), foods)
        ua.tarilka.data.Advice.Rule.VEG_FRUIT -> c.getString(R.string.advice_veg, i.amount.f0()) + if (foods.isNotEmpty()) " " + c.getString(R.string.advice_veg_yours, foods) else ""
        ua.tarilka.data.Advice.Rule.LEGUMES -> c.getString(R.string.advice_legumes)
        ua.tarilka.data.Advice.Rule.FISH -> c.getString(R.string.advice_fish)
        ua.tarilka.data.Advice.Rule.NUTS -> c.getString(R.string.advice_nuts)
    }
    return text + " (" + c.getString(i.rule.source) + ")"
}
