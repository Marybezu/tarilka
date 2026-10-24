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
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.water_target, (targets.waterMl / 1000).f1()), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { ActivitySection(vm) }
        item { ModelSection(ai) }
        item { DataSection(vm) }
        item { AppearanceSection(vm) }
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
    val note by ai.importNote.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteOriginal by remember { mutableStateOf(true) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ai.import(uri, deleteOriginal)
    }

    Section(title = stringResource(R.string.ai_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_explain), style = MaterialTheme.typography.bodyMedium)
            note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val s = state
            when {
                importing != null -> {
                    LinearProgressIndicator(progress = { importing ?: 0f }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.ai_importing), style = MaterialTheme.typography.bodySmall)
                }
                s is ModelStore.State.Ready -> {
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
                else -> {
                    if (s is ModelStore.State.Failed) {
                        Text(stringResource(R.string.ai_failed, s.reason), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.ai_steps, ModelStore.E2B_FILE), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ModelStore.E2B_PAGE + "/tree/main")),
                            )
                        }
                    }) { Text(stringResource(R.string.ai_open_page)) }
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.ai_import)) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { deleteOriginal = !deleteOriginal },
                    ) {
                        androidx.compose.material3.Checkbox(checked = deleteOriginal, onCheckedChange = { deleteOriginal = it })
                        Text(stringResource(R.string.ai_delete_original), style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun ActivitySection(vm: MainViewModel) {
    val stepsOn by vm.stepsEnabled.collectAsState()
    val syncOn by vm.healthSync.collectAsState()
    val hcAvailable = vm.healthAvailable
    val sensorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshSteps()
    }
    // One system dialog at a time: Health Connect first, then the step sensor.
    val hcLauncher = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.containsAll(ua.tarilka.health.HealthSync.WRITE)) vm.setHealthSync(true)
        if (vm.stepsEnabled.value && vm.stepSensorPresent) {
            sensorLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            vm.refreshSteps()
        }
    }

    Section(title = stringResource(R.string.activity_section)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SwitchRow(stringResource(R.string.show_steps), stringResource(R.string.show_steps_hint), stepsOn) { on ->
                vm.setStepsEnabled(on)
                if (on) {
                    when {
                        hcAvailable -> hcLauncher.launch(setOf(ua.tarilka.health.HealthSync.READ_STEPS))
                        vm.stepSensorPresent -> sensorLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                }
            }
            if (hcAvailable) {
                SwitchRow(stringResource(R.string.hc_sync), stringResource(R.string.hc_sync_hint), syncOn) { on ->
                    if (on) hcLauncher.launch(ua.tarilka.health.HealthSync.WRITE + ua.tarilka.health.HealthSync.READ_STEPS)
                    else vm.setHealthSync(false)
                }
            } else {
                Text(stringResource(R.string.hc_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(vm: MainViewModel) {
    var mode by remember { mutableStateOf(vm.themeMode) }
    val dynamic by vm.dynamicColor.collectAsState()
    Section(title = stringResource(R.string.appearance)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark).forEachIndexed { i, res ->
                FilterChip(mode == i, { mode = i; vm.setThemeMode(i) }, { Text(stringResource(res)) })
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            Spacer(Modifier.height(6.dp))
            SwitchRow(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_hint), dynamic, vm::setDynamicColor)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DataSection(vm: MainViewModel) {
    val message by vm.dataMessage.collectAsState()
    val pending by vm.pendingImport.collectAsState()
    val encrypted by vm.encryptedImport.collectAsState()
    val wrong by vm.wrongPassword.collectAsState()
    val date = java.time.LocalDate.now().toString()
    var askPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri, password)
        password = ""
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri)
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readImport(uri)
    }
    Section(title = stringResource(R.string.data_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.data_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { askPassword = true }) { Text(stringResource(R.string.export_backup)) }
                OutlinedButton(onClick = { importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                    Text(stringResource(R.string.import_backup))
                }
                OutlinedButton(onClick = { exportCsv.launch("tarilka-diary-$date.csv") }) { Text(stringResource(R.string.export_csv)) }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (askPassword) {
        var repeat by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text(stringResource(R.string.export_backup)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.password_hint), style = MaterialTheme.typography.bodySmall)
                    PasswordField(stringResource(R.string.password), password) { password = it }
                    if (password.isNotEmpty()) PasswordField(stringResource(R.string.password_repeat), repeat) { repeat = it }
                }
            },
            confirmButton = {
                TextButton(enabled = password.isEmpty() || password == repeat, onClick = {
                    askPassword = false
                    exportJson.launch("tarilka-$date.json")
                }) { Text(stringResource(if (password.isEmpty()) R.string.export_plain else R.string.export_encrypted)) }
            },
            dismissButton = { TextButton(onClick = { askPassword = false; password = "" }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    encrypted?.let {
        var pw by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.encryptedImport.value = null },
            title = { Text(stringResource(R.string.import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.password_enter), style = MaterialTheme.typography.bodySmall)
                    PasswordField(stringResource(R.string.password), pw) { pw = it }
                    if (wrong) Text(stringResource(R.string.password_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { vm.unlockImport(pw) }) { Text(stringResource(R.string.unlock)) } },
            dismissButton = { TextButton(onClick = { vm.encryptedImport.value = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    pending?.let { (_, s) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.pendingImport.value = null },
            title = { Text(stringResource(R.string.import_title)) },
            text = { Text(stringResource(R.string.import_confirm, s.diary, s.fluids, s.weights, s.foods)) },
            confirmButton = {
                TextButton(onClick = vm::confirmImport) {
                    Text(stringResource(R.string.import_replace), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { vm.pendingImport.value = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
