package ua.tarilka.ui

import androidx.compose.foundation.clickable
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
fun HistoryScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (LocalDate) -> Unit) {
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
                            if (d.note) add("📝")
                        }
                        if (parts.isNotEmpty()) {
                            Text(
                                parts.joinToString("   "),
                                style = MaterialTheme.typography.bodySmall.merge(Numbers),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
