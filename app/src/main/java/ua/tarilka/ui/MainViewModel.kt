package ua.tarilka.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import ua.tarilka.TarilkaApp
import ua.tarilka.data.DayTotal
import ua.tarilka.data.DiaryEntry
import ua.tarilka.data.FluidEntry
import ua.tarilka.data.Food
import ua.tarilka.data.Habits
import ua.tarilka.data.Nutrition
import ua.tarilka.data.Profile
import ua.tarilka.data.Targets
import ua.tarilka.data.Tip
import ua.tarilka.data.Tips
import ua.tarilka.data.Totals
import ua.tarilka.data.WeightEntry
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val tapp = app as TarilkaApp
    private val db = tapp.db
    private val prefs = tapp.prefs
    private val health = tapp.health
    private val stepSensor = tapp.stepSensor

    val day = MutableStateFlow(LocalDate.now())

    /** The calendar "today", refreshed when the app comes back to the screen. */
    private val today = MutableStateFlow(LocalDate.now())

    /** Call on resume: if the app stayed open past midnight, move from yesterday to the new day. */
    fun onResume() {
        val now = LocalDate.now()
        if (now != today.value) {
            if (day.value == today.value) day.value = now
            today.value = now
        }
        refreshSteps()
        refreshSleep()
        checkActivity()
    }
    val query = MutableStateFlow("")

    val profile: StateFlow<Profile> = prefs.profile

    val weights: StateFlow<List<WeightEntry>> =
        db.weights().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Weight on the selected day, or the last one recorded before it. */
    val weightForDay: StateFlow<Double?> = combine(weights, day) { list, d -> list.lastOrNull { it.day <= d.toEpochDay() }?.kg }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The weight recorded exactly on the selected day, if any (for editing or deleting it). */
    val weightOnDay: StateFlow<WeightEntry?> = combine(weights, day) { list, d -> list.firstOrNull { it.day == d.toEpochDay() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val latestWeight: StateFlow<Double?> =
        weights.map { it.lastOrNull()?.kg }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val targets: StateFlow<Targets> = combine(profile, latestWeight) { p, w -> Nutrition.targets(p, w) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Nutrition.targets(Profile(), null))

    val entries: StateFlow<List<DiaryEntry>> = day.flatMapLatest { db.diary().forDay(it.toEpochDay()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totals: StateFlow<Totals> = entries.map { Totals.of(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Totals())

    /** Last 7 days ending at the selected day. */
    val week: StateFlow<List<DayTotal>> = day.flatMapLatest {
        db.diary().totals(it.minusDays(6).toEpochDay(), it.toEpochDay())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val weeklyTrend: StateFlow<Double?> = weights.map { list ->
        val from = LocalDate.now().minusDays(21).toEpochDay()
        Nutrition.weeklyTrend(list.filter { it.day >= from })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val fluids: StateFlow<List<FluidEntry>> = day.flatMapLatest { db.fluids().forDay(it.toEpochDay()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Water entries plus drinks logged as food. */
    val fluidMl: StateFlow<Double> = combine(
        fluids,
        day.flatMapLatest { db.fluids().drinksFromDiary(it.toEpochDay(), ua.tarilka.data.FluidRules.ALCOHOL, ua.tarilka.data.FluidRules.MILK_DRINKS, ua.tarilka.data.FluidRules.SOUPS) },
    ) { f, drinks -> f.sumOf { it.ml } + drinks }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)

    private val _stepsEnabled = MutableStateFlow(prefs.stepsEnabled)
    val stepsEnabled: StateFlow<Boolean> = _stepsEnabled.asStateFlow()
    private val _healthSync = MutableStateFlow(prefs.healthSync)
    val healthSync: StateFlow<Boolean> = _healthSync.asStateFlow()
    private val _steps = MutableStateFlow<Long?>(null)
    val steps: StateFlow<Long?> = _steps.asStateFlow()
    /** Where the shown steps come from: Health Connect (true) or the phone's sensor (false). */
    val stepsFromHc = MutableStateFlow(false)

    val healthAvailable: Boolean get() = health.available
    val stepSensorPresent: Boolean get() = stepSensor.present

    val tips: StateFlow<List<Tip>> = combine(
        combine(totals, targets, entries) { t, tg, e -> Triple(t, tg, e.size) },
        combine(day, profile, weeklyTrend, latestWeight) { d, p, tr, w -> listOf(d, p, tr, w) },
        combine(fluidMl, steps, prefs.stepsGoal) { f, st, goal -> Triple(f, st, goal) },
    ) { (t, tg, n), rest, (fluid, st, goal) ->
        val d = rest[0] as LocalDate
        val now = LocalTime.now().hour
        val finished = d.isBefore(LocalDate.now()) || now >= 21
        val afternoon = d == LocalDate.now() && now >= 15
        Tips.forDay(t, tg, n, finished, rest[1] as Profile, rest[2] as Double?, rest[3] as Double?, fluid, afternoon, st, goal)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { day.collect { refreshSteps() } }
    }

    // ---- sleep ----

    val sleepEnabled: StateFlow<Boolean> = prefs.sleepEnabled
    fun setSleepEnabled(on: Boolean) { prefs.setSleepEnabled(on); refreshSleep() }

    /** Health Connect sleep for the 7 days ending at the selected day (empty without permission). */
    private val hcSleep = MutableStateFlow<Map<Long, Double>>(emptyMap())

    init {
        // After hcSleep exists: the first collect runs right away.
        viewModelScope.launch { day.collect { refreshSleep() } }
    }

    fun refreshSleep() = viewModelScope.launch {
        val d = day.value
        val map = if (prefs.sleepEnabled.value) runCatching { health.sleepByDay(d.minusDays(6), d) }.getOrDefault(emptyMap()) else emptyMap()
        // Another day may have been opened meanwhile: never show its neighbour's data.
        if (d == day.value) hcSleep.value = map
    }

    data class SleepShown(val hours: Double, val quality: Int?, val fromHc: Boolean)

    /** What you typed wins over Health Connect. */
    val sleep: StateFlow<SleepShown?> = combine(
        prefs.sleepEnabled, day.flatMapLatest { db.sleep().forDay(it.toEpochDay()) }, hcSleep, day,
    ) { on, manual, hc, d ->
        when {
            !on -> null
            manual != null -> SleepShown(manual.hours, manual.quality, false)
            else -> hc[d.toEpochDay()]?.let { SleepShown(it, null, true) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveSleep(hours: Double, quality: Int?) = viewModelScope.launch {
        db.sleep().upsert(ua.tarilka.data.SleepEntry(day.value.toEpochDay(), hours, quality))
    }

    fun deleteSleep() = viewModelScope.launch { db.sleep().delete(day.value.toEpochDay()) }

    /** Hours per day for a range: typed-in values, else Health Connect. */
    private suspend fun sleepRange(from: LocalDate, to: LocalDate): Map<Long, Double> {
        if (!prefs.sleepEnabled.value) return emptyMap()
        val hc = runCatching { health.sleepByDay(from, to) }.getOrDefault(emptyMap())
        return hc + db.sleep().range(from.toEpochDay(), to.toEpochDay()).associate { it.day to it.hours }
    }

    // ---- menstrual cycle (optional) ----

    val cycleEnabled: StateFlow<Boolean> = prefs.cycleEnabled
    fun setCycleEnabled(on: Boolean) { prefs.setCycleEnabled(on); refreshCycle() }

    /** Periods another app wrote to Health Connect (read only, never stored here). */
    private val hcPeriods = MutableStateFlow<List<ua.tarilka.data.PeriodEntry>>(emptyList())

    init {
        refreshCycle()
    }

    fun refreshCycle() = viewModelScope.launch {
        hcPeriods.value = if (prefs.cycleEnabled.value) runCatching { health.periods(LocalDate.now().minusDays(400)) }.getOrDefault(emptyList()) else emptyList()
    }

    /** Typed-in periods win; a Health Connect period within 3 days of one is the same period. */
    private val allPeriods = combine(db.cycle().periods(), hcPeriods) { local, hc ->
        local + hc.filter { h -> local.none { kotlin.math.abs(it.start - h.start) <= 3 } }
    }

    val cycle: StateFlow<ua.tarilka.data.CycleInfo?> = combine(prefs.cycleEnabled, allPeriods, db.cycle().days(), today, prefs.cycleTypical) { on, periods, days, t, typical ->
        if (!on) null else ua.tarilka.data.CycleInfo.of(periods, days, t.toEpochDay(), typical.takeIf { it > 0 })
    }.onEach { info ->
        // Reminders read the expected date from preferences (they run without the app open).
        val next = info?.nextStart ?: -1
        if (next != prefs.cycleNext) {
            prefs.cycleNext = next
            val app = getApplication<Application>()
            runCatching {
                ua.tarilka.notify.Reminders.schedule(app, only = ua.tarilka.notify.Reminders.CYCLE_BEFORE)
                ua.tarilka.notify.Reminders.schedule(app, only = ua.tarilka.notify.Reminders.CYCLE_LATE)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val periodDays: StateFlow<List<ua.tarilka.data.PeriodDay>> = db.cycle().days()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val periods: StateFlow<List<ua.tarilka.data.PeriodEntry>> = allPeriods
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val cycleTypical: StateFlow<Int> = prefs.cycleTypical

    /** Starts of periods typed in here; the others come from Health Connect and are deleted there. */
    val ownPeriodStarts: StateFlow<Set<Long>> = db.cycle().periods().map { l -> l.map { it.start }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** First setup: the last period (start and usual length in days) and the usual cycle length (-1 = don't know). */
    fun setupCycle(start: LocalDate, periodDays: Int, cycleDays: Int) = viewModelScope.launch {
        prefs.setCycleTypical(cycleDays)
        val end = start.plusDays(periodDays - 1L)
        // A period that is still going on stays open: "Ended" can be tapped later.
        db.cycle().upsert(ua.tarilka.data.PeriodEntry(start.toEpochDay(), end.toEpochDay().takeIf { !end.isAfter(LocalDate.now()) }))
    }

    fun periodStarted(d: LocalDate) = viewModelScope.launch { db.cycle().upsert(ua.tarilka.data.PeriodEntry(d.toEpochDay())) }

    /** Marks the end of the latest period that started on or before [d] (copied here if it came from Health Connect). */
    fun periodEnded(d: LocalDate) = viewModelScope.launch {
        val p = periods.value.filter { it.start <= d.toEpochDay() }.maxByOrNull { it.start } ?: return@launch
        db.cycle().upsert(p.copy(end = d.toEpochDay()))
    }

    fun deletePeriod(start: Long) = viewModelScope.launch {
        val end = periods.value.firstOrNull { it.start == start }?.end ?: (start + 10)
        db.cycle().delete(start)
        db.cycle().deleteDays(start, maxOf(end, start + 10))
    }

    fun setPeriodDay(d: LocalDate, flow: Int?, pain: Int?) = viewModelScope.launch {
        db.cycle().upsertDay(ua.tarilka.data.PeriodDay(d.toEpochDay(), flow, pain))
    }

    // ---- bowel movements and workouts (both optional) ----

    val bowelEnabled: StateFlow<Boolean> = prefs.bowelEnabled
    fun setBowelEnabled(on: Boolean) = prefs.setBowelEnabled(on)
    val workoutsEnabled: StateFlow<Boolean> = prefs.workoutsEnabled
    fun setWorkoutsEnabled(on: Boolean) = prefs.setWorkoutsEnabled(on)

    val bowel: StateFlow<ua.tarilka.data.BowelEntry?> = day.flatMapLatest { db.bowel().forDay(it.toEpochDay()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveBowel(count: Int, form: Int?) = viewModelScope.launch {
        db.bowel().upsert(ua.tarilka.data.BowelEntry(day.value.toEpochDay(), count, form.takeIf { count > 0 }))
    }

    fun deleteBowel() = viewModelScope.launch { db.bowel().delete(day.value.toEpochDay()) }

    val workouts: StateFlow<List<ua.tarilka.data.WorkoutEntry>> = day.flatMapLatest { db.workouts().forDay(it.toEpochDay()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addWorkout(type: Int, minutes: Int, intensity: Int) = viewModelScope.launch {
        db.workouts().insert(ua.tarilka.data.WorkoutEntry(day = day.value.toEpochDay(), type = type, minutes = minutes, intensity = intensity, createdAt = timestampFor(day.value)))
    }

    fun deleteWorkout(w: ua.tarilka.data.WorkoutEntry) = viewModelScope.launch { db.workouts().delete(w) }

    /**
     * A suggested activity level for the profile when 3+ weeks of steps and workouts clearly
     * differ from it; null when they match, data is short, or the hint was dismissed lately.
     */
    val activityHint = MutableStateFlow<Int?>(null)

    fun checkActivity() = viewModelScope.launch {
        val p = profile.value
        if (!p.isSet || LocalDate.now().toEpochDay() - prefs.activityHintDismissed < 30) { activityHint.value = null; return@launch }
        val end = LocalDate.now().minusDays(1)
        val from = end.minusDays(27)
        val steps = if (prefs.stepsEnabled) stepsByDay(from, end).values.filter { it > 0 } else emptyList()
        val minutes = db.workouts().range(from.toEpochDay(), end.toEpochDay()).sumOf(ua.tarilka.data.Workouts::whoMinutes) / 4.0
        val byMinutes = when { minutes >= 300 -> 3; minutes >= 150 -> 2; else -> 0 }
        activityHint.value = if (steps.size >= 21) {
            // Steps decide; workouts can only raise the level. Steps alone never mean "physical work" (4).
            val avg = steps.average()
            val level = maxOf(when { avg < 5_000 -> 0; avg < 7_500 -> 1; avg < 10_000 -> 2; else -> 3 }, byMinutes)
            // Steps can't tell physical work (4) from an active day (3): don't suggest lowering from 4.
            level.takeIf { it != p.activity && !(p.activity == 4 && level == 3) }
        } else {
            // Without enough step data nothing says the person moves less: only suggest more.
            byMinutes.takeIf { it > p.activity }
        }
    }

    fun applyActivityHint(level: Int) {
        prefs.saveProfile(profile.value.copy(activity = level))
        activityHint.value = null
    }

    fun dismissActivityHint() {
        prefs.activityHintDismissed = LocalDate.now().toEpochDay()
        activityHint.value = null
    }

    // ---- "why I eat" ----

    val reasonsEnabled: StateFlow<Boolean> = prefs.reasonsEnabled
    fun setReasonsEnabled(on: Boolean) = prefs.setReasonsEnabled(on)

    /** Entries just added, waiting for the optional "why" answer. */
    val pendingReason = MutableStateFlow<List<Long>?>(null)

    fun setReason(reason: Int?, hunger: Int?, hardToStop: Boolean) = viewModelScope.launch {
        val ids = pendingReason.value ?: return@launch
        pendingReason.value = null
        db.diary().setReason(ids, reason, hunger, hardToStop)
    }

    fun skipReason() { pendingReason.value = null }

    /** Reads steps for the selected day: Health Connect first, then the phone's step sensor (today only). */
    fun refreshSteps() = viewModelScope.launch {
        if (!prefs.stepsEnabled) { _steps.value = null; return@launch }
        val d = day.value
        val fromHc = runCatching { health.steps(d) }.getOrNull()
        val fromSensor = if (d == LocalDate.now()) runCatching { stepSensor.today() }.getOrNull() else null
        val useHc = fromHc != null && (fromHc > 0 || fromSensor == null)
        if (!useHc && fromSensor != null) prefs.logSteps(d.toEpochDay(), fromSensor)
        if (d != day.value) return@launch // another day was opened while reading
        _steps.value = if (useHc) fromHc else fromSensor
        stepsFromHc.value = useHc
    }

    val stepsGoal: StateFlow<Int> = prefs.stepsGoal
    fun setStepsGoal(v: Int) = prefs.setStepsGoal(v)

    /** Steps per day: Health Connect (one grouped request), else what the phone's sensor logged. */
    private suspend fun stepsByDay(from: LocalDate, to: LocalDate): Map<Long, Long> =
        prefs.loggedSteps().filterKeys { it in from.toEpochDay()..to.toEpochDay() } +
            runCatching { health.stepsByDay(from, to) }.getOrDefault(emptyMap()).filterValues { it > 0 }

    /** Steps per day for the last [days] days ending at the selected day. */
    suspend fun stepsHistory(days: Int): List<Pair<LocalDate, Long?>> {
        val end = day.value
        val from = end.minusDays(days - 1L)
        val map = stepsByDay(from, end)
        return (0 until days).map { i -> from.plusDays(i.toLong()).let { it to map[it.toEpochDay()] } }
    }

    fun setStepsEnabled(on: Boolean) {
        prefs.stepsEnabled = on
        _stepsEnabled.value = on
        refreshSteps()
        runCatching { ua.tarilka.notify.Reminders.schedule(getApplication()) }
    }

    fun setHealthSync(on: Boolean) {
        prefs.healthSync = on
        _healthSync.value = on
    }

    private fun sync(block: suspend () -> Unit) {
        if (prefs.healthSync) tapp.scope.launch { runCatching { block() } }
    }

    fun addFluid(ml: Double) = viewModelScope.launch {
        val e = FluidEntry(day = day.value.toEpochDay(), ml = ml, createdAt = timestampFor(day.value))
        val id = db.fluids().insert(e)
        sync { health.writeFluid(e.copy(id = id)) }
    }

    fun deleteFluid(e: FluidEntry) = viewModelScope.launch {
        db.fluids().delete(e)
        sync { health.deleteFluid(e) }
        offerUndo(1) {
            db.fluids().insert(e)
            sync { health.writeFluid(e) }
        }
    }

    /** Entries for past days get a midday time so they land on the right day in Health Connect. */
    private fun timestampFor(d: LocalDate): Long =
        if (d == LocalDate.now()) System.currentTimeMillis()
        else d.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    val searchResults: StateFlow<List<Food>> = query.debounce(120).flatMapLatest { q ->
        val clean = q.trim().lowercase().replace('ʼ', '\'').replace('’', '\'')
        if (clean.isEmpty()) db.foods().all() else db.foods().search(clean)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recent: StateFlow<List<Food>> = db.foods().recent()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- day note ----

    val note: StateFlow<String> = day.flatMapLatest { d -> db.notes().forDay(d.toEpochDay()).map { it?.text ?: "" } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Adds a line to today's note (e.g. a self-check result the person chose to keep). */
    fun appendNoteToday(line: String) {
        viewModelScope.launch {
            val d = LocalDate.now().toEpochDay()
            val old = db.notes().forDay(d).first()?.text?.takeIf { it.isNotBlank() }
            db.notes().upsert(ua.tarilka.data.DayNote(d, listOfNotNull(old, line).joinToString("\n").take(1000)))
        }
    }

    fun saveNote(text: String) = viewModelScope.launch {
        val d = day.value.toEpochDay()
        if (text.isBlank()) db.notes().delete(d) else db.notes().upsert(ua.tarilka.data.DayNote(d, text.trim()))
    }

    // ---- history: one row per day that has anything logged ----

    data class HistoryDay(val day: LocalDate, val kcal: Double, val fluidMl: Double, val weight: Double?, val note: String?)

    val history: StateFlow<List<HistoryDay>> = today.flatMapLatest { t ->
        val to = t.toEpochDay()
        val from = to - 365
        combine(
            db.diary().totals(from, to),
            combine(
                db.fluids().totals(from, to),
                db.fluids().drinksFromDiaryRange(from, to, ua.tarilka.data.FluidRules.ALCOHOL, ua.tarilka.data.FluidRules.MILK_DRINKS, ua.tarilka.data.FluidRules.SOUPS),
            ) { a, b -> (a + b).groupBy { it.day }.mapValues { (d, l) -> ua.tarilka.data.DayFluid(d, l.sumOf { it.ml }) } },
            db.weights().all(), db.notes().all(),
        ) { food, w, weight, notes ->
            val f = food.associateBy { it.day }
            val kg = weight.associateBy { it.day }
            val n = notes.associate { it.day to it.text }
            (f.keys + w.keys + kg.keys.filter { it >= from } + n.keys).sortedDescending().map { d ->
                HistoryDay(LocalDate.ofEpochDay(d), f[d]?.kcal ?: 0.0, w[d]?.ml ?: 0.0, kg[d]?.kg, n[d])
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- recipes ----

    suspend fun allFoods(): List<Food> = db.foods().allOnce()

    fun shiftDay(by: Long) { day.value = day.value.plusDays(by) }
    fun today() { day.value = LocalDate.now() }

    val mealTimes: StateFlow<Boolean> = prefs.mealTimes
    fun setMealTimes(on: Boolean) = prefs.setMealTimes(on)

    /** The time offered for a new entry: now for today, a usual time of that meal for past days. */
    fun defaultTime(meal: Int): LocalTime =
        mealTimeOf(meal) ?: if (day.value == LocalDate.now()) LocalTime.now().withSecond(0).withNano(0)
        else MEAL_TIMES.getOrElse(meal) { LocalTime.NOON }

    /** Breakfast, lunch and dinner have one time for all their foods (snacks keep their own). */
    fun mealTimeOf(meal: Int): LocalTime? = if (meal == 3) null else entries.value.firstOrNull { it.meal == meal }?.let {
        java.time.Instant.ofEpochMilli(it.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)
    }

    /** Moves every food of a meal on the shown day to [time]. */
    fun setMealTime(meal: Int, time: LocalTime) = viewModelScope.launch {
        entries.value.filter { it.meal == meal }.forEachIndexed { i, e ->
            val updated = e.copy(createdAt = millis(LocalDate.ofEpochDay(e.day), time) + i)
            db.diary().update(updated)
            sync { health.deleteMeal(e); health.writeMeal(updated) }
        }
    }

    private fun millis(d: LocalDate, t: LocalTime) = d.atTime(t).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun addFood(food: Food, grams: Double, meal: Int, time: LocalTime? = null) =
        addEntries(listOf(DiaryEntry.of(food, grams, day.value.toEpochDay(), meal)), time)

    /** [time] = when it was eaten (chosen by the person); otherwise now, or the meal's usual time on a past day. */
    fun addEntries(list: List<DiaryEntry>, time: LocalTime? = null) = viewModelScope.launch {
        val saved = list.mapIndexed { i, e ->
            val d = LocalDate.ofEpochDay(e.day)
            val at = when {
                time != null -> millis(d, time) + i // + i keeps several items of one meal apart
                // Joining a meal that already has a time: same time as its other foods.
                e.meal != 3 && d == day.value -> mealTimeOf(e.meal)?.let { millis(d, it) + i } ?: if (d == LocalDate.now()) System.currentTimeMillis() + i else millis(d, MEAL_TIMES.getOrElse(e.meal) { LocalTime.NOON }) + i
                d == LocalDate.now() -> System.currentTimeMillis() + i
                else -> millis(d, MEAL_TIMES.getOrElse(e.meal) { LocalTime.NOON }) + i
            }
            val withTime = e.copy(createdAt = at)
            withTime.copy(id = db.diary().insert(withTime))
        }
        sync { saved.forEach { health.writeMeal(it) } }
        if (prefs.reasonsEnabled.value && saved.isNotEmpty()) pendingReason.value = saved.map { it.id }
    }

    fun updateGrams(entry: DiaryEntry, grams: Double, time: LocalTime? = null) = viewModelScope.launch {
        if (entry.grams <= 0) return@launch
        val k = grams / entry.grams
        val updated = entry.copy(
            grams = grams, kcal = entry.kcal * k, protein = entry.protein * k,
            fat = entry.fat * k, carbs = entry.carbs * k, fiber = entry.fiber * k,
            createdAt = time?.let { millis(LocalDate.ofEpochDay(entry.day), it) } ?: entry.createdAt,
        )
        db.diary().update(updated)
        sync { health.deleteMeal(entry); health.writeMeal(updated) }
    }

    fun deleteEntry(entry: DiaryEntry) = viewModelScope.launch {
        db.diary().delete(entry)
        sync { health.deleteMeal(entry) }
        offerUndo(1) {
            db.diary().insert(entry)
            sync { health.writeMeal(entry) }
        }
    }

    /** The "−" next to "+250 ml": takes back the last drink of the day. */
    fun removeLastFluid() {
        fluids.value.maxByOrNull { it.createdAt }?.let { deleteFluid(it) }
    }

    // ---- batch delete with a short undo ----

    private var undoAction: (suspend () -> Unit)? = null
    private var undoSeq = 0L
    /**
     * (event number, items that Undo would bring back). A new number for every delete, so two
     * deletes of one item each still show the bar again; deletes in quick succession add up.
     */
    val undoEvent = MutableStateFlow<Pair<Long, Int>?>(null)

    private fun offerUndo(count: Int, restore: suspend () -> Unit) {
        val earlier = undoAction
        undoAction = if (earlier == null) restore else ({ earlier(); restore() })
        undoEvent.value = ++undoSeq to count + (if (earlier != null) undoEvent.value?.second ?: 0 else 0)
    }

    fun undo() = viewModelScope.launch {
        val action = undoAction
        undoAction = null
        undoEvent.value = null
        action?.invoke()
    }

    /** The snackbar for event [seq] is gone (timed out or left the screen): its undo is no longer offered. */
    fun undoShown(seq: Long) {
        if (undoEvent.value?.first != seq) return
        undoAction = null
        undoEvent.value = null
    }

    fun deleteMeal(meal: Int) = viewModelScope.launch {
        val list = entries.value.filter { it.meal == meal }
        if (list.isEmpty()) return@launch
        db.diary().deleteMany(list)
        sync { list.forEach { health.deleteMeal(it) } }
        offerUndo(list.size) {
            db.diary().insertAll(list)
            sync { list.forEach { health.writeMeal(it) } }
        }
    }

    fun deleteFluidsOfDay() = viewModelScope.launch {
        val list = fluids.value
        if (list.isEmpty()) return@launch
        db.fluids().deleteMany(list)
        sync { list.forEach { health.deleteFluid(it) } }
        offerUndo(list.size) {
            db.fluids().insertAll(list)
            sync { list.forEach { health.writeFluid(it) } }
        }
    }

    /**
     * Clears the diary (food, drinks, weight, notes). With [everything] also own foods, recipes,
     * favorites, the profile and the not-found list. The AI model and app settings stay.
     */
    fun clearData(everything: Boolean, alsoHealthConnect: Boolean) = viewModelScope.launch {
        db.diary().deleteAll()
        db.fluids().deleteAll()
        db.weights().deleteAll()
        db.notes().deleteAll()
        db.sleep().deleteAll()
        db.bowel().deleteAll()
        db.workouts().deleteAll()
        db.cycle().deleteAll()
        db.cycle().deleteAllDays()
        prefs.clearCycle()
        if (everything) {
            db.foods().deleteCustom()
            db.foods().setFavorites(emptyList())
            prefs.clearPersonal()
            java.io.File(getApplication<Application>().cacheDir, "photos").deleteRecursively()
        }
        undoAction = null
        undoEvent.value = null
        val app = getApplication<Application>()
        dataMessage.value = app.getString(ua.tarilka.R.string.clear_done)
        if (alsoHealthConnect) tapp.scope.launch {
            // Say so if Health Connect could not delete everything (e.g. a permission was taken back).
            if (runCatching { health.deleteAllOwn() }.getOrDefault(false) != true) {
                dataMessage.value = app.getString(ua.tarilka.R.string.clear_done_hc_failed)
            }
        }
    }

    // ---- habits and summaries ----

    private val foodsById: StateFlow<Map<Long, Food>> = db.foods().all().map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Patterns for the 7 days ending at the selected day (the "Week" card). */
    val weekHabits: StateFlow<Habits?> = combine(
        // Two weeks: the one before gives the alcohol trend.
        day.flatMapLatest { db.diary().rangeFlow(it.minusDays(13).toEpochDay(), it.toEpochDay()) },
        foodsById,
        combine(day.flatMapLatest { db.sleep().rangeFlow(it.minusDays(6).toEpochDay(), it.toEpochDay()) }, hcSleep, prefs.sleepEnabled, prefs.mealTimes) { manual, hc, on, times ->
            (if (on) hc + manual.associate { it.day to it.hours } else emptyMap()) to times
        },
        day.flatMapLatest { db.bowel().rangeFlow(it.minusDays(13).toEpochDay(), it.toEpochDay()) },
        day.flatMapLatest { db.workouts().rangeFlow(it.minusDays(13).toEpochDay(), it.toEpochDay()) },
    ) { list, foods, (sleepMap, times), bowel, workouts ->
        val split = day.value.minusDays(7).toEpochDay()
        val (cur, prev) = list.partition { it.day > split }
        Habits.of(cur, foods, 7, sleep = sleepMap, bowel = bowel.filter { it.day > split }, workouts = workouts.filter { it.day > split }, withTimes = times)
            .copy(prev = Habits.of(prev, foods, 7, bowel = bowel.filter { it.day <= split }, workouts = workouts.filter { it.day <= split }, withTimes = times))
            .takeIf { it.days >= 2 }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    enum class Period(val days: Int) { WEEK(7), MONTH(30), YEAR(365) }

    suspend fun habitsFor(period: Period): Habits {
        val end = LocalDate.now()
        val from = end.minusDays(period.days - 1L)
        val foods = foodsById.value.ifEmpty { db.foods().allOnce().associateBy { it.id } }
        val (cur, prev) = db.diary().range(from.minusDays(period.days.toLong()).toEpochDay(), end.toEpochDay())
            .partition { it.day >= from.toEpochDay() }
        val start = from.minusDays(period.days.toLong())
        val sleep = sleepRange(start, end)
        val bowel = db.bowel().range(start.toEpochDay(), end.toEpochDay()).partition { it.day >= from.toEpochDay() }
        val workouts = db.workouts().range(start.toEpochDay(), end.toEpochDay()).partition { it.day >= from.toEpochDay() }
        return Habits.of(cur, foods, period.days, sleep = sleep.filterKeys { it >= from.toEpochDay() }, bowel = bowel.first, workouts = workouts.first, withTimes = prefs.mealTimes.value)
            .copy(prev = Habits.of(prev, foods, period.days, sleep = sleep.filterKeys { it < from.toEpochDay() }, bowel = bowel.second, workouts = workouts.second, withTimes = prefs.mealTimes.value))
    }

    /** Plain-text summary of a period ending today, compared with the period before, for the AI. */
    suspend fun periodSummary(period: Period): String {
        val n = period.days.toLong()
        val end = LocalDate.now()
        val from = end.minusDays(n - 1)
        val prevFrom = from.minusDays(n)
        val foods = foodsById.value.ifEmpty { db.foods().allOnce().associateBy { it.id } }
        val all = db.diary().range(prevFrom.toEpochDay(), end.toEpochDay())
        val (cur, prev) = all.partition { it.day >= from.toEpochDay() }
        val sleepAll = sleepRange(prevFrom, end)
        val bowelAll = db.bowel().range(prevFrom.toEpochDay(), end.toEpochDay()).partition { it.day >= from.toEpochDay() }
        val workAll = db.workouts().range(prevFrom.toEpochDay(), end.toEpochDay()).partition { it.day >= from.toEpochDay() }
        val h = Habits.of(cur, foods, period.days, sleep = sleepAll.filterKeys { it >= from.toEpochDay() }, bowel = bowelAll.first, workouts = workAll.first, withTimes = prefs.mealTimes.value)
        val hp = Habits.of(prev, foods, period.days, sleep = sleepAll.filterKeys { it < from.toEpochDay() }, bowel = bowelAll.second, workouts = workAll.second, withTimes = prefs.mealTimes.value)
        // Water entries plus drinks logged as food, as on the Today screen.
        val fluidsByDay = (
            db.fluids().totalsOnce(prevFrom.toEpochDay(), end.toEpochDay()) +
                db.fluids().drinksFromDiaryRange(prevFrom.toEpochDay(), end.toEpochDay(), ua.tarilka.data.FluidRules.ALCOHOL, ua.tarilka.data.FluidRules.MILK_DRINKS, ua.tarilka.data.FluidRules.SOUPS).first()
            ).groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.ml } }
        val tg = targets.value
        val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val zone = java.time.ZoneId.systemDefault()
        fun Double.r() = toInt()
        fun avgFluid(a: LocalDate, b: LocalDate) =
            fluidsByDay.filterKeys { it in a.toEpochDay()..b.toEpochDay() }.values.let { if (it.isEmpty()) null else it.average().r() }
        // Average over days that have steps (Health Connect only goes back about 30 days before access was given).
        val stepsByDay = if (prefs.stepsEnabled) stepsByDay(prevFrom, end) else emptyMap()
        fun stepsAvg(a: LocalDate, b: LocalDate) = stepsByDay.filterKeys { it in a.toEpochDay()..b.toEpochDay() }.values.filter { it > 0 }
            .let { if (it.isEmpty()) null else "${it.average().toLong()} (over ${it.size} days with data)" }
        val periodName = period.name.lowercase()
        return buildString {
            appendLine("Summary of the person's last $periodName ($from to $end), compared with the $periodName before.")
            appendLine(
                "Daily targets: ${tg.kcal.r()} kcal, protein ${tg.protein.r()} g, fat ${tg.fat.r()} g, carbs ${tg.carbs.r()} g, " +
                    "fiber ${tg.fiber.r()} g, fluids ${tg.waterMl.r()} ml. Goal: ${tg.goal.name.lowercase()} weight." +
                    when (tg.limit) {
                        ua.tarilka.data.Limit.PREGNANT -> " The person is pregnant or breastfeeding: never suggest eating less or losing weight."
                        ua.tarilka.data.Limit.TEEN -> " The person is under 18: never suggest eating less or losing weight."
                        ua.tarilka.data.Limit.UNDERWEIGHT -> " BMI is under 18.5: never suggest eating less or losing weight; gently suggest a doctor if weight is falling."
                        null -> ""
                    },
            )
            when (period) {
                Period.WEEK -> for (i in 0L..6L) {
                    val d = from.plusDays(i)
                    val list = cur.filter { it.day == d.toEpochDay() }
                    val name = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                    val drinks = fluidsByDay[d.toEpochDay()]?.let { ", drinks ${it.r()} ml" } ?: ""
                    if (list.isEmpty()) { appendLine("- $name $d: nothing logged$drinks"); continue }
                    val t = Totals.of(list)
                    val times = list.map { java.time.Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalTime().format(time) }.distinct()
                    val at = if (prefs.mealTimes.value) "; eaten at ${times.joinToString()}" else ""
                    appendLine("- $name $d: ${t.kcal.r()} kcal, protein ${t.protein.r()} g, fiber ${t.fiber.r()} g$at$drinks")
                }
                Period.MONTH -> for (w in 0 until 4) {
                    val a = from.plusDays(w * 7L)
                    val b = if (w == 3) end else a.plusDays(6)
                    val hw = Habits.of(cur.filter { it.day in a.toEpochDay()..b.toEpochDay() }, foods, (b.toEpochDay() - a.toEpochDay() + 1).toInt())
                    appendLine("- $a to $b: ${hw.days} days logged, average ${hw.kcalAvg.r()} kcal, protein ${hw.proteinAvg.r()} g")
                }
                Period.YEAR -> cur.groupBy { LocalDate.ofEpochDay(it.day).withDayOfMonth(1) }.toSortedMap().forEach { (m, list) ->
                    val hm = Habits.of(list, foods, m.lengthOfMonth())
                    val kg = weights.value.filter { LocalDate.ofEpochDay(it.day).withDayOfMonth(1) == m }.map { it.kg }
                    appendLine(
                        "- ${m.year}-${"%02d".format(m.monthValue)}: ${hm.days} days logged, average ${hm.kcalAvg.r()} kcal, protein ${hm.proteinAvg.r()} g" +
                            (if (kg.isNotEmpty()) ", weight ${"%.1f".format(java.util.Locale.ROOT, kg.average())} kg" else ""),
                    )
                }
            }
            fun line(label: String, now: String, before: String) = appendLine("$label: $now (previous $periodName: $before)")
            line("Days with food logged", "${h.days}", "${hp.days}")
            if (h.days > 0) {
                line("Average per logged day", "${h.kcalAvg.r()} kcal, protein ${h.proteinAvg.r()} g, fiber ${h.fiberAvg.r()} g", if (hp.days > 0) "${hp.kcalAvg.r()} kcal, protein ${hp.proteinAvg.r()} g" else "no data")
                line("Sweets and sugary drinks", "${h.sweetsPct.r()} % of calories, ${h.sweetFreeDays} of ${h.days} days without", if (hp.days > 0) "${hp.sweetsPct.r()} %" else "no data")
                line("Vegetables and fruit", "${h.vegFruitG.r()} g a day (WHO: 400 g or more)", if (hp.days > 0) "${hp.vegFruitG.r()} g" else "no data")
                if (prefs.mealTimes.value) line("Calories eaten after 22:00", "${h.latePct.r()} %", if (hp.days > 0) "${hp.latePct.r()} %" else "no data")
                line(
                    "Alcohol (standard drinks of 10 g)",
                    "${"%.1f".format(java.util.Locale.ROOT, h.alcoholPerWeek)} a week, on ${h.alcoholDays} days, ${h.heavyDrinkDays} days with 6 or more",
                    "${"%.1f".format(java.util.Locale.ROOT, hp.alcoholPerWeek)} a week, on ${hp.alcoholDays} days, ${hp.heavyDrinkDays} days with 6 or more",
                )
                line("Days with fast food", "${h.fastFoodDays}", "${hp.fastFoodDays}")
            }
            if (h.sleepAvg != null || hp.sleepAvg != null) {
                line("Average sleep", h.sleepAvg?.let { "%.1f h".format(java.util.Locale.ROOT, it) } ?: "not recorded", hp.sleepAvg?.let { "%.1f h".format(java.util.Locale.ROOT, it) } ?: "not recorded")
            }
            if (h.bowelDays > 0) {
                line(
                    "Bowel movements (typed in)",
                    "on ${h.bowelMoveDays} of ${h.bowelDays} recorded days; hard ${h.bowelHard} days, loose ${h.bowelLoose} days",
                    if (hp.bowelDays > 0) "on ${hp.bowelMoveDays} of ${hp.bowelDays} days; hard ${hp.bowelHard}, loose ${hp.bowelLoose}" else "not recorded",
                )
            }
            if (h.workoutCount > 0 || hp.workoutCount > 0) {
                line(
                    "Workouts (WHO minutes a week: moderate ×1, vigorous ×2; target 150–300; strength days target 2)",
                    "${h.activeMinPerWeek.toInt()} min, strength ${"%.1f".format(java.util.Locale.ROOT, h.strengthPerWeek)} days a week",
                    "${hp.activeMinPerWeek.toInt()} min, strength ${"%.1f".format(java.util.Locale.ROOT, hp.strengthPerWeek)} days",
                )
            }
            // Cycle: starts in the period and how eating looked in the week before each start.
            if (prefs.cycleEnabled.value) {
                val starts = periods.value.map { it.start }.filter { it in from.toEpochDay()..end.toEpochDay() }
                if (starts.isNotEmpty()) {
                    val before = starts.flatMap { s -> (s - 7 until s).toList() }.toSet()
                    val byDay = cur.groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.kcal } }
                    val pre = byDay.filterKeys { it in before }.values
                    val other = byDay.filterKeys { it !in before }.values
                    appendLine(
                        "Menstruation started on: " + starts.joinToString { LocalDate.ofEpochDay(it).toString() } +
                            (if (pre.isNotEmpty() && other.isNotEmpty()) "; average kcal in the 7 days before: ${pre.average().r()}, other days: ${other.average().r()}" else "") +
                            ". Higher appetite and up to 1–2 kg of water weight before menstruation are normal; mention it kindly if it fits.",
                    )
                }
            }
            if (h.reasonMeals > 0) {
                val why = h.notHunger.entries.sortedByDescending { it.value }.joinToString { "${ua.tarilka.data.Reasons.EN.getOrElse(it.key) { "other" }} ${it.value}" }
                appendLine(
                    "Why the person ate (answered for ${h.reasonMeals} meals): not from hunger ${h.notHungerTotal} times" +
                        (if (why.isNotEmpty()) " ($why)" else "") + "; hard to stop ${h.hardToStop} times.",
                )
            }
            line("Average drinks (water etc.) on days with any", avgFluid(from, end)?.let { "$it ml" } ?: "none logged", avgFluid(prevFrom, from.minusDays(1))?.let { "$it ml" } ?: "none")
            stepsAvg(from, end)?.let { line("Average steps a day", it, stepsAvg(prevFrom, from.minusDays(1)) ?: "no data") }
            val recent = weights.value.filter { it.day in minOf(prevFrom, end.minusDays(27)).toEpochDay()..end.toEpochDay() }
            if (recent.isNotEmpty()) {
                if (period != Period.YEAR) appendLine("Weight: " + recent.takeLast(20).joinToString { "${LocalDate.ofEpochDay(it.day)} ${it.kg} kg" })
                Nutrition.weeklyTrend(recent.filter { it.day >= end.minusDays(27).toEpochDay() })?.let { tr ->
                    appendLine(
                        "Weight trend, last 4 weeks: ${"%+.2f".format(java.util.Locale.ROOT, tr)} kg a week " +
                            "(${"%+.1f".format(java.util.Locale.ROOT, tr / recent.last().kg * 100)} % of body weight).",
                    )
                }
            }
            // The person's own notes say what numbers cannot (mood, illness, a party); they stay on the phone.
            val notes = db.notes().allOnce().filter { it.day in from.toEpochDay()..end.toEpochDay() }
            if (notes.isNotEmpty()) {
                val each = if (period == Period.WEEK) 300 else 120
                appendLine("The person's day notes (take them into account):")
                notes.takeLast(if (period == Period.WEEK) 7 else 20).forEach { appendLine("- ${LocalDate.ofEpochDay(it.day)}: ${it.text.take(each).replace('\n', ' ')}") }
            }
            appendLine("Techniques you may suggest (pick 1–2 that fit what you see, explain simply):")
            Habits.TECHNIQUES.forEach { appendLine("- $it") }
            if (h.alcoholPerWeek > 0) {
                appendLine("For alcohol (WHO brief intervention; if there is alcohol, always include 2 of these, more if drinking is frequent or heavy):")
                Habits.ALCOHOL_TECHNIQUES.forEach { appendLine("- $it") }
            }
            append(
                "Write a kind, encouraging summary: first what went well or improved, then 2–3 patterns worth attention, " +
                    "then the techniques. Compare with the previous $periodName where it helps. Days with nothing logged were probably just not recorded. " +
                    "Never shame. Do not set weight targets or suggest diets. If sleep is short, mention that it raises appetite. " +
                    "Suggest seeing a doctor or a specialist only if weight falls faster than about 1 % of body weight a week, " +
                    "the average is under 1200 kcal, a quarter or more of calories are eaten late on most days, " +
                    "or eating felt hard to stop twice a week or more; then say kindly that this is common and help works. " +
                    "For alcohol there is no safe amount: say whether it went up or down compared with before; if there were days with 6 or more drinks, " +
                    "give the alcohol techniques first and only then add that a doctor or a support line can help.",
            )
        }
    }

    fun saveFood(food: Food) = viewModelScope.launch {
        val f = food.copy(searchKey = Food.buildSearchKey(food.nameUk, food.nameEn))
        if (f.id == 0L) db.foods().insert(f) else db.foods().update(f)
    }

    fun deleteFood(food: Food) = viewModelScope.launch { if (food.isCustom) db.foods().delete(food) }
    fun toggleFavorite(food: Food) = viewModelScope.launch { db.foods().update(food.copy(favorite = !food.favorite)) }

    fun saveWeight(kg: Double, date: LocalDate = LocalDate.now(), time: LocalTime? = null) = viewModelScope.launch {
        val w = WeightEntry(date.toEpochDay(), kg, time?.let { it.hour * 60 + it.minute })
        db.weights().upsert(w)
        sync { health.deleteWeight(w); health.writeWeight(w) }
    }

    fun deleteWeight(w: WeightEntry) = viewModelScope.launch {
        db.weights().delete(w)
        sync { health.deleteWeight(w) }
    }

    fun saveProfile(p: Profile) = prefs.saveProfile(p)

    val missing: StateFlow<Map<String, Int>> = prefs.missing
    fun recordMissing(q: String) = prefs.recordMissing(q)
    fun clearMissing() = prefs.clearMissing()

    val themeMode: Int get() = prefs.themeMode
    val dynamicColor: StateFlow<Boolean> = prefs.dynamicColor
    fun setThemeMode(mode: Int) {
        prefs.themeMode = mode
        ua.tarilka.applyThemeMode(mode)
    }
    fun setDynamicColor(on: Boolean) = prefs.setDynamicColor(on)

    // ---- export / import ----

    /** Result of the last export or import, shown once in Settings. */
    val dataMessage = MutableStateFlow<String?>(null)

    fun exportBackup(uri: android.net.Uri, password: String) = viewModelScope.launch {
        dataMessage.value = runCatching {
            val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ua.tarilka.data.Backup.export(db, prefs) }
            writeText(uri, if (password.isEmpty()) json else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                ua.tarilka.data.Backup.encrypt(json, password)
            })
            getApplication<Application>().getString(ua.tarilka.R.string.export_done)
        }.getOrElse { getApplication<Application>().getString(ua.tarilka.R.string.data_error, it.message ?: "") }
    }

    fun exportCsv(uri: android.net.Uri) = viewModelScope.launch {
        dataMessage.value = runCatching {
            val ukrainian = ua.tarilka.data.isUkrainian()
            writeText(uri, kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ua.tarilka.data.Backup.diaryCsv(db, ukrainian) })
            getApplication<Application>().getString(ua.tarilka.R.string.export_done)
        }.getOrElse { getApplication<Application>().getString(ua.tarilka.R.string.data_error, it.message ?: "") }
    }

    /** Password chosen for the export in progress ("" = no encryption, null = unknown). */
    var exportPassword: String? = null

    /** Step 1: read and check the file. Step 2 ([confirmImport]) replaces the data. */
    val pendingImport = MutableStateFlow<Pair<String, ua.tarilka.data.Backup.Summary>?>(null)

    fun readImport(uri: android.net.Uri) = viewModelScope.launch {
        wrongPassword.value = false
        runCatching {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            }
            if (ua.tarilka.data.Backup.isEncrypted(text)) {
                encryptedImport.value = text
            } else {
                pendingImport.value = text to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ua.tarilka.data.Backup.peek(text) }
            }
        }.onFailure {
            dataMessage.value = getApplication<Application>().getString(ua.tarilka.R.string.data_error, it.message ?: "")
        }
    }

    /** An encrypted backup waiting for its password. */
    val encryptedImport = MutableStateFlow<String?>(null)
    val wrongPassword = MutableStateFlow(false)

    fun unlockImport(password: String) = viewModelScope.launch {
        val text = encryptedImport.value ?: return@launch
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ua.tarilka.data.Backup.decrypt(text, password) }
        }.onSuccess { plain ->
            encryptedImport.value = null
            wrongPassword.value = false
            pendingImport.value = plain to ua.tarilka.data.Backup.peek(plain)
        }.onFailure { wrongPassword.value = true }
    }

    fun confirmImport() = viewModelScope.launch {
        val (text, _) = pendingImport.value ?: return@launch
        pendingImport.value = null
        dataMessage.value = runCatching {
            val s = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ua.tarilka.data.Backup.restore(db, prefs, text) }
            getApplication<Application>().getString(ua.tarilka.R.string.import_done, s.diary, s.weights)
        }.getOrElse { getApplication<Application>().getString(ua.tarilka.R.string.data_error, it.message ?: "") }
    }

    private suspend fun writeText(uri: android.net.Uri, text: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }

    companion object {
        /** Usual times of breakfast, lunch, dinner and a snack, for entries on past days. */
        val MEAL_TIMES = listOf(LocalTime.of(8, 0), LocalTime.of(13, 0), LocalTime.of(19, 0), LocalTime.of(16, 0))
    }
}
