package ua.tarilka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import ua.tarilka.data.Food
import ua.tarilka.ui.theme.LocalMacroColors
import ua.tarilka.ui.theme.Numbers
import java.util.Locale
import kotlin.math.roundToInt

/** Whether calorie numbers are shown at all: not under 18 (see Targets.showKcal). Set once at the root. */
val LocalShowKcal = androidx.compose.runtime.compositionLocalOf { true }

fun Double.f0(): String = if (isFinite()) roundToInt().toString() else "—"
fun Double.f1(): String = String.format(Locale.getDefault(), "%.1f", this)

/** A number typed by the person: comma or dot, never negative, NaN or infinite. */
fun String.toNumber(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

@Composable
fun mealName(meal: Int): String = stringResource(
    when (meal) {
        0 -> R.string.meal_breakfast
        1 -> R.string.meal_lunch
        2 -> R.string.meal_dinner
        else -> R.string.meal_snack
    },
)

@Composable
fun Section(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

/** Ring that fills as calories are eaten; turns amber when over the target. */
@Composable
fun CalorieRing(eaten: Double, target: Double?, warnOver: Boolean = true, size: Dp = 132.dp, ofText: String? = null) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = LocalMacroColors.current.kcal
    val over = MaterialTheme.colorScheme.tertiary
    val frac = if (target != null && target > 0) (eaten / target).toFloat() else 0f
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Canvas(Modifier.size(size)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                if (frac > 1.05f && warnOver) over else fill, -90f, 360f * frac.coerceIn(0f, 1f), false,
                Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(eaten.f0(), style = MaterialTheme.typography.headlineMedium.merge(Numbers))
            Text(
                ofText ?: if (target != null) stringResource(R.string.of_kcal, target.f0()) else stringResource(R.string.unit_kcal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun NutrientBar(
    label: String,
    value: Double,
    target: Double,
    color: Color,
    unit: String = stringResource(R.string.unit_g),
    decimals: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                if (decimals) "${value.f1()} / ${target.f1()} $unit" else "${value.f0()} / ${target.f0()} $unit",
                style = MaterialTheme.typography.labelMedium.merge(Numbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        val track = MaterialTheme.colorScheme.surfaceVariant
        val frac = if (target > 0) (value / target).toFloat().coerceIn(0f, 1f) else 0f
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            drawRect(track)
            drawRect(color, size = Size(size.width * frac, size.height))
        }
    }
}

@Composable
fun MacroLine(kcal: Double, p: Double, f: Double, c: Double) {
    val m = LocalMacroColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (LocalShowKcal.current) Text("${kcal.f0()} ${stringResource(R.string.unit_kcal)}", style = MaterialTheme.typography.bodySmall.merge(Numbers))
        Text("${stringResource(R.string.short_protein)} ${p.f0()}", color = m.protein, style = MaterialTheme.typography.bodySmall.merge(Numbers))
        Text("${stringResource(R.string.short_fat)} ${f.f0()}", color = m.fat, style = MaterialTheme.typography.bodySmall.merge(Numbers))
        Text("${stringResource(R.string.short_carbs)} ${c.f0()}", color = m.carbs, style = MaterialTheme.typography.bodySmall.merge(Numbers))
    }
}

@Composable
fun sourceLabel(source: String): String = stringResource(
    when (source) {
        "USDA" -> R.string.source_usda
        "LABEL" -> R.string.source_label
        "RECIPE" -> R.string.source_recipe
        "AI" -> R.string.source_ai
        "RECIPE_USER" -> R.string.source_recipe_user
        else -> R.string.source_custom
    },
)

/** "Time: 19:40 ✎" — tap to pick another time of the meal. */
@Composable
fun TimeRow(time: java.time.LocalTime, onChange: (java.time.LocalTime) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    TextButton(onClick = {
        android.app.TimePickerDialog(context, { _, h, m -> onChange(java.time.LocalTime.of(h, m)) }, time.hour, time.minute, true).show()
    }) {
        Text(stringResource(R.string.meal_time, "%02d:%02d".format(time.hour, time.minute)))
    }
}

/** Ask how many grams; shows the resulting nutrients live. */
@Composable
fun GramsDialog(
    title: String,
    initial: Double?,
    food: Food?,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
    extraAction: (@Composable () -> Unit)? = null,
    /** When set, a "Time" row is shown and [onConfirmAt] gets the chosen time. */
    time: java.time.LocalTime? = null,
    onConfirmAt: ((Double, java.time.LocalTime) -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial?.f0() ?: "") }
    val grams = text.toNumber()
    var at by remember { mutableStateOf(time) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.grams)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                val portion = food?.portionG
                if (food != null && portion != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val unit = food.portionName() ?: stringResource(R.string.portion)
                        val g = stringResource(R.string.unit_g)
                        AssistChip(onClick = { text = portion.f0() }, label = { Text("≈ 1 $unit (${portion.f0()} $g)") })
                        AssistChip(onClick = { text = (portion * 2).f0() }, label = { Text("≈ 2 (${(portion * 2).f0()} $g)") })
                    }
                    Text(
                        stringResource(R.string.portion_typical),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                    }
                }
                at?.let { t -> TimeRow(t) { at = it } }
                if (food != null && grams != null) {
                    Spacer(Modifier.height(12.dp))
                    val k = grams / 100
                    MacroLine(food.kcal * k, food.protein * k, food.fat * k, food.carbs * k)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = grams != null && grams > 0 && grams <= 5000, onClick = {
                val g = grams ?: return@TextButton
                val t = at
                if (t != null && onConfirmAt != null) onConfirmAt(g, t) else onConfirm(g)
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                extraAction?.invoke()
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Minimal line chart: points are (x, y); draws a dot for each and a line through them. */
@Composable
fun LineChart(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier, height: Dp = 180.dp) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    if (points.size < 2) {
        Text(
            stringResource(R.string.chart_need_more),
            style = MaterialTheme.typography.bodyMedium,
            color = labelColor,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        )
        return
    }
    val minY = points.minOf { it.second }
    val maxY = points.maxOf { it.second }
    val padY = maxOf(0.5, (maxY - minY) * 0.15)
    val lo = minY - padY
    val hi = maxY + padY
    val minX = points.first().first
    val maxX = points.last().first
    Row(modifier.fillMaxWidth()) {
        Column(Modifier.height(height), verticalArrangement = Arrangement.SpaceBetween) {
            Text(hi.f1(), style = labelStyle.merge(Numbers), color = labelColor)
            Text(lo.f1(), style = labelStyle.merge(Numbers), color = labelColor)
        }
        Spacer(Modifier.width(8.dp))
        Canvas(
            Modifier
                .weight(1f)
                .height(height),
        ) {
            fun px(x: Double) = if (maxX == minX) size.width / 2 else ((x - minX) / (maxX - minX) * size.width).toFloat()
            fun py(y: Double) = (size.height - (y - lo) / (hi - lo) * size.height).toFloat()
            for (i in 0..3) {
                val y = size.height * i / 3
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val path = Path()
            points.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(px(x), py(y)) else path.lineTo(px(x), py(y))
            }
            drawPath(path, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            points.forEach { (x, y) -> drawCircle(line, 3.5.dp.toPx(), Offset(px(x), py(y))) }
        }
    }
}

/** Quick fluid logging: common amounts, a custom amount, and today's list. */
@Composable
fun FluidDialog(
    fluids: List<ua.tarilka.data.FluidEntry>,
    onAdd: (Double) -> Unit,
    onDelete: (ua.tarilka.data.FluidEntry) -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.water_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.water_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(150, 250, 330, 500).forEach { ml ->
                        AssistChip(onClick = { onAdd(ml.toDouble()) }, label = { Text("$ml") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        label = { Text(stringResource(R.string.ml)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = custom.toNumber()?.let { it in 1.0..3000.0 } == true,
                        onClick = { custom.toNumber()?.let(onAdd); custom = "" },
                    ) { Text(stringResource(R.string.add)) }
                }
                val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                fluids.asReversed().forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            java.time.Instant.ofEpochMilli(f.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(time),
                            style = MaterialTheme.typography.bodyMedium.merge(Numbers),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("${f.ml.f0()} ${stringResource(R.string.ml)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.merge(Numbers))
                        TextButton(onClick = { onDelete(f) }) { Text(stringResource(R.string.delete)) }
                    }
                }
                if (fluids.size > 1) {
                    TextButton(onClick = onDeleteAll) {
                        Text(stringResource(R.string.delete_fluids_day), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
