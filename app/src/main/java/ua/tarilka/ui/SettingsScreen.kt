package ua.tarilka.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import ua.tarilka.R
import ua.tarilka.ai.ModelStore
import ua.tarilka.data.Goal
import ua.tarilka.data.Profile
import ua.tarilka.data.Sex

@Composable
fun SettingsScreen(vm: MainViewModel, ai: AiViewModel, padding: PaddingValues) {
    val profile by vm.profile.collectAsState()
    val targets by vm.targets.collectAsState()
    LaunchedEffect(Unit) { ai.refreshModel() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp)) }
        item { ProfileSection(profile, onSave = vm::saveProfile) }
        item {
            Section(title = stringResource(R.string.your_targets)) {
                Text(
                    stringResource(
                        R.string.targets_text,
                        targets.kcal.f0(), targets.protein.f0(), targets.fat.f0(), targets.carbs.f0(), targets.fiber.f0(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item { ModelSection(ai) }
        item { LanguageSection() }
        item {
            Section(title = stringResource(R.string.about)) {
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileSection(profile: Profile, onSave: (Profile) -> Unit) {
    var sex by remember(profile) { mutableStateOf(profile.sex) }
    var age by remember(profile) { mutableStateOf(profile.age.toString()) }
    var height by remember(profile) { mutableStateOf(profile.heightCm.toString()) }
    var weight by remember(profile) { mutableStateOf(profile.weightKg.f1()) }
    var activity by remember(profile) { mutableStateOf(profile.activity) }
    var goal by remember(profile) { mutableStateOf(profile.goal) }
    var custom by remember(profile) { mutableStateOf(profile.customKcal?.toString() ?: "") }

    val ageN = age.toNumber()?.toInt()
    val heightN = height.toNumber()?.toInt()
    val weightN = weight.toNumber()
    val valid = ageN != null && ageN in 14..100 &&
        heightN != null && heightN in 120..230 &&
        weightN != null && weightN in 30.0..300.0

    Section(title = stringResource(R.string.profile)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(sex == Sex.FEMALE, { sex = Sex.FEMALE }, { Text(stringResource(R.string.female)) })
                FilterChip(sex == Sex.MALE, { sex = Sex.MALE }, { Text(stringResource(R.string.male)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(stringResource(R.string.age), age, { age = it }, Modifier.weight(1f))
                Field(stringResource(R.string.height_cm), height, { height = it }, Modifier.weight(1f))
                Field(stringResource(R.string.weight_kg), weight, { weight = it }, Modifier.weight(1f))
            }
            Text(stringResource(R.string.activity), style = MaterialTheme.typography.labelLarge)
            val labels = listOf(
                R.string.activity_0, R.string.activity_1, R.string.activity_2, R.string.activity_3, R.string.activity_4,
            )
            labels.forEachIndexed { i, res ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { activity = i },
                ) {
                    RadioButton(selected = activity == i, onClick = { activity = i })
                    Text(stringResource(res), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(R.string.goal), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(goal == Goal.LOSE, { goal = Goal.LOSE }, { Text(stringResource(R.string.goal_lose)) })
                FilterChip(goal == Goal.MAINTAIN, { goal = Goal.MAINTAIN }, { Text(stringResource(R.string.goal_maintain)) })
                FilterChip(goal == Goal.GAIN, { goal = Goal.GAIN }, { Text(stringResource(R.string.goal_gain)) })
            }
            Field(stringResource(R.string.custom_kcal), custom, { custom = it })
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        Profile(
                            sex = sex, age = ageN!!, heightCm = heightN!!, weightKg = weightN!!,
                            activity = activity, goal = goal,
                            customKcal = custom.toNumber()?.toInt()?.takeIf { it in 800..6000 },
                        ),
                    )
                },
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

@Composable
private fun ModelSection(ai: AiViewModel) {
    val state by ai.model.collectAsState()
    val importing by ai.importProgress.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ai.import(uri)
    }

    Section(title = stringResource(R.string.ai_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_explain), style = MaterialTheme.typography.bodyMedium)
            when (val s = state) {
                is ModelStore.State.Ready -> {
                    Text(stringResource(R.string.ai_ready, s.file.name, s.sizeMb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    if (confirmDelete) {
                        Row {
                            TextButton(onClick = { ai.deleteModel(); confirmDelete = false }) {
                                Text(stringResource(R.string.ai_delete_confirm), color = MaterialTheme.colorScheme.error)
                            }
                            TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
                        }
                    } else {
                        OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.ai_delete)) }
                    }
                }
                is ModelStore.State.Downloading -> {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.ai_downloading, s.downloadedMb, s.totalMb), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = ai::cancelDownload) { Text(stringResource(R.string.cancel)) }
                }
                else -> {
                    if (s is ModelStore.State.Failed) {
                        Text(stringResource(R.string.ai_failed, s.reason), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (importing != null) {
                        LinearProgressIndicator(progress = { importing ?: 0f }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.ai_importing), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Button(onClick = ai::download) { Text(stringResource(R.string.ai_download)) }
                        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.ai_import)) }
                        Text(stringResource(R.string.ai_import_hint, ModelStore.E2B_PAGE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageSection() {
    var current by remember { mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags()) }
    fun set(tag: String) {
        current = tag
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }
    Section(title = stringResource(R.string.language)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(current.isEmpty(), { set("") }, { Text(stringResource(R.string.lang_system)) })
            FilterChip(current.startsWith("uk"), { set("uk") }, { Text("Українська") })
            FilterChip(current.startsWith("en"), { set("en") }, { Text("English") })
        }
        Spacer(Modifier.height(4.dp))
    }
}
