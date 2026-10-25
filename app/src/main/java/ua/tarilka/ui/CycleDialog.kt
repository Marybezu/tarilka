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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "2.11": short enough to keep the cycle row on one line. */
fun dayMonth(day: Long): String = LocalDate.ofEpochDay(day).let { "${it.dayOfMonth}.%02d".format(it.monthValue) }

fun shortDate(day: Long): String = LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/**
 * Everything about the cycle in one place: what the app knows, today's flow and pain (optional),
 * marking a start or end on another date, past starts, and when to see a doctor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CycleDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val info by vm.cycle.collectAsState()
    val periods by vm.periods.collectAsState()
    val days by vm.periodDays.collectAsState()
    val selected by vm.day.collectAsState()
    val context = LocalContext.current
    val c = info ?: return
    val today = selected.toEpochDay()
    fun pickDate(onPicked: (LocalDate) -> Unit) {
        android.app.DatePickerDialog(
            context, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) },
            selected.year, selected.monthValue - 1, selected.dayOfMonth,
        ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cycle_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                c.avgLength?.let { Text(stringResource(R.string.cycle_avg, it, c.lengths.size), style = MaterialTheme.typography.bodyMedium) }
                c.nextStart?.let { Text(stringResource(R.string.cycle_next, shortDate(it)), style = MaterialTheme.typography.bodyMedium) }
                c.ovulation?.let { Text(stringResource(R.string.cycle_ovulation, shortDate(it - 5), shortDate(it)), style = MaterialTheme.typography.bodyMedium) }
                if (c.nextStart == null || c.fromTypical) Text(stringResource(R.string.cycle_need_two), style = MaterialTheme.typography.bodySmall)
                if (c.irregular) Text(stringResource(R.string.cycle_irregular), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.cycle_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Flow and pain for the shown day, if it falls in a period.
                val inPeriod = periods.any { today in it.start..(it.end ?: (it.start + 9)) }
                if (inPeriod) {
                    HorizontalDivider()
                    val d = days.firstOrNull { it.day == today }
                    Text(stringResource(R.string.cycle_flow), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        stringArrayResource(R.array.cycle_flows).forEachIndexed { i, label ->
                            FilterChip(selected = d?.flow == i, onClick = { vm.setPeriodDay(selected, if (d?.flow == i) null else i, d?.pain) }, label = { Text(label, maxLines = 1) })
                        }
                    }
                    Text(stringResource(R.string.cycle_pain), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        stringArrayResource(R.array.cycle_pains).forEachIndexed { i, label ->
                            FilterChip(selected = d?.pain == i, onClick = { vm.setPeriodDay(selected, d?.flow, if (d?.pain == i) null else i) }, label = { Text(label, maxLines = 1) })
                        }
                    }
                }

                HorizontalDivider()
                TextButton(onClick = { pickDate(vm::periodStarted) }) { Text(stringResource(R.string.cycle_mark_start)) }
                if (periods.isNotEmpty()) TextButton(onClick = { pickDate(vm::periodEnded) }) { Text(stringResource(R.string.cycle_mark_end)) }

                periods.sortedByDescending { it.start }.take(6).forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            shortDate(p.start) + (p.end?.let { " – " + shortDate(it) } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.deletePeriod(p.start) }) { Text(stringResource(R.string.delete)) }
                    }
                }

                HorizontalDivider()
                Text(stringResource(R.string.cycle_tips), style = MaterialTheme.typography.bodyMedium)
                if (c.seeDoctor) {
                    val why = buildList {
                        if (c.tooShortOrLong) add(stringResource(R.string.cycle_flag_length))
                        if (c.longBleeding) add(stringResource(R.string.cycle_flag_long))
                        if (c.heavyOrPainful) add(stringResource(R.string.cycle_flag_heavy))
                    }.joinToString(" ")
                    Text(stringResource(R.string.cycle_doctor, why), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                } else {
                    Text(stringResource(R.string.cycle_when_doctor), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** First setup: when the last period started, how long it usually lasts, and the usual cycle length. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CycleSetupDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var start by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<LocalDate?>(null) }
    var periodLen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(5) }
    var cycleLen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(vm.cycleTypical.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cycle_setup_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cycle_setup_last), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.OutlinedButton(onClick = {
                    val d = start ?: LocalDate.now()
                    android.app.DatePickerDialog(context, { _, y, m, dd -> start = LocalDate.of(y, m + 1, dd) }, d.year, d.monthValue - 1, d.dayOfMonth)
                        .apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                }) { Text(start?.let { shortDate(it.toEpochDay()) } ?: stringResource(R.string.choose_date)) }
                Text(stringResource(R.string.cycle_setup_period), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (3..7).forEach { n -> FilterChip(selected = periodLen == n, onClick = { periodLen = n }, label = { Text("$n") }) }
                }
                Text(stringResource(R.string.cycle_setup_cycle), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = cycleLen <= 0, onClick = { cycleLen = -1 }, label = { Text(stringResource(R.string.dont_know)) })
                    listOf(24, 26, 28, 30, 32, 35).forEach { n -> FilterChip(selected = cycleLen == n, onClick = { cycleLen = n }, label = { Text("$n") }) }
                }
                Text(stringResource(R.string.cycle_setup_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = start != null, onClick = { vm.setupCycle(start!!, periodLen, cycleLen); onDismiss() }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
