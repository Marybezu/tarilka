package ua.tarilka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import ua.tarilka.ui.theme.Numbers
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun WeightScreen(vm: MainViewModel, padding: PaddingValues) {
    val weights by vm.weights.collectAsState()
    val trend by vm.weeklyTrend.collectAsState()
    val targets by vm.targets.collectAsState()
    var input by remember { mutableStateOf("") }
    var range by remember { mutableIntStateOf(30) }
    val today = LocalDate.now().toEpochDay()
    val todays = weights.firstOrNull { it.day == today }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.tab_weight), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
        }
        item {
            Section(title = stringResource(R.string.weight_today)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(
                        stringResource(R.string.kg),
                        input,
                        { input = it },
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(
                        enabled = input.toNumber()?.let { it in 25.0..350.0 } == true,
                        onClick = { input.toNumber()?.let { vm.saveWeight(it) }; input = "" },
                    ) { Text(stringResource(R.string.save)) }
                }
                if (todays != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.weight_saved_today, todays.kg.f1()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Section(title = stringResource(R.string.weight_chart)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 90, 365).forEach { d ->
                        FilterChip(
                            selected = range == d,
                            onClick = { range = d },
                            label = { Text(stringResource(R.string.days_n, d)) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val pts = weights.filter { it.day >= today - range }.map { it.day.toDouble() to it.kg }
                LineChart(pts)
                trend?.let {
                    Spacer(Modifier.height(8.dp))
                    val sign = if (it > 0) "+" else ""
                    Text(stringResource(R.string.trend_week, sign + it.f1()), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { WaistSection(vm) }
        item {
            Section(title = stringResource(R.string.energy_title)) {
                if (targets.showKcal) {
                    Text(
                        stringResource(R.string.energy_text, targets.bmr.f0(), targets.tdee.f0(), targets.kcal.f0()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val profile by vm.profile.collectAsState()
                if (profile.isSet) BmiLine(targets.bmi, profile.age, targets.limit == ua.tarilka.data.Limit.PREGNANT)
            }
        }
        if (weights.isNotEmpty()) {
            item { ListHeader(stringResource(R.string.history)) }
            items(weights.reversed(), key = { it.day }) { w ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            LocalDate.ofEpochDay(w.day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Text("${w.kg.f1()} ${stringResource(R.string.kg)}", style = MaterialTheme.typography.bodyLarge.merge(Numbers))
                    IconButton(onClick = { vm.deleteWeight(w) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

/**
 * Waist circumference, now and then (optional). WHO: over 80 cm for women and 94 cm for men is a higher risk,
 * over 88 / 102 cm a substantially higher one; it shows what BMI cannot (where fat is).
 */
@Composable
private fun WaistSection(vm: MainViewModel) {
    val list by vm.waist.collectAsState()
    val profile by vm.profile.collectAsState()
    var input by remember { mutableStateOf("") }
    Section(title = stringResource(R.string.waist_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(stringResource(R.string.waist_cm), input, { input = it }, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Button(
                enabled = input.toNumber()?.let { it in 40.0..250.0 } == true,
                onClick = { input.toNumber()?.let { vm.saveWaist(it) }; input = "" },
            ) { Text(stringResource(R.string.save)) }
        }
        list.lastOrNull()?.let { last ->
            Spacer(Modifier.height(6.dp))
            val female = profile.sex == ua.tarilka.data.Sex.FEMALE
            val (raised, high) = if (female) 80.0 to 88.0 else 94.0 to 102.0
            // In pregnancy and under 18 the adult thresholds say nothing: only the number, no colour.
            val rated = !profile.pregnant && profile.age >= 18
            val level = when {
                !rated -> null
                last.cm <= raised -> ua.tarilka.data.Habits.Level.GOOD
                last.cm <= high -> ua.tarilka.data.Habits.Level.OK
                else -> ua.tarilka.data.Habits.Level.WATCH
            }
            val first = list.first()
            Text(
                "● " + stringResource(R.string.waist_last, last.cm.f1(), shortDate(last.day)) +
                    (if (list.size > 1) " · " + stringResource(R.string.waist_change, (if (last.cm - first.cm > 0) "+" else "") + (last.cm - first.cm).f1(), shortDate(first.day)) else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = level?.let { levelColor(it) } ?: MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (rated) stringResource(R.string.waist_info, raised.f0(), high.f0()) else stringResource(R.string.waist_not_rated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { vm.deleteWaist(last) }) { Text(stringResource(R.string.delete_last)) }
        } ?: Text(stringResource(R.string.waist_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
