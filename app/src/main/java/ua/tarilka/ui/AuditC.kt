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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * The answers are never stored; the score goes into today's note only if the person taps Save.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuditCDialog(sex: Sex, onSaveNote: ((String) -> Unit)? = null, onDismiss: () -> Unit) {
    val answers = remember { mutableStateListOf<Int?>(null, null, null) }
    var saved by remember { mutableStateOf(false) }
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
                            // "Never" for the first question answers the other two: they are about drinking days.
                            val never = qi > 0 && answers[0] == 0
                            FilterChip(
                                selected = answers[qi] == i,
                                enabled = !never,
                                onClick = {
                                    val was = answers[qi]
                                    answers[qi] = i
                                    if (qi == 0 && i == 0) { answers[1] = 0; answers[2] = 0 }
                                    // Changed from "Never": the other two need real answers again.
                                    if (qi == 0 && i != 0 && was == 0) { answers[1] = null; answers[2] = null }
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                if (answers.all { it != null }) {
                    val score = answers.sumOf { it!! }
                    val risk = if (sex == Sex.FEMALE) 3 else 4
                    val level = when {
                        score == 0 -> 0
                        score < risk -> 1
                        score < 8 -> 2
                        else -> 3
                    }
                    Text(stringResource(R.string.audit_score, score), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(listOf(R.string.audit_none, R.string.audit_low, R.string.audit_risk, R.string.audit_high)[level]),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Saved only if the person asks: one line with the score, never the answers.
                    if (onSaveNote != null) {
                        val line = stringResource(R.string.audit_note, score, stringArrayResource(R.array.audit_levels)[level])
                        TextButton(enabled = !saved, onClick = { onSaveNote(line); saved = true }) {
                            Text(stringResource(if (saved) R.string.audit_saved else R.string.audit_save))
                        }
                    }
                    if (score >= risk) Text(stringResource(R.string.alcohol_tips), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
