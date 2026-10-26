package ua.tarilka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import ua.tarilka.data.BowelEntry
import ua.tarilka.data.WorkoutEntry
import ua.tarilka.data.Workouts

/** Bowel movements today in two taps: how many, and the form in plain words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BowelDialog(initial: BowelEntry?, onSave: (Int, Int?) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var count by remember { mutableStateOf(initial?.count) }
    var form by remember { mutableStateOf(initial?.form) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bowel_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 1, 2, 3).forEach { n ->
                        FilterChip(
                            selected = count == n,
                            onClick = { count = n },
                            label = { Text(if (n == 0) stringResource(R.string.bowel_none) else if (n == 3) "3+" else "$n", maxLines = 1) },
                        )
                    }
                }
                if ((count ?: 0) > 0) {
                    Text(stringResource(R.string.bowel_form), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        stringArrayResource(R.array.bowel_forms).forEachIndexed { i, label ->
                            FilterChip(selected = form == i, onClick = { form = if (form == i) null else i }, label = { Text(label, maxLines = 1) })
                        }
                    }
                }
                if (initial != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete_record), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(enabled = count != null, onClick = { onSave(count!!, form) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Today's workouts and a quick form to add one: what, how long, how hard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutDialog(
    today: List<WorkoutEntry>,
    weightKg: Double,
    onAdd: (type: Int, minutes: Int, intensity: Int) -> Unit,
    onDelete: (WorkoutEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var type by remember { mutableStateOf<Int?>(null) }
    var minutes by remember { mutableStateOf<Int?>(null) }
    var intensity by remember { mutableStateOf(1) }
    val types = stringArrayResource(R.array.workout_types)
    val levels = stringArrayResource(R.array.workout_intensity)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workouts_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                today.forEach { w ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            workoutLine(w, types, levels) + if (LocalShowKcal.current) " · ≈ ${Workouts.kcal(w, weightKg).f0()} ${stringResource(R.string.unit_kcal)}" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onDelete(w) }) { Text(stringResource(R.string.delete)) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEachIndexed { i, label ->
                        FilterChip(selected = type == i, onClick = { type = i }, label = { Text(label, maxLines = 1) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 20, 30, 45, 60, 90).forEach { m ->
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(stringResource(R.string.minutes_n, m), maxLines = 1) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    levels.forEachIndexed { i, label ->
                        FilterChip(selected = intensity == i, onClick = { intensity = i }, label = { Text(label, maxLines = 1) })
                    }
                }
                if (LocalShowKcal.current) Text(stringResource(R.string.workout_kcal_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = type != null && minutes != null, onClick = { onAdd(type!!, minutes!!, intensity); type = null; minutes = null }) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
fun workoutLine(w: WorkoutEntry, types: Array<String>, levels: Array<String>): String =
    "${types.getOrElse(w.type) { types.last() }} ${stringResource(R.string.minutes_n, w.minutes)} · ${levels.getOrElse(w.intensity) { "" }}"
