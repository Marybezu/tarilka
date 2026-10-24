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
        item {
            Section(title = stringResource(R.string.energy_title)) {
                Text(
                    stringResource(R.string.energy_text, targets.bmr.f0(), targets.tdee.f0(), targets.kcal.f0()),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
