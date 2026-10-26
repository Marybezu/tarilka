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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
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
                    if (targets.showKcal) {
                        stringResource(
                            R.string.targets_text,
                            targets.kcal.f0(), targets.protein.f0(), targets.fat.f0(), targets.carbs.f0(), targets.fiber.f0(),
                        )
                    } else {
                        stringResource(R.string.targets_text_teen, targets.protein.f0(), targets.fiber.f0())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (targets.customRaised) {
                    Text(stringResource(R.string.custom_raised), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                targets.limit?.let { l ->
                    Text(stringResource(limitText(l)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.water_target, (targets.waterMl / 1000).f1()), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.water_own_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (profile.isSet) BmiLine(targets.bmi, profile.age, targets.limit == ua.tarilka.data.Limit.PREGNANT)
            }
        }
        item { ActivitySection(vm) }
        item { SelfSection(vm) }
        item { RemindersSection() }
        item { ModelSection(ai) }
        item { MissingSection(vm) }
        item { DataSection(vm) }
        item { ProtectionSection() }
        item { AppearanceSection(vm) }
        item { LanguageSection() }
        item {
            Section(title = stringResource(R.string.about)) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val title = stringResource(R.string.feedback_title)
                val body = stringResource(R.string.feedback_body, Feedback.version(context))
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { Feedback.open(context, title, body) }) { Text(stringResource(R.string.feedback)) }
                Text(stringResource(R.string.feedback_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var pregnant by remember(profile) { mutableStateOf(profile.pregnant) }

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
            if (sex == Sex.FEMALE) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { pregnant = !pregnant },
                ) {
                    androidx.compose.material3.Switch(checked = pregnant, onCheckedChange = { pregnant = it })
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.pregnant), style = MaterialTheme.typography.bodyMedium)
                }
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
            // Weight loss is not offered in pregnancy and breastfeeding, under 18 or with a BMI under 18.5.
            val limit = if (weightN != null && heightN != null && heightN > 0 && ageN != null) {
                ua.tarilka.data.Nutrition.limitFor(sex, pregnant, ageN, ua.tarilka.data.Nutrition.bmi(weightN, heightN))
            } else null
            // Shown and saved as "Maintain" while a limit applies; the choice itself is kept (half-typed values pass).
            val shown = if (limit != null && goal == Goal.LOSE) Goal.MAINTAIN else goal
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(shown == Goal.LOSE, { goal = Goal.LOSE }, { Text(stringResource(R.string.goal_lose)) }, enabled = limit == null)
                FilterChip(shown == Goal.MAINTAIN, { goal = Goal.MAINTAIN }, { Text(stringResource(R.string.goal_maintain)) })
                FilterChip(goal == Goal.GAIN, { goal = Goal.GAIN }, { Text(stringResource(R.string.goal_gain)) })
            }
            limit?.let { Text(stringResource(limitText(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Field(stringResource(R.string.custom_kcal), custom, { custom = it })
            Text(stringResource(R.string.custom_kcal_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        Profile(
                            sex = sex, age = ageN!!, heightCm = heightN!!, weightKg = weightN!!,
                            activity = activity, goal = if (limit != null && goal == Goal.LOSE) Goal.MAINTAIN else goal,
                            customKcal = custom.toNumber()?.toInt()?.takeIf { it in 800..6000 },
                            pregnant = sex == Sex.FEMALE && pregnant,
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
    var deleteOriginal by remember { mutableStateOf(false) }
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
                    Text(stringResource(R.string.ai_ready, s.sizeMb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
                    fun open(url: String) = runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    }
                    // A direct link to the one file that fits: the page lists many chip-specific variants.
                    Button(onClick = { open(ModelStore.E2B_DOWNLOAD) }) { Text(stringResource(R.string.ai_download)) }
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.ai_import)) }
                    TextButton(onClick = { open(ModelStore.E2B_PAGE) }) { Text(stringResource(R.string.ai_open_page)) }
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

/** Optional reminders; each one asks for the notification permission only when switched on. */
@Composable
private fun RemindersSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = (context.applicationContext as ua.tarilka.TarilkaApp).prefs
    var meals by remember { mutableStateOf((0..2).map { prefs.mealReminder(it) }) }
    var water by remember { mutableStateOf(prefs.waterReminder) }
    var sleep by remember { mutableStateOf(prefs.sleepReminder) }
    var weekly by remember { mutableStateOf(prefs.weeklyReminder) }
    var denied by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pending?.invoke() else denied = true
        pending = null
    }
    fun apply() = ua.tarilka.notify.Reminders.schedule(context)
    /** Runs [turnOn] once notifications are allowed. */
    fun whenAllowed(turnOn: () -> Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) { turnOn(); denied = false } else { pending = turnOn; permission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }
    fun pickTime(initial: java.time.LocalTime, onPicked: (java.time.LocalTime) -> Unit) {
        android.app.TimePickerDialog(context, { _, h, m -> onPicked(java.time.LocalTime.of(h, m)) }, initial.hour, initial.minute, true).show()
    }
    val defaults = listOf(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(14, 0), java.time.LocalTime.of(19, 0))

    Section(title = stringResource(R.string.reminders_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.reminders_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (meal in 0..2) {
                val t = meals[meal]
                fun set(v: java.time.LocalTime?) {
                    prefs.setMealReminder(meal, v)
                    meals = meals.toMutableList().also { it[meal] = v }
                    apply()
                }
                TimeSwitchRow(mealName(meal), t, onTime = { pickTime(t ?: defaults[meal], ::set) }) { on ->
                    if (on) whenAllowed { set(defaults[meal]) } else set(null)
                }
            }
            SwitchRow(stringResource(R.string.remind_water_title), stringResource(R.string.remind_water_hint), water) { on ->
                fun set(v: Boolean) { prefs.waterReminder = v; water = v; apply() }
                if (on) whenAllowed { set(true) } else set(false)
            }
            TimeSwitchRow(stringResource(R.string.remind_sleep_title), sleep, onTime = { pickTime(sleep ?: java.time.LocalTime.of(22, 30)) { prefs.sleepReminder = it; sleep = it; apply() } }) { on ->
                fun set(v: java.time.LocalTime?) { prefs.sleepReminder = v; sleep = v; apply() }
                if (on) whenAllowed { set(java.time.LocalTime.of(22, 30)) } else set(null)
            }
            // Cycle reminders: shown only when cycle tracking is on.
            if (prefs.cycleEnabled.collectAsState().value) {
                var before by remember { mutableStateOf(prefs.cycleRemindDays) }
                var late by remember { mutableStateOf(prefs.cycleLateRemind) }
                Text(stringResource(R.string.remind_cycle_title), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-1, 1, 2, 3).forEach { n ->
                        FilterChip(
                            selected = before == n,
                            onClick = {
                                fun set() { prefs.cycleRemindDays = n; before = n; apply() }
                                if (n > 0) whenAllowed(::set) else set()
                            },
                            label = { Text(if (n < 0) stringResource(R.string.off) else stringResource(R.string.days_before, n)) },
                        )
                    }
                }
                SwitchRow(stringResource(R.string.remind_cycle_late_title), stringResource(R.string.remind_cycle_late_hint), late) { on ->
                    fun set(v: Boolean) { prefs.cycleLateRemind = v; late = v; apply() }
                    if (on) whenAllowed { set(true) } else set(false)
                }
            }
            SwitchRow(stringResource(R.string.remind_weekly_title), stringResource(R.string.remind_weekly_hint), weekly) { on ->
                fun set(v: Boolean) { prefs.weeklyReminder = v; weekly = v; apply() }
                if (on) whenAllowed { set(true) } else set(false)
            }
            if (denied) Text(stringResource(R.string.notif_denied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** A reminder with a time: tap the time to change it. */
@Composable
private fun TimeSwitchRow(title: String, time: java.time.LocalTime?, onTime: () -> Unit, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (time != null) TextButton(onClick = onTime) { Text("%02d:%02d".format(time.hour, time.minute)) }
        androidx.compose.material3.Switch(checked = time != null, onCheckedChange = onChange)
    }
}

/** Optional self-observation: sleep and "why I eat". Both off by default. */
@Composable
private fun SelfSection(vm: MainViewModel) {
    val sleepOn by vm.sleepEnabled.collectAsState()
    val reasonsOn by vm.reasonsEnabled.collectAsState()
    val sleepLauncher = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
    ) { vm.refreshSleep() }
    Section(title = stringResource(R.string.self_section)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SwitchRow(
                stringResource(R.string.sleep_title),
                stringResource(if (vm.healthAvailable) R.string.sleep_hint_hc else R.string.sleep_hint),
                sleepOn,
            ) { on ->
                vm.setSleepEnabled(on)
                // Health Connect is optional: without the permission sleep is simply typed in.
                if (on && vm.healthAvailable) sleepLauncher.launch(setOf(ua.tarilka.health.HealthSync.READ_SLEEP))
            }
            SwitchRow(stringResource(R.string.reasons_title), stringResource(R.string.reasons_hint), reasonsOn) { vm.setReasonsEnabled(it) }
            val timesOn by vm.mealTimes.collectAsState()
            SwitchRow(stringResource(R.string.meal_times_title), stringResource(R.string.meal_times_hint), timesOn) { vm.setMealTimes(it) }
            val workoutsOn by vm.workoutsEnabled.collectAsState()
            SwitchRow(stringResource(R.string.workouts_title), stringResource(R.string.workouts_hint), workoutsOn) { vm.setWorkoutsEnabled(it) }
            val cycleOn by vm.cycleEnabled.collectAsState()
            val cycleLauncher = rememberLauncherForActivityResult(
                androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
            ) { vm.refreshCycle() }
            SwitchRow(stringResource(R.string.cycle_title), stringResource(R.string.cycle_hint), cycleOn) { on ->
                vm.setCycleEnabled(on)
                // Optional: read periods another app wrote to Health Connect.
                if (on && vm.healthAvailable) cycleLauncher.launch(setOf(ua.tarilka.health.HealthSync.READ_PERIODS))
            }
            val bowelOn by vm.bowelEnabled.collectAsState()
            SwitchRow(stringResource(R.string.bowel_short), stringResource(R.string.bowel_hint), bowelOn) { vm.setBowelEnabled(it) }
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
        Spacer(Modifier.height(6.dp))
        SwitchRow(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_hint), dynamic, vm::setDynamicColor)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DataSection(vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val message by vm.dataMessage.collectAsState()
    val pending by vm.pendingImport.collectAsState()
    val encrypted by vm.encryptedImport.collectAsState()
    val wrong by vm.wrongPassword.collectAsState()
    val date = java.time.LocalDate.now().toString()
    var askPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    // Kept across rotation: the dialog, and what to write while the system "Save as" screen is open.
    var pdfDialog by rememberSaveable { mutableStateOf(false) }
    var pdfRequest by rememberSaveable { mutableStateOf<PdfRequest?>(null) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val r = pdfRequest
        if (uri != null && r != null) vm.exportPdf(context, uri, r.from, r.to, r.parts, r.profile)
        pdfRequest = null
    }
    /** null = no dialog, false = clear the diary, true = delete all personal data */
    var clearing by remember { mutableStateOf<Boolean?>(null) }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        // The password is kept in the view model: after rotation the UI state is new,
        // and an empty password here must never silently mean "save unencrypted".
        val pw = vm.exportPassword
        if (uri != null) {
            if (pw == null) vm.dataMessage.value = context.getString(R.string.export_retry) else vm.exportBackup(uri, pw)
        }
        vm.exportPassword = null
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
                OutlinedButton(onClick = { pdfDialog = true }) { Text(stringResource(R.string.pdf_button)) }
            }
            AutoBackupRow(vm)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clearing = false }) {
                    Text(stringResource(R.string.clear_diary), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { clearing = true }) {
                    Text(stringResource(R.string.clear_all), color = MaterialTheme.colorScheme.error)
                }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (pdfDialog) {
        PdfDialog(vm, onDismiss = { pdfDialog = false }) { req ->
            pdfRequest = req
            pdfDialog = false
            savePdf.launch(if (req.from == req.to) "tarilka-${req.from}.pdf" else "tarilka-${req.from}_${req.to}.pdf")
        }
    }
    clearing?.let { everything ->
        var alsoHc by remember { mutableStateOf(true) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { clearing = null },
            title = { Text(stringResource(if (everything) R.string.clear_all else R.string.clear_diary)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (everything) R.string.clear_all_text else R.string.clear_diary_text), style = MaterialTheme.typography.bodyMedium)
                    // Offered even if sync is off now: records written earlier may still be there.
                    if (vm.healthAvailable) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { alsoHc = !alsoHc }) {
                            androidx.compose.material3.Checkbox(checked = alsoHc, onCheckedChange = { alsoHc = it })
                            Text(stringResource(R.string.clear_hc), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    TextButton(onClick = { clearing = null; askPassword = true }) { Text(stringResource(R.string.export_first)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.clearData(everything, alsoHc && vm.healthAvailable); clearing = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { clearing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (askPassword) {
        var repeat by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text(stringResource(R.string.export_backup)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.password_hint), style = MaterialTheme.typography.bodySmall)
                    // Cycle data is especially personal: say so where the choice is made.
                    if (vm.cycleEnabled.collectAsState().value) {
                        Text(stringResource(R.string.password_cycle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    PasswordField(stringResource(R.string.password), password) { password = it }
                    if (password.isNotEmpty()) PasswordField(stringResource(R.string.password_repeat), repeat) { repeat = it }
                    if (password.isNotEmpty() && password.length < 8) Text(stringResource(R.string.auto_backup_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            },
            confirmButton = {
                TextButton(enabled = password.isEmpty() || (password.length >= 8 && password == repeat), onClick = {
                    askPassword = false
                    vm.exportPassword = password
                    exportJson.launch("tarilka-$date.json")
                }) { Text(stringResource(if (password.isEmpty()) R.string.export_plain else R.string.export_encrypted)) }
            },
            dismissButton = { TextButton(onClick = { askPassword = false; password = "" }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    encrypted?.let {
        var pw by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.encryptedImport.value = null; vm.wrongPassword.value = false },
            title = { Text(stringResource(R.string.import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.password_enter), style = MaterialTheme.typography.bodySmall)
                    PasswordField(stringResource(R.string.password), pw) { pw = it }
                    if (wrong) Text(stringResource(R.string.password_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { vm.unlockImport(pw) }) { Text(stringResource(R.string.unlock)) } },
            dismissButton = {
                TextButton(onClick = { vm.encryptedImport.value = null; vm.wrongPassword.value = false }) { Text(stringResource(R.string.cancel)) }
            },
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

/** Foods people searched for and didn't find; shown only when there are some. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MissingSection(vm: MainViewModel) {
    val missing by vm.missing.collectAsState()
    val text = missing.entries.sortedByDescending { it.value }.joinToString("\n") { "${it.key} (${it.value})" }
    Section(title = stringResource(R.string.missing_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.missing_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (missing.isEmpty()) {
                Text(stringResource(R.string.missing_empty), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            val context = androidx.compose.ui.platform.LocalContext.current
            val issueTitle = stringResource(R.string.missing_issue_title)
            val issueBody = stringResource(R.string.missing_issue_body, Feedback.version(context), text)
            androidx.compose.foundation.layout.FlowRow {
                TextButton(onClick = { Feedback.open(context, issueTitle, issueBody) }) { Text(stringResource(R.string.send_list)) }
                TextButton(onClick = { Feedback.copyPrivate(context, text) }) {
                    Text(stringResource(R.string.copy_list))
                }
                TextButton(onClick = vm::clearMissing) { Text(stringResource(R.string.clear)) }
            }
        }
    }
}

fun limitText(l: ua.tarilka.data.Limit): Int = when (l) {
    ua.tarilka.data.Limit.PREGNANT -> R.string.limit_pregnant
    ua.tarilka.data.Limit.TEEN -> R.string.limit_teen
    ua.tarilka.data.Limit.UNDERWEIGHT -> R.string.limit_underweight
}

/** Optional app protection: encrypted diary, opened with fingerprint or the phone PIN. */
@Composable
private fun ProtectionSection() {
    val activity = androidx.compose.ui.platform.LocalContext.current as androidx.fragment.app.FragmentActivity
    val on by Protection.enabled.collectAsState()
    val msg by Protection.message.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    Section(title = stringResource(R.string.protect_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { confirm = true }) {
                Text(stringResource(R.string.protect_switch), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = on, onCheckedChange = { confirm = true })
            }
            Text(stringResource(R.string.protect_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The one way to lose the diary: always in sight while protection is on.
            if (on) {
                Text(
                    "⚠ " + stringResource(R.string.protect_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
            msg?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            val prefs = (activity.application as ua.tarilka.TarilkaApp).prefs
            val noShots by prefs.noScreenshots.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { prefs.setNoScreenshots(!noShots) }) {
                Text(stringResource(R.string.no_screenshots), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = noShots, onCheckedChange = { prefs.setNoScreenshots(it) })
            }
            Text(stringResource(R.string.no_screenshots_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(if (on) R.string.protect_off_title else R.string.protect_on_title)) },
            text = { Text(stringResource(if (on) R.string.protect_off_text else R.string.protect_on_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    Protection.message.value = null
                    if (on) Protection.disable(activity) else Protection.enable(activity)
                }) { Text(stringResource(if (on) R.string.protect_off_confirm else R.string.protect_on_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** PDF report: period, what to include (with ready-made sets), and whether to add the profile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PdfDialog(vm: MainViewModel, onDismiss: () -> Unit, onCreate: (PdfRequest) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val today = java.time.LocalDate.now()
    // 0 day, 1 week, 2 month, 3 own dates, 4 all time
    var span by rememberSaveable { mutableStateOf(2) }
    var day by rememberSaveable { mutableStateOf(today) }
    var from by rememberSaveable { mutableStateOf(today.minusDays(29)) }
    var to by rememberSaveable { mutableStateOf(today) }
    var parts by rememberSaveable { mutableStateOf(ReportPresets.DIETITIAN) }
    var profile by rememberSaveable { mutableStateOf(false) }
    var first by remember { mutableStateOf<java.time.LocalDate?>(null) }
    LaunchedEffect(Unit) { first = vm.firstEntryDay() }
    fun range(): Pair<java.time.LocalDate, java.time.LocalDate> = when (span) {
        0 -> day to day
        1 -> today.minusDays(6) to today
        2 -> today.minusDays(29) to today
        3 -> minOf(from, to) to maxOf(from, to)
        else -> (first ?: today) to today
    }
    fun pick(initial: java.time.LocalDate, onPicked: (java.time.LocalDate) -> Unit) {
        android.app.DatePickerDialog(context, { _, y, m, d -> onPicked(java.time.LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
            .apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    }
    val names = mapOf(
        ReportPart.SUMMARY to R.string.pdf_part_summary, ReportPart.FOOD to R.string.pdf_part_food,
        ReportPart.REASONS to R.string.pdf_part_reasons, ReportPart.FLUID to R.string.m_fluid,
        ReportPart.WEIGHT to R.string.m_weight, ReportPart.SLEEP to R.string.m_sleep,
        ReportPart.ACTIVITY to R.string.pdf_part_activity, ReportPart.BOWEL to R.string.m_bowel,
        ReportPart.CYCLE to R.string.cycle_title, ReportPart.NOTES to R.string.pdf_part_notes,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_button)) },
        text = {
            Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.pdf_period_title), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.pdf_span_day, R.string.period_week, R.string.period_month, R.string.pdf_span_own, R.string.pdf_span_all)
                        .forEachIndexed { i, res -> FilterChip(span == i, { span = i }, { Text(stringResource(res)) }) }
                }
                when (span) {
                    0 -> TextButton(onClick = { pick(day) { day = it } }) { Text(shortDate(day.toEpochDay())) }
                    3 -> Row {
                        TextButton(onClick = { pick(from) { from = it } }) { Text(shortDate(from.toEpochDay())) }
                        Text("–", modifier = Modifier.align(Alignment.CenterVertically))
                        TextButton(onClick = { pick(to) { to = it } }) { Text(shortDate(to.toEpochDay())) }
                    }
                    else -> Unit
                }
                Text(stringResource(R.string.pdf_what), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(parts == ReportPresets.DIETITIAN, { parts = ReportPresets.DIETITIAN }, { Text(stringResource(R.string.pdf_preset_diet)) })
                    FilterChip(parts == ReportPresets.CYCLE, { parts = ReportPresets.CYCLE }, { Text(stringResource(R.string.pdf_preset_cycle)) })
                    FilterChip(parts == ReportPresets.ALL, { parts = ReportPresets.ALL }, { Text(stringResource(R.string.pdf_preset_all)) })
                }
                ReportPart.entries.forEach { p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { parts = if (p in parts) parts - p else parts + p },
                    ) {
                        androidx.compose.material3.Checkbox(checked = p in parts, onCheckedChange = { parts = if (it) parts + p else parts - p })
                        Text(stringResource(names.getValue(p)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { profile = !profile }) {
                    androidx.compose.material3.Checkbox(checked = profile, onCheckedChange = { profile = it })
                    Text(stringResource(R.string.pdf_with_profile), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.pdf_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = parts.isNotEmpty(), onClick = {
                val (a, b) = range()
                onCreate(PdfRequest(a, b, parts, profile))
            }) { Text(stringResource(R.string.pdf_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** What the PDF report should contain; survives rotation while the system "Save as" screen is open. */
data class PdfRequest(
    val from: java.time.LocalDate,
    val to: java.time.LocalDate,
    val parts: Set<ReportPart>,
    val profile: Boolean,
) : java.io.Serializable

/** The weekly encrypted copy: where, when last, a problem if any; set up with a password and a folder. */
@Composable
private fun AutoBackupRow(vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as ua.tarilka.TarilkaApp
    val version by vm.autoBackupVersion.collectAsState()
    var setup by rememberSaveable { mutableStateOf(false) }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    // The password waits in the view model while the folder picker is open: only in memory, and it survives
    // turning the phone.
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pw = vm.autoBackupPending
        if (uri != null && pw != null) vm.setUpAutoBackup(uri, pw)
        vm.autoBackupPending = null
        pw1 = ""; pw2 = ""
    }
    val on = remember(version) { ua.tarilka.data.AutoBackup.isSetUp(app.prefs) }
    val folder = remember(version) { if (on) ua.tarilka.data.AutoBackup.folderName(context, app.prefs) else null }
    val problem = remember(version) { app.prefs.autoBackupProblem }
    val last = remember(version) { app.prefs.autoBackupLast.takeIf { it > 0 } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.auto_backup_title), style = MaterialTheme.typography.titleSmall)
        if (on) {
            Text(
                stringResource(R.string.auto_backup_on, folder ?: "—", last?.let { shortDate(it) } ?: "—"),
                style = MaterialTheme.typography.bodySmall,
            )
            if (problem) Text(stringResource(R.string.auto_backup_problem), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.autoBackupNow() }) { Text(stringResource(R.string.auto_backup_now)) }
                TextButton(onClick = { vm.turnOffAutoBackup() }) { Text(stringResource(R.string.auto_backup_off)) }
            }
        } else {
            Text(stringResource(R.string.auto_backup_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { setup = true }) { Text(stringResource(R.string.auto_backup_setup)) }
        }
    }
    if (setup) {
        val ok = pw1.length >= 8 && pw1 == pw2
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { setup = false },
            title = { Text(stringResource(R.string.auto_backup_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.auto_backup_setup_text), style = MaterialTheme.typography.bodyMedium)
                    PasswordField(stringResource(R.string.password), pw1) { pw1 = it }
                    PasswordField(stringResource(R.string.auto_backup_repeat), pw2) { pw2 = it }
                    if (pw1.isNotEmpty() && pw1.length < 8) Text(stringResource(R.string.auto_backup_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    else if (pw2.isNotEmpty() && pw1 != pw2) Text(stringResource(R.string.auto_backup_mismatch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = { vm.autoBackupPending = pw1; setup = false; pickFolder.launch(null) }) { Text(stringResource(R.string.auto_backup_choose)) }
            },
            dismissButton = { TextButton(onClick = { setup = false; pw1 = ""; pw2 = "" }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
