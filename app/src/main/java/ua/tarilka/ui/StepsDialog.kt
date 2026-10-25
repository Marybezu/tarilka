package ua.tarilka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import ua.tarilka.ui.theme.LocalMacroColors
import ua.tarilka.ui.theme.Numbers
import java.time.LocalDate

/** Steps for the last week or month as bars, with the daily goal as a line; the goal can be changed here. */
@Composable
fun StepsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var days by remember { mutableIntStateOf(7) }
    val goal by vm.stepsGoal.collectAsState()
    var goalText by remember { mutableStateOf(goal.toString()) }
    val history by produceState<List<Pair<LocalDate, Long?>>>(emptyList(), days) { value = vm.stepsHistory(days) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.steps_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = days == 7, onClick = { days = 7 }, label = { Text(stringResource(R.string.period_week)) })
                    FilterChip(selected = days == 30, onClick = { days = 30 }, label = { Text(stringResource(R.string.period_month)) })
                }
                val known = history.mapNotNull { it.second }
                if (known.isEmpty()) {
                    Text(stringResource(R.string.steps_no_history), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        stringResource(R.string.steps_avg, known.average().f0(), known.count { it >= goal }, known.size),
                        style = MaterialTheme.typography.bodyMedium.merge(Numbers),
                    )
                    StepBars(history.map { it.second ?: 0L }, goal)
                }
                Field(stringResource(R.string.steps_goal), goalText, { goalText = it.filter(Char::isDigit).take(5) })
                Text(stringResource(R.string.steps_goal_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                goalText.toIntOrNull()?.takeIf { it in 1_000..50_000 }?.let(vm::setStepsGoal)
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun StepBars(values: List<Long>, goal: Int) {
    val color = LocalMacroColors.current.fiber
    val track = MaterialTheme.colorScheme.surfaceVariant
    val max = maxOf(goal * 1.3, (values.maxOrNull() ?: 0L).toDouble())
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        val n = values.size
        val gap = if (n > 10) 2f else 8f
        val w = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            val x = i * (w + gap)
            drawRect(track, Offset(x, 0f), Size(w, size.height))
            val h = (v / max).toFloat() * size.height
            drawRect(color, Offset(x, size.height - h), Size(w, h))
        }
        val y = size.height - (goal / max).toFloat() * size.height
        drawLine(Color.Gray, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
    }
}
