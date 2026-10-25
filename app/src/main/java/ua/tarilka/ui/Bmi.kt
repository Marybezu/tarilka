package ua.tarilka.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import ua.tarilka.R
import ua.tarilka.data.Nutrition

/**
 * Body mass index with the WHO category, and what it does and does not say.
 * Under 18 the adult categories do not apply, so only the number is shown.
 */
@Composable
fun BmiLine(bmi: Double, age: Int, modifier: Modifier = Modifier) {
    var info by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val text = if (age < 18) {
            stringResource(R.string.bmi_teen, bmi.f1())
        } else {
            stringResource(R.string.bmi_line, bmi.f1(), stringArrayResource(R.array.bmi_classes)[Nutrition.bmiClass(bmi)])
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { info = true }) { Text(stringResource(R.string.what_is_it)) }
    }
    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            title = { Text(stringResource(R.string.bmi_title)) },
            text = {
                Text(
                    stringResource(R.string.bmi_info),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { info = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}
