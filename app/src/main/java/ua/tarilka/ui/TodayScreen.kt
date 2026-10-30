package ua.tarilka.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.background
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import ua.tarilka.ai.ModelStore
import ua.tarilka.data.DiaryEntry
import ua.tarilka.data.aiSafety
import ua.tarilka.data.Tip
import ua.tarilka.data.isUkrainian
import ua.tarilka.ui.theme.LocalMacroColors
import ua.tarilka.ui.theme.Numbers
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun TodayScreen(
    vm: MainViewModel,
    ai: AiViewModel,
    padding: PaddingValues,
    onAdd: (meal: Int) -> Unit,
    onPhoto: (meal: Int) -> Unit,
    onHistory: () -> Unit,
) {
    val day by vm.day.collectAsState()
    val entries by vm.entries.collectAsState()
    val totals by vm.totals.collectAsState()
    val targets by vm.targets.collectAsState()
    val tips by vm.tips.collectAsState()
    val week by vm.week.collectAsState()
    val profile by vm.profile.collectAsState()
    val model by ai.model.collectAsState()
    val advice by ai.advice.collectAsState()
    val adviceLoading by ai.adviceLoading.collectAsState()
    val adviceFor by ai.adviceFor.collectAsState()
    val fluidMl by vm.fluidMl.collectAsState()
    val fluids by vm.fluids.collectAsState()
    val steps by vm.steps.collectAsState()
    val stepsEnabled by vm.stepsEnabled.collectAsState()
    val weight by vm.weightForDay.collectAsState()
    val weightOnDay by vm.weightOnDay.collectAsState()
    val stepsFromHc by vm.stepsFromHc.collectAsState()
    val trend by vm.weeklyTrend.collectAsState()
    val habits by vm.weekHabits.collectAsState()
    val sleep by vm.sleep.collectAsState()
    val sleepOn by vm.sleepEnabled.collectAsState()
    var sleepDialog by remember { mutableStateOf(false) }
    val bowelOn by vm.bowelEnabled.collectAsState()
    val bowel by vm.bowel.collectAsState()
    var bowelDialog by remember { mutableStateOf(false) }
    val workoutsOn by vm.workoutsEnabled.collectAsState()
    val workouts by vm.workouts.collectAsState()
    var workoutDialog by remember { mutableStateOf(false) }
    val activityHint by vm.activityHint.collectAsState()
    val mealTimesOn by vm.mealTimes.collectAsState()
    val cycle by vm.cycle.collectAsState()
    val yesterday by vm.yesterday.collectAsState()
    var cycleDialog by remember { mutableStateOf(false) }
    var cycleSetup by remember { mutableStateOf(false) }
    val periodsKnown = vm.periods.collectAsState().value.isNotEmpty()
    var stepsDialog by remember { mutableStateOf(false) }
    val stepsGoal by vm.stepsGoal.collectAsState()
    var weightDialog by remember { mutableStateOf(false) }
    val note by vm.note.collectAsState()
    var noteDialog by remember { mutableStateOf(false) }
    val macros = LocalMacroColors.current
    val context = LocalContext.current
    var editing by remember { mutableStateOf<DiaryEntry?>(null) }
    var fluidDialog by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.onResume()
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DayHeader(day, onPrev = { vm.shiftDay(-1) }, onNext = { vm.shiftDay(1) }, onToday = vm::today, onHistory = onHistory) }

        if (!profile.isSet) {
            item {
                Section {
                    Text(stringResource(R.string.profile_hint), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Under 18 the ring shows protein instead of calories.
                    if (LocalShowKcal.current) CalorieRing(totals.kcal, targets.kcal, warnOver = targets.limit == null)
                    else CalorieRing(totals.protein, targets.protein, warnOver = false, ofText = stringResource(R.string.ring_protein, targets.protein.f0()))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NutrientBar(stringResource(R.string.protein), totals.protein, targets.protein, macros.protein)
                        if (targets.showKcal) {
                            NutrientBar(stringResource(R.string.fat), totals.fat, targets.fat, macros.fat)
                            NutrientBar(stringResource(R.string.carbs), totals.carbs, targets.carbs, macros.carbs)
                        }
                        NutrientBar(stringResource(R.string.fiber), totals.fiber, targets.fiber, macros.fiber)
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { fluidDialog = true },
                    ) {
                        NutrientBar(
                            "💧 " + stringResource(R.string.water),
                            fluidMl / 1000, targets.waterMl / 1000, macros.carbs,
                            unit = stringResource(R.string.unit_l), decimals = true,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (fluids.isNotEmpty()) {
                        androidx.compose.material3.FilledTonalIconButton(onClick = vm::removeLastFluid) {
                            Text("−", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    FilledTonalButton(onClick = { vm.addFluid(250.0) }) { Text(stringResource(R.string.plus_ml, 250)) }
                }
                if (stepsEnabled && steps != null) {
                    Spacer(Modifier.height(8.dp))
                    val kcal = ua.tarilka.data.Nutrition.stepsKcal(steps!!, weight ?: profile.weightKg)
                    Column(Modifier.clickable { stepsDialog = true }) {
                        NutrientBar("👣 " + stringResource(R.string.steps_title), steps!!.toDouble(), stepsGoal.toDouble(), macros.fiber, unit = "")
                    }
                    Text(
                        (if (LocalShowKcal.current) "≈ ${kcal.f0()} ${stringResource(R.string.unit_kcal)} · " else "") +
                            stringResource(if (stepsFromHc) R.string.steps_from_hc else R.string.steps_from_sensor),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "⚖️ " + (weight?.let { "${it.f1()} ${stringResource(R.string.kg)}" } ?: "—") +
                            (weightOnDay?.minutes?.let { " · %02d:%02d".format(it / 60, it % 60) } ?: "") +
                            (trend?.let { "  ·  " + stringResource(R.string.trend_week, (if (it > 0) "+" else "") + it.f1()) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium.merge(Numbers),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { weightDialog = true }) { Text(stringResource(R.string.weight_record)) }
                }
                cycle?.let { c ->
                    val t = day.toEpochDay()
                    // Water weight goes up before and in the first days of a period: say so next to the weight.
                    val cd = c.cycleDay(t)
                    val nearPeriod = c.inPeriod && (cd ?: 99) <= 3 || c.nextStart?.let { it - t in 0..7 } == true
                    if (nearPeriod) {
                        Text(stringResource(R.string.cycle_weight_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { if (periodsKnown) cycleDialog = true else cycleSetup = true }) {
                        Text(
                            "🌸 " + when {
                                !periodsKnown -> stringResource(R.string.cycle_not_set)
                                c.late(t) -> stringResource(R.string.cycle_late, dayMonth(c.nextStart!!))
                                cd != null -> stringResource(R.string.cycle_day, cd) + (c.nextStart?.let { " · " + stringResource(R.string.cycle_next_short, dayMonth(it)) } ?: "")
                                else -> stringResource(R.string.cycle_title) + " —"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (!periodsKnown) {
                            TextButton(onClick = { cycleSetup = true }) { Text(stringResource(R.string.cycle_set)) }
                        } else if (c.inPeriod && c.lastStart != null && t >= c.lastStart) {
                            // Only on days of the open period: "Ended" on an older day would close an old one.
                            TextButton(onClick = { vm.periodEnded(day) }) { Text(stringResource(R.string.cycle_ended)) }
                        } else if (c.nextStart == null || t >= c.nextStart - 5) {
                            // Around the expected start: one tap.
                            TextButton(onClick = { vm.periodStarted(day) }) { Text(stringResource(R.string.cycle_started)) }
                        } else {
                            // Earlier than expected, or another date: the dialog starts with "Started today".
                            TextButton(onClick = { cycleDialog = true }) { Text(stringResource(R.string.record)) }
                        }
                    }
                }
                if (workoutsOn) {
                    val types = androidx.compose.ui.res.stringArrayResource(R.array.workout_types)
                    val levels = androidx.compose.ui.res.stringArrayResource(R.array.workout_intensity)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "🏃 " + (if (workouts.isEmpty()) stringResource(R.string.workouts_title) + " —" else workouts.map { workoutLine(it, types, levels) }.joinToString("; ")),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { workoutDialog = true }) { Text(stringResource(R.string.add)) }
                    }
                }
                if (bowelOn) {
                    val forms = androidx.compose.ui.res.stringArrayResource(R.array.bowel_forms)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "🌿 " + stringResource(R.string.bowel_short) + ": " + (bowel?.let { b ->
                                when (b.count) {
                                    0 -> stringResource(R.string.bowel_none)
                                    1 -> stringResource(R.string.bowel_once)
                                    2 -> stringResource(R.string.bowel_twice)
                                    else -> stringResource(R.string.bowel_3plus)
                                } +
                                    // Only an unusual form is named; the usual one needs no label.
                                    (b.form?.takeIf { it != 1 }?.let { " · " + forms.getOrElse(it) { "" } } ?: "")
                            } ?: "—"),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { bowelDialog = true }) { Text(stringResource(R.string.record)) }
                    }
                }
                if (sleepOn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Hours and how it felt are separate things: "4 h, felt okay" reads fine, "4 h · okay" did not.
                        val s = sleep
                        Text(
                            "😴 " + (s?.let {
                                stringResource(R.string.sleep_line, if (it.hours < 4) "<4" else if (it.hours > 10) ">10" else if (it.hours % 1.0 == 0.0) it.hours.toInt().toString() else it.hours.f1()) +
                                    (it.quality?.let { q -> SLEEP_FELT.getOrNull(q) }?.let { r -> ", " + stringResource(r) } ?: "") +
                                    (if (it.fromHc) " · Health Connect" else "")
                            } ?: stringResource(R.string.sleep_title) + " —"),
                            style = MaterialTheme.typography.bodyMedium.merge(Numbers),
                            color = if (s != null && s.hours < 6) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { sleepDialog = true }) { Text(stringResource(R.string.record)) }
                    }
                }
            }
        }

        item {
            Section(title = stringResource(R.string.recommendations)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tips.forEach { TipRow(it) }
                    activityHint?.let { level ->
                        val name = stringResource(listOf(R.string.activity_0, R.string.activity_1, R.string.activity_2, R.string.activity_3, R.string.activity_4)[level])
                        Text(stringResource(R.string.activity_hint, name), style = MaterialTheme.typography.bodyMedium)
                        Row {
                            TextButton(onClick = { vm.applyActivityHint(level) }) { Text(stringResource(R.string.update)) }
                            TextButton(onClick = vm::dismissActivityHint) { Text(stringResource(R.string.not_now)) }
                        }
                    }
                    if (model is ModelStore.State.Ready && entries.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        when {
                            adviceLoading && adviceFor == day -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodyMedium)
                            }
                            advice != null && adviceFor == day -> {
                                HorizontalDivider()
                                Text(advice!!, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = ai::clearAdvice) { Text(stringResource(R.string.hide)) }
                            }
                            else -> FilledTonalButton(onClick = {
                                val summary = adviceSummary(context, day, entries, totals, targets, tips, fluidMl, steps, note, mealTimesOn)
                                ai.requestAdvice(summary, isUkrainian(), day)
                            }) {
                                Icon(Icons.Filled.Star, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.ai_advice))
                            }
                        }
                    }
                }
            }
        }

        for (meal in 0..3) {
            val list = entries.filter { it.meal == meal }
            item(key = "meal$meal") {
                MealSection(
                    meal = meal,
                    entries = list,
                    showPhoto = model is ModelStore.State.Ready,
                    onAdd = { onAdd(meal) },
                    onPhoto = { onPhoto(meal) },
                    onEntry = { editing = it },
                    onDeleteAll = { vm.deleteMeal(meal) },
                    onDelete = { vm.deleteEntry(it) },
                    time = if (mealTimesOn && meal != 3 && list.isNotEmpty()) vm.mealTimeOf(meal) else null,
                    onTime = { vm.setMealTime(meal, it) },
                    onRepeat = if (list.isEmpty() && !yesterday[meal].isNullOrEmpty()) { { vm.repeatYesterday(meal) } } else null,
                )
            }
        }

        item(key = "note") {
            Section(
                modifier = Modifier.clickable { noteDialog = true },
                title = stringResource(R.string.note_title),
            ) {
                Text(
                    note.ifEmpty { stringResource(R.string.note_hint) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (note.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        item {
            val withData = week.filter { it.kcal > 0 }
            if (withData.isNotEmpty()) {
                Section(title = stringResource(R.string.week_title)) {
                    val avg = withData.sumOf { it.kcal } / withData.size
                    val avgP = withData.sumOf { it.protein } / withData.size
                    // An average of one or two days says nothing new: the bars are enough until then.
                    if (withData.size >= 3) {
                        Text(
                            if (targets.showKcal) stringResource(R.string.week_avg, avg.f0(), targets.kcal.f0(), avgP.f0(), targets.protein.f0())
                            else stringResource(R.string.week_avg_protein, avgP.f0(), targets.protein.f0()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (targets.showKcal) WeekBars(day, week, targets.kcal, warnOver = targets.limit == null)
                    // Several full days well below what the body needs at rest: a calm word and a pointer to help.
                    val past = withData.filter { it.day < java.time.LocalDate.now().toEpochDay() }
                    if (past.size >= 4 && past.sumOf { it.kcal } / past.size < targets.bmr) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.week_under), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                    }
                    habits?.let {
                        Spacer(Modifier.height(10.dp))
                        HabitChips(it, sex = profile.sex, pregnant = profile.sex == ua.tarilka.data.Sex.FEMALE && profile.pregnant, onSaveNote = vm::appendNoteToday, workoutsTracked = vm.workoutsTracked.collectAsState().value)
                    }
                    TextButton(onClick = onHistory) { Text(stringResource(R.string.summaries_link)) }
                }
            }
        }
    }

    if (weightDialog) {
        var text by remember { mutableStateOf(weightOnDay?.kg?.f1() ?: "") }
        var wTime by remember {
            mutableStateOf(
                weightOnDay?.minutes?.let { java.time.LocalTime.of(it / 60, it % 60) }
                    ?: if (day == LocalDate.now()) java.time.LocalTime.now().withSecond(0).withNano(0) else java.time.LocalTime.of(8, 0),
            )
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { weightDialog = false },
            title = { Text(stringResource(R.string.weight_today)) },
            text = {
                Column {
                    Field(stringResource(R.string.kg), text, { text = it })
                    TimeRow(wTime) { wTime = it }
                    Text(stringResource(R.string.weight_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    weightOnDay?.let { w ->
                        TextButton(onClick = { vm.deleteWeight(w); weightDialog = false }) {
                            Text(stringResource(R.string.delete_record), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = text.toNumber()?.let { it in 25.0..350.0 } == true,
                    onClick = { text.toNumber()?.let { vm.saveWeight(it, day, wTime) }; weightDialog = false },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { weightDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (stepsDialog) StepsDialog(vm) { stepsDialog = false }

    if (cycleDialog) CycleDialog(vm) { cycleDialog = false }
    if (cycleSetup) CycleSetupDialog(vm) { cycleSetup = false }

    if (bowelDialog) {
        BowelDialog(
            initial = bowel,
            onSave = { c, f -> vm.saveBowel(c, f); bowelDialog = false },
            onDelete = { vm.deleteBowel(); bowelDialog = false },
            onDismiss = { bowelDialog = false },
        )
    }

    if (workoutDialog) {
        WorkoutDialog(
            today = workouts,
            weightKg = weight ?: profile.weightKg,
            onAdd = vm::addWorkout,
            onDelete = vm::deleteWorkout,
            onDismiss = { workoutDialog = false },
        )
    }

    if (sleepDialog) {
        SleepDialog(
            initial = sleep,
            onSave = { h, q -> vm.saveSleep(h, q); sleepDialog = false },
            onDelete = { vm.deleteSleep(); sleepDialog = false },
            onDismiss = { sleepDialog = false },
        )
    }

    if (noteDialog) {
        var text by remember { mutableStateOf(note) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { noteDialog = false },
            title = { Text(stringResource(R.string.note_title)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(1000) },
                    placeholder = { Text(stringResource(R.string.note_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { vm.saveNote(text); noteDialog = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { noteDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (fluidDialog) {
        FluidDialog(
            fluids = fluids,
            onAdd = { vm.addFluid(it) },
            onDelete = { vm.deleteFluid(it) },
            onDeleteAll = { vm.deleteFluidsOfDay(); fluidDialog = false },
            onDismiss = { fluidDialog = false },
        )
    }

    editing?.let { e ->
        GramsDialog(
            title = e.name(),
            initial = e.grams,
            food = null,
            onDismiss = { editing = null },
            onConfirm = { vm.updateGrams(e, it); editing = null },
            // Snacks have their own time; other meals change it in the meal header.
            time = if (mealTimesOn && e.meal == 3) java.time.Instant.ofEpochMilli(e.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0) else null,
            onConfirmAt = { g, t -> vm.updateGrams(e, g, t); editing = null },
            extraAction = {
                TextButton(onClick = { vm.deleteEntry(e); editing = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
private fun DayHeader(day: LocalDate, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit, onHistory: () -> Unit) {
    val today = LocalDate.now()
    val label = when (day) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.prev_day)) }
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onToday),
        )
        IconButton(onClick = onNext, enabled = day.isBefore(today)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_day))
        }
        IconButton(onClick = onHistory) {
            Icon(painterResource(R.drawable.ic_calendar), stringResource(R.string.history_title))
        }
    }
}

@Composable
private fun TipRow(tip: Tip) {
    val color = when (tip.kind) {
        Tip.Kind.GOOD -> MaterialTheme.colorScheme.primary
        Tip.Kind.WARN -> MaterialTheme.colorScheme.tertiary
        Tip.Kind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Tips with sources open on tap: where it came from today, and what could be swapped.
    var open by remember { mutableStateOf(false) }
    Column(if (tip.sources.isNotEmpty()) Modifier.clickable { open = !open } else Modifier) {
        Row {
            Text("●", color = color, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(tip.res, *tip.args.toTypedArray()) + if (tip.sources.isNotEmpty()) " " + stringResource(if (open) R.string.tip_less else R.string.tip_where) else "",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (open) {
            val unit = if (tip.sourceUnit != 0) stringResource(tip.sourceUnit) else ""
            Text(
                stringResource(R.string.tip_sources, tip.sources.joinToString(", ") { (n, v) -> "$n ${v.f0()} $unit".trim() }) +
                    (if (tip.res == R.string.tip_fat_high) "\n" + stringResource(R.string.tip_fat_swap) else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun MealSection(
    meal: Int,
    entries: List<DiaryEntry>,
    showPhoto: Boolean,
    onAdd: () -> Unit,
    onPhoto: () -> Unit,
    onEntry: (DiaryEntry) -> Unit,
    onDeleteAll: () -> Unit,
    onDelete: (DiaryEntry) -> Unit,
    time: java.time.LocalTime? = null,
    onTime: (java.time.LocalTime) -> Unit = {},
    /** "Same as yesterday": shown on an empty meal when the day before had one. */
    onRepeat: (() -> Unit)? = null,
) {
    val kcal = entries.sumOf { it.kcal }
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Section(
        title = mealName(meal),
        trailing = {
            // One time for the whole meal: tap to change it for all its foods.
            time?.let { t ->
                TextButton(onClick = {
                    android.app.TimePickerDialog(context, { _, h, m -> onTime(java.time.LocalTime.of(h, m)) }, t.hour, t.minute, true).show()
                }) { Text("%02d:%02d ✎".format(t.hour, t.minute), style = MaterialTheme.typography.labelLarge.merge(Numbers)) }
            }
            if (entries.isNotEmpty() && LocalShowKcal.current) {
                Text(
                    "${kcal.f0()} ${stringResource(R.string.unit_kcal)}",
                    style = MaterialTheme.typography.labelLarge.merge(Numbers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_meal)) },
                            onClick = { menu = false; onDeleteAll() },
                        )
                    }
                }
            }
        },
    ) {
        entries.forEach { e -> androidx.compose.runtime.key(e.id) {
            // A swipe to the left never deletes by itself: it only shows a Delete button, which needs a tap.
            // "Undo" still appears at the bottom. Read the entry as it is now (grams or answers may have changed).
            val current by androidx.compose.runtime.rememberUpdatedState(e)
            var revealed by remember { mutableStateOf(false) }
            var drag by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val minDrag = with(density) { 40.dp.toPx() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragEnd = { if (drag < -minDrag) revealed = true else if (drag > minDrag) revealed = false },
                        ) { _, d -> drag += d }
                    }
                    .clickable { if (revealed) revealed = false else onEntry(e) }
                    .padding(vertical = 6.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (e.fromAi) "✦ " else "") + e.name(),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${e.grams.f0()} ${stringResource(R.string.unit_g)} · " +
                            "${stringResource(R.string.short_protein)} ${e.protein.f0()} " +
                            "${stringResource(R.string.short_fat)} ${e.fat.f0()} " +
                            "${stringResource(R.string.short_carbs)} ${e.carbs.f0()}",
                        style = MaterialTheme.typography.bodySmall.merge(Numbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (revealed) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { revealed = false; onDelete(current) },
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.delete))
                    }
                } else {
                    Text(
                        if (LocalShowKcal.current) e.kcal.f0() else "${e.grams.f0()} ${stringResource(R.string.unit_g)}",
                        style = MaterialTheme.typography.bodyLarge.merge(Numbers),
                    )
                }
            }
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add))
            }
            if (showPhoto) {
                OutlinedButton(onClick = onPhoto) {
                    Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.photo))
                }
            }
        }
        onRepeat?.let { TextButton(onClick = it) { Text(stringResource(R.string.same_as_yesterday)) } }
    }
}

@Composable
private fun WeekBars(day: LocalDate, week: List<ua.tarilka.data.DayTotal>, target: Double?, warnOver: Boolean) {
    val byDay = week.associateBy { it.day }
    val color = LocalMacroColors.current.kcal
    val over = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val max = maxOf((target ?: 0.0) * 1.3, week.maxOfOrNull { it.kcal } ?: 0.0, 1.0)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 6 downTo 0) {
            val d = day.minusDays(i.toLong())
            val k = byDay[d.toEpochDay()]?.kcal ?: 0.0
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                androidx.compose.foundation.Canvas(
                    Modifier
                        .width(18.dp)
                        .height(64.dp),
                ) {
                    drawRect(track)
                    val h = (k / max).toFloat().coerceIn(0f, 1f) * size.height
                    drawRect(
                        if (warnOver && target != null && k > target * 1.1) over else color,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
                        size = androidx.compose.ui.geometry.Size(size.width, h),
                    )
                    if (target != null) {
                        val ty = (size.height - (target / max).toFloat() * size.height)
                        drawLine(
                            androidx.compose.ui.graphics.Color.Gray,
                            androidx.compose.ui.geometry.Offset(0f, ty),
                            androidx.compose.ui.geometry.Offset(size.width, ty),
                            strokeWidth = 2f,
                        )
                    }
                }
                Text(
                    d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Plain-text summary handed to the AI. The rule-based tips are included so it stays consistent. */
private fun adviceSummary(
    context: android.content.Context,
    day: LocalDate,
    entries: List<DiaryEntry>,
    totals: ua.tarilka.data.Totals,
    targets: ua.tarilka.data.Targets,
    tips: List<Tip>,
    fluidMl: Double,
    steps: Long?,
    note: String,
    withTimes: Boolean = true,
): String = buildString {
    val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    val isToday = day == LocalDate.now()
    val dayWord = if (isToday) "today" else "on ${day} (a past day)"
    if (isToday) appendLine("Current time: ${java.time.LocalTime.now().format(time)}.")
    appendLine("Foods eaten $dayWord (" + (if (withTimes) "time, " else "") + "name, amount):")
    entries.forEach {
        val t = java.time.Instant.ofEpochMilli(it.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(time)
        appendLine("- " + (if (withTimes) "$t " else "") + "${it.nameEn} (${it.nameUk}), ${it.grams.f0()} g" + if (targets.showKcal) ", ${it.kcal.f0()} kcal" else "")
    }
    appendLine("Fluids $dayWord: ${fluidMl.f0()} ml (target ${targets.waterMl.f0()} ml).")
    if (steps != null) appendLine("Steps $dayWord: $steps.")
    if (note.isNotBlank()) appendLine("The person's note for that day: ${note.take(300)}")
    appendLine(
        if (targets.showKcal) {
            "Totals: ${totals.kcal.f0()} kcal (target ${targets.kcal.f0()}), protein ${totals.protein.f0()} g " +
                "(target ${targets.protein.f0()}), fat ${totals.fat.f0()} g (target ${targets.fat.f0()}), " +
                "carbs ${totals.carbs.f0()} g (target ${targets.carbs.f0()}), fiber ${totals.fiber.f0()} g (target ${targets.fiber.f0()})."
        } else {
            "Totals: protein ${totals.protein.f0()} g (target ${targets.protein.f0()}), fiber ${totals.fiber.f0()} g " +
                "(target ${targets.fiber.f0()}). The person is under 18: never mention calories or calorie targets."
        },
    )
    appendLine("Important: " + targets.aiSafety())
    if (tips.isNotEmpty()) {
        appendLine("Notes from the app:")
        tips.forEach { appendLine("- " + context.getString(it.res, *it.args.toTypedArray())) }
    }
    append(if (isToday) "Give short practical advice for the rest of the day or for tomorrow." else "Give short practical advice based on that day.")
}

private val SLEEP_QUALITY = listOf(R.string.sleep_poor, R.string.sleep_ok, R.string.sleep_good)
private val SLEEP_FELT = listOf(R.string.sleep_felt_poor, R.string.sleep_felt_ok, R.string.sleep_felt_good)

/** Hours slept last night in two taps; quality is optional. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SleepDialog(
    initial: MainViewModel.SleepShown?,
    onSave: (Double, Int?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var hours by remember { mutableStateOf(initial?.hours?.let { kotlin.math.round(it * 2) / 2 }) }
    var quality by remember { mutableStateOf(initial?.quality) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_question)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 3.5 stands for "under 4 hours", 11 for "over 10".
                    listOf(3.5, 4.0, 5.0, 6.0, 6.5, 7.0, 7.5, 8.0, 9.0, 10.0, 11.0).forEach { h ->
                        androidx.compose.material3.FilterChip(
                            selected = hours == h,
                            onClick = { hours = h },
                            label = { Text(if (h < 4) "<4" else if (h > 10) ">10" else if (h % 1.0 == 0.0) h.toInt().toString() else h.f1(), maxLines = 1) },
                        )
                    }
                }
                Text(stringResource(R.string.sleep_quality), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SLEEP_QUALITY.forEachIndexed { i, res ->
                        androidx.compose.material3.FilterChip(
                            selected = quality == i,
                            onClick = { quality = if (quality == i) null else i },
                            label = { Text(stringResource(res), maxLines = 1) },
                        )
                    }
                }
                // Delete sits here, not next to Cancel/Save: three buttons don't fit in one row.
                if (initial != null && !initial.fromHc) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete_record), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(enabled = hours != null, onClick = { onSave(hours!!, quality) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
