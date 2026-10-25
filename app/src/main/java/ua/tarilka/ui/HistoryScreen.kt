package ua.tarilka.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import ua.tarilka.ui.theme.Numbers
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Every day with entries, newest first. Tap a day to open it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: MainViewModel, ai: AiViewModel, onBack: () -> Unit, onOpen: (LocalDate) -> Unit) {
    val days by vm.history.collectAsState()
    val targets by vm.targets.collectAsState()
    val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        if (days.isEmpty()) {
            Text(
                stringResource(R.string.history_empty),
                Modifier.padding(pad).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "summary") { Summaries(vm, ai, days.size) }
            items(days, key = { it.day.toEpochDay() }) { d ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(d.day) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            d.day.format(fmt) + "  " +
                                d.day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val parts = buildList {
                            if (d.fluidMl > 0) add("💧 ${(d.fluidMl / 1000).f1()} ${stringResource(R.string.unit_l)}")
                            d.weight?.let { add("⚖ ${it.f1()} ${stringResource(R.string.kg)}") }

                        }
                        if (parts.isNotEmpty()) {
                            Text(
                                parts.joinToString("   "),
                                style = MaterialTheme.typography.bodySmall.merge(Numbers),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        d.note?.let {
                            Text(
                                "📝 $it",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (d.kcal > 0) {
                        Text(
                            "${d.kcal.f0()} ${stringResource(R.string.unit_kcal)}",
                            style = MaterialTheme.typography.titleMedium.merge(Numbers),
                            color = if (d.kcal > targets.kcal * 1.1) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Badges and an AI summary for the last week, month or year. */
@Composable
private fun Summaries(vm: MainViewModel, ai: AiViewModel, dayCount: Int) {
    var period by rememberSaveable { mutableStateOf(MainViewModel.Period.WEEK) }
    val habits by produceState<ua.tarilka.data.Habits?>(null, period, dayCount) { value = vm.habitsFor(period) }
    val model by ai.model.collectAsState()
    val text by ai.summary.collectAsState()
    val loading by ai.summaryLoading.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Section(Modifier.padding(16.dp), title = stringResource(R.string.summaries_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MainViewModel.Period.entries.forEach { p ->
                    FilterChip(
                        selected = p == period,
                        onClick = { if (p != period) { period = p; ai.clearSummary() } },
                        label = {
                            Text(stringResource(when (p) {
                                MainViewModel.Period.WEEK -> R.string.period_week
                                MainViewModel.Period.MONTH -> R.string.period_month
                                MainViewModel.Period.YEAR -> R.string.period_year
                            }))
                        },
                    )
                }
            }
            val h = habits
            if (h == null || h.days < 2) {
                Text(stringResource(R.string.summaries_few), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(
                stringResource(R.string.summaries_avg, h.kcalAvg.f0(), h.proteinAvg.f0(), h.days),
                style = MaterialTheme.typography.bodyMedium.merge(Numbers),
            )
            HabitChips(h)
            if (model !is ua.tarilka.ai.ModelStore.State.Ready) return@Column
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodyMedium)
                }
                text != null -> {
                    HorizontalDivider()
                    Text(text!!, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text!!)) }) { Text(stringResource(R.string.copy)) }
                        TextButton(onClick = ai::clearSummary) { Text(stringResource(R.string.hide)) }
                    }
                }
                else -> FilledTonalButton(onClick = { ai.requestSummary({ vm.periodSummary(period) }, ua.tarilka.data.isUkrainian()) }) {
                    Icon(Icons.Filled.Star, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_summary))
                }
            }
        }
    }
}
