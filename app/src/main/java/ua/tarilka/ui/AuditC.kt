package ua.tarilka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import ua.tarilka.data.Sex

/**
 * AUDIT-C: the first three questions of the WHO AUDIT, 0–12 points.
 * Increased risk from 3 points for women and 4 for men; 8 or more is high.
 * Answers are not stored.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuditCDialog(sex: Sex, onDismiss: () -> Unit) {
    val answers = remember { mutableStateListOf<Int?>(null, null, null) }
    val questions = listOf(
        R.string.audit_q1 to R.array.audit_a1,
        R.string.audit_q2 to R.array.audit_a2,
        R.string.audit_q3 to R.array.audit_a3,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.audit_intro), style = MaterialTheme.typography.bodySmall)
                questions.forEachIndexed { qi, (q, a) ->
                    Text(stringResource(q), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        stringArrayResource(a).forEachIndexed { i, label ->
                            FilterChip(selected = answers[qi] == i, onClick = { answers[qi] = i }, label = { Text(label) })
                        }
                    }
                }
                if (answers.all { it != null }) {
                    val score = answers.sumOf { it!! }
                    val risk = if (sex == Sex.FEMALE) 3 else 4
                    Text(stringResource(R.string.audit_score, score), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            when {
                                score == 0 -> R.string.audit_none
                                score < risk -> R.string.audit_low
                                score < 8 -> R.string.audit_risk
                                else -> R.string.audit_high
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (score >= risk) Text(stringResource(R.string.alcohol_tips), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
