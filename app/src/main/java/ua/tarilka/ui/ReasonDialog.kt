package ua.tarilka.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.tarilka.R

val REASON_NAMES = listOf(R.string.reason_hunger, R.string.reason_stress, R.string.reason_boredom, R.string.reason_company, R.string.reason_habit)

/**
 * Optional "why do I eat now?" after adding food. Every part can be left empty;
 * the answers stay on the phone and only feed the summaries.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReasonDialog(onSave: (reason: Int?, hunger: Int?, hardToStop: Boolean) -> Unit, onSkip: () -> Unit) {
    var reason by remember { mutableStateOf<Int?>(null) }
    var hunger by remember { mutableStateOf<Int?>(null) }
    var hard by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.reason_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    REASON_NAMES.forEachIndexed { i, res ->
                        FilterChip(selected = reason == i, onClick = { reason = if (reason == i) null else i }, label = { Text(stringResource(res)) })
                    }
                }
                Text(stringResource(R.string.hunger_scale), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { n ->
                        FilterChip(selected = hunger == n, onClick = { hunger = if (hunger == n) null else n }, label = { Text("$n") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { hard = !hard }) {
                    Checkbox(checked = hard, onCheckedChange = { hard = it })
                    Text(stringResource(R.string.hard_to_stop), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(reason, hunger, hard) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onSkip) { Text(stringResource(R.string.skip)) } },
    )
}
