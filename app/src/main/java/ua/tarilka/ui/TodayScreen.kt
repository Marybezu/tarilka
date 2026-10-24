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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import ua.tarilka.ai.ModelStore
import ua.tarilka.data.DiaryEntry
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
    val fluidMl by vm.fluidMl.collectAsState()
    val fluids by vm.fluids.collectAsState()
    val steps by vm.steps.collectAsState()
    val stepsEnabled by vm.stepsEnabled.collectAsState()
    val weight by vm.latestWeight.collectAsState()
    val macros = LocalMacroColors.current
    val context = LocalContext.current
    var editing by remember { mutableStateOf<DiaryEntry?>(null) }
    var fluidDialog by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refreshSteps()
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
        item { DayHeader(day, onPrev = { vm.shiftDay(-1) }, onNext = { vm.shiftDay(1) }, onToday = vm::today) }

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
                    CalorieRing(totals.kcal, targets.kcal)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NutrientBar(stringResource(R.string.protein), totals.protein, targets.protein, macros.protein)
                        NutrientBar(stringResource(R.string.fat), totals.fat, targets.fat, macros.fat)
                        NutrientBar(stringResource(R.string.carbs), totals.carbs, targets.carbs, macros.carbs)
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
                    Spacer(Modifier.width(12.dp))
                    FilledTonalButton(onClick = { vm.addFluid(250.0) }) { Text(stringResource(R.string.plus_ml, 250)) }
                }
                if (stepsEnabled && steps != null) {
                    Spacer(Modifier.height(8.dp))
                    val kcal = ua.tarilka.data.Nutrition.stepsKcal(steps!!, weight ?: profile.weightKg)
                    Text(
                        "👣 " + stringResource(R.string.steps_line, steps!!.toInt(), kcal.f0()),
                        style = MaterialTheme.typography.bodyMedium.merge(Numbers),
                    )
                }
            }
        }

        item {
            Section(title = stringResource(R.string.recommendations)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tips.forEach { TipRow(it) }
                    if (model is ModelStore.State.Ready && entries.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        when {
                            adviceLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodyMedium)
                            }
                            advice != null -> {
                                HorizontalDivider()
                                Text(advice!!, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = ai::clearAdvice) { Text(stringResource(R.string.hide)) }
                            }
                            else -> FilledTonalButton(onClick = {
                                val summary = adviceSummary(context, entries, totals, targets, tips, fluidMl, steps)
                                ai.requestAdvice(summary, isUkrainian())
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
                    showPhoto = true,
                    onAdd = { onAdd(meal) },
                    onPhoto = { onPhoto(meal) },
                    onEntry = { editing = it },
                )
            }
        }

        item {
            val withData = week.filter { it.kcal > 0 }
            if (withData.isNotEmpty()) {
                Section(title = stringResource(R.string.week_title)) {
                    val avg = withData.sumOf { it.kcal } / withData.size
                    val avgP = withData.sumOf { it.protein } / withData.size
                    Text(
                        stringResource(R.string.week_avg, avg.f0(), withData.size, avgP.f0()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    WeekBars(day, week, targets.kcal)
                }
            }
        }
    }

    if (fluidDialog) {
        FluidDialog(
            fluids = fluids,
            onAdd = { vm.addFluid(it) },
            onDelete = { vm.deleteFluid(it) },
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
            extraAction = {
                TextButton(onClick = { vm.deleteEntry(e); editing = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
private fun DayHeader(day: LocalDate, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
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
    }
}

@Composable
private fun TipRow(tip: Tip) {
    val color = when (tip.kind) {
        Tip.Kind.GOOD -> MaterialTheme.colorScheme.primary
        Tip.Kind.WARN -> MaterialTheme.colorScheme.secondary
        Tip.Kind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row {
        Text("●", color = color, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(tip.res, *tip.args.toTypedArray()), style = MaterialTheme.typography.bodyMedium)
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
) {
    val kcal = entries.sumOf { it.kcal }
    Section(
        title = mealName(meal),
        trailing = {
            if (entries.isNotEmpty()) {
                Text(
                    "${kcal.f0()} ${stringResource(R.string.unit_kcal)}",
                    style = MaterialTheme.typography.labelLarge.merge(Numbers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        entries.forEach { e ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEntry(e) }
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
                Text(e.kcal.f0(), style = MaterialTheme.typography.bodyLarge.merge(Numbers))
            }
        }
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
    }
}

@Composable
private fun WeekBars(day: LocalDate, week: List<ua.tarilka.data.DayTotal>, target: Double) {
    val byDay = week.associateBy { it.day }
    val color = LocalMacroColors.current.kcal
    val over = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val max = maxOf(target * 1.3, week.maxOfOrNull { it.kcal } ?: 0.0)
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
                        if (k > target * 1.1) over else color,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
                        size = androidx.compose.ui.geometry.Size(size.width, h),
                    )
                    val ty = (size.height - (target / max).toFloat() * size.height)
                    drawLine(
                        androidx.compose.ui.graphics.Color.Gray,
                        androidx.compose.ui.geometry.Offset(0f, ty),
                        androidx.compose.ui.geometry.Offset(size.width, ty),
                        strokeWidth = 2f,
                    )
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
    entries: List<DiaryEntry>,
    totals: ua.tarilka.data.Totals,
    targets: ua.tarilka.data.Targets,
    tips: List<Tip>,
    fluidMl: Double,
    steps: Long?,
): String = buildString {
    val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    val now = java.time.LocalTime.now().format(time)
    appendLine("Current time: $now.")
    appendLine("Foods eaten today (time, name, amount):")
    entries.forEach {
        val t = java.time.Instant.ofEpochMilli(it.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(time)
        appendLine("- $t ${it.nameEn} (${it.nameUk}), ${it.grams.f0()} g, ${it.kcal.f0()} kcal")
    }
    appendLine("Fluids today: ${fluidMl.f0()} ml (target ${targets.waterMl.f0()} ml).")
    if (steps != null) appendLine("Steps today: $steps.")
    appendLine(
        "Totals: ${totals.kcal.f0()} kcal (target ${targets.kcal.f0()}), protein ${totals.protein.f0()} g " +
            "(target ${targets.protein.f0()}), fat ${totals.fat.f0()} g (target ${targets.fat.f0()}), " +
            "carbs ${totals.carbs.f0()} g (target ${targets.carbs.f0()}), fiber ${totals.fiber.f0()} g (target ${targets.fiber.f0()}).",
    )
    if (tips.isNotEmpty()) {
        appendLine("Notes from the app:")
        tips.forEach { appendLine("- " + context.getString(it.res, *it.args.toTypedArray())) }
    }
    append("Give short practical advice for the rest of the day or for tomorrow.")
}
