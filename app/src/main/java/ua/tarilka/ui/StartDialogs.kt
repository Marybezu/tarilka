package ua.tarilka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.tarilka.CrashLog
import ua.tarilka.R

/** Shown once at the very first start: what the app does, that everything stays on the phone, what is optional. */
@Composable
fun IntroDialog(onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pages = listOf(R.string.intro_1_title to R.string.intro_1, R.string.intro_2_title to R.string.intro_2, R.string.intro_3_title to R.string.intro_3)
    val (title, text) = pages[page]
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
                Text("${page + 1} / ${pages.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (page < pages.lastIndex) page++ else onDone() }) {
                Text(stringResource(if (page < pages.lastIndex) R.string.next else R.string.intro_start))
            }
        },
        dismissButton = if (page < pages.lastIndex) {
            { TextButton(onClick = onDone) { Text(stringResource(R.string.skip)) } }
        } else null,
    )
}

/** After a crash: offers to send the technical description with feedback; the text is shown in full first. */
@Composable
fun CrashDialog() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(CrashLog.pending(context)) }
    val t = text ?: return
    AlertDialog(
        onDismissRequest = { CrashLog.clear(context); text = null },
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.crash_text), style = MaterialTheme.typography.bodyMedium)
                Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Feedback.open(context, context.getString(R.string.crash_issue_title), context.getString(R.string.crash_issue_body) + "\n\n```\n" + t + "\n```")
                CrashLog.clear(context)
                text = null
            }) { Text(stringResource(R.string.crash_send)) }
        },
        dismissButton = { TextButton(onClick = { CrashLog.clear(context); text = null }) { Text(stringResource(R.string.crash_no)) } },
    )
}
