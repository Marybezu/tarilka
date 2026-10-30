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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import androidx.room.withTransaction
import ua.tarilka.data.aiSafety
import ua.tarilka.data.clearDiary
import ua.tarilka.data.drinksByDay
import ua.tarilka.data.drinksOn
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
        // The diary was locked meanwhile: this view model's database is closed.
        if (tapp.dbState.value !== db) return
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
        day.flatMapLatest { db.fluids().drinksOn(it.toEpochDay()) },
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
    val healthInstallable: Boolean get() = health.installable
    /** How many kinds of record (food, drinks, weight) may be written to Health Connect now. */
    suspend fun healthWriteGranted(): Int = health.granted().count { it in ua.tarilka.health.HealthSync.WRITE }
    val stepSensorPresent: Boolean get() = stepSensor.present

    val tips: StateFlow<List<Tip>> = combine(
        combine(totals, targets, entries) { t, tg, e -> Triple(t, tg, e.size) },
        combine(day, weeklyTrend, latestWeight) { d, tr, w -> Triple(d, tr, w) },
        combine(fluidMl, steps, prefs.stepsGoal) { f, st, goal -> Triple(f, st, goal) },
    ) { (t, tg, n), (d, trend, weight), (fluid, st, goal) ->
        val now = LocalTime.now().hour
        val finished = d.isBefore(LocalDate.now()) || now >= 21
        val afternoon = d == LocalDate.now() && now >= 15
        Tips.forDay(t, tg, n, finished, trend, weight, fluid, afternoon, st, goal, entries.value)
    }.combine(
        // Workouts tracked for a week or more and none in the last 7 days: a gentle word, at most once a week, never daily.
        combine(prefs.workoutsEnabled, day, today) { on, d, t -> Triple(on, d, t) }.flatMapLatest { (on, d, t) ->
            if (!on || d != t) flowOf(false)
            else db.workouts().rangeFlow(t.toEpochDay() - 6, t.toEpochDay()).map { list ->
                val td = t.toEpochDay()
                val due = list.isEmpty() && td - prefs.workoutsSince >= 7 &&
                    (prefs.workoutNudgeDay == td || td - prefs.workoutNudgeDay >= 7)
                if (due) prefs.workoutNudgeDay = td
                due
            }
        },
    ) { list, nudge -> if (nudge) list + Tip(ua.tarilka.R.string.tip_no_workouts) else list }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Workout tracking on for a week or more: the activity badge shows even with no workouts (0 minutes). */
    val workoutsTracked: StateFlow<Boolean> = combine(prefs.workoutsEnabled, today) { on, t -> on && t.toEpochDay() - prefs.workoutsSince >= 7 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch { day.collect { refreshSteps() } }
        // What reminders need while the diary is locked (app protection): only today's counts.
        viewModelScope.launch {
            combine(day, entries, fluidMl, targets) { d, e, f, t -> listOf(d, e, f, t) }.collect { (d, e, f, t) ->
                // Only with protection on: without it the reminders read the database itself.
                if (d == LocalDate.now() && prefs.appLock) {
                    @Suppress("UNCHECKED_CAST")
                    prefs.cacheToday((d as LocalDate).toEpochDay(), (e as List<DiaryEntry>).map { it.meal }.toSet(), f as Double, (t as Targets).waterMl)
                }
            }
        }
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

    val cycle: StateFlow<ua.tarilka.data.CycleInfo?> = combine(
        prefs.cycleEnabled, allPeriods, db.cycle().days(), today,
        combine(prefs.cycleTypical, prefs.profile) { typical, p -> typical to (p.age < 18) },
    ) { on, periods, days, t, (typical, teen) ->
        if (!on) null else ua.tarilka.data.CycleInfo.of(periods, days, t.toEpochDay(), typical.takeIf { it > 0 }, teen = teen)
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

    /** The expected start for the cycle reminders, stored only while one of them is on (see Prefs.cycleNext). */
    fun syncCycleNext() { prefs.cycleNext = cycle.value?.nextStart ?: -1 }

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
                db.fluids().drinksByDay(from, to),
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

    /** The day before's meals, for "Same as yesterday". */
    val yesterday: StateFlow<Map<Int, List<DiaryEntry>>> = day.flatMapLatest { d ->
        db.diary().forDay(d.minusDays(1).toEpochDay()).map { list -> list.groupBy { it.meal } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private var repeating = false
    private var lastRepeat = -1 to 0L

    /**
     * Adds yesterday's meal again, each food at its own clock time from yesterday (not "now": late filling-in
     * is not late eating), but never in the future.
     */
    fun repeatYesterday(meal: Int) {
        // A quick double tap adds it once (also right after the first one was saved).
        if (repeating || (lastRepeat.first == meal && System.currentTimeMillis() - lastRepeat.second < 3000)) return
        val d = day.value
        val zone = java.time.ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val list = yesterday.value[meal].orEmpty().mapIndexed { i, e ->
            val clock = java.time.Instant.ofEpochMilli(e.createdAt).atZone(zone).toLocalTime()
            val at = (d.atTime(clock).atZone(zone).toInstant().toEpochMilli() + i).coerceAtMost(now + i)
            e.copy(id = 0, day = d.toEpochDay(), reason = null, hunger = null, hardToStop = false, createdAt = at)
        }
        if (list.isEmpty()) return
        repeating = true
        addEntries(list, keepTimes = true).invokeOnCompletion { repeating = false; lastRepeat = meal to System.currentTimeMillis() }
    }

    fun addFood(food: Food, grams: Double, meal: Int, time: LocalTime? = null) =
        addEntries(listOf(DiaryEntry.of(food, grams, day.value.toEpochDay(), meal)), time)

    /** [time] = when it was eaten (chosen by the person); otherwise now, or the meal's usual time on a past day. */
    fun addEntries(list: List<DiaryEntry>, time: LocalTime? = null, keepTimes: Boolean = false) = viewModelScope.launch {
        val saved = list.mapIndexed { i, e ->
            val d = LocalDate.ofEpochDay(e.day)
            val at = when {
                keepTimes -> e.createdAt
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
        // Several foods added in a row get one "why I eat" question, asked when the person is back on Today.
        if (prefs.reasonsEnabled.value && saved.isNotEmpty()) pendingReason.value = (pendingReason.value ?: emptyList()) + saved.map { it.id }
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
     * Deletes everything logged on one day: food, drinks, note, sleep, bowel, workouts, weight and waist.
     * Cycle dates stay (they span several days). What this app wrote to Health Connect for the day goes too.
     * Undo brings it all back.
     */
    fun deleteDay(d: LocalDate) = viewModelScope.launch {
        val day = d.toEpochDay()
        val food = db.diary().range(day, day)
        val drinks = db.fluids().onDay(day)
        val note = db.notes().forDay(day).first()
        val sleep = db.sleep().range(day, day).firstOrNull()
        val bowel = db.bowel().range(day, day).firstOrNull()
        val work = db.workouts().range(day, day)
        val weight = db.weights().allOnce().firstOrNull { it.day == day }
        val waist = db.waist().allOnce().firstOrNull { it.day == day }
        val count = food.size + drinks.size + work.size + listOfNotNull(note, sleep, bowel, weight, waist).size
        if (count == 0) return@launch
        db.withTransaction {
            if (food.isNotEmpty()) db.diary().deleteMany(food)
            if (drinks.isNotEmpty()) db.fluids().deleteMany(drinks)
            db.notes().delete(day)
            db.sleep().delete(day)
            db.bowel().delete(day)
            work.forEach { db.workouts().delete(it) }
            weight?.let { db.weights().delete(it) }
            waist?.let { db.waist().delete(it) }
        }
        sync {
            food.forEach { health.deleteMeal(it) }
            drinks.forEach { health.deleteFluid(it) }
            weight?.let { health.deleteWeight(it) }
        }
        offerUndo(count) {
            db.withTransaction {
                db.diary().insertAll(food)
                db.fluids().insertAll(drinks)
                note?.let { db.notes().upsert(it) }
                sleep?.let { db.sleep().upsert(it) }
                bowel?.let { db.bowel().upsert(it) }
                db.workouts().insertAll(work)
                weight?.let { db.weights().upsert(it) }
                waist?.let { db.waist().upsert(it) }
            }
            sync {
                food.forEach { health.writeMeal(it) }
                drinks.forEach { health.writeFluid(it) }
                weight?.let { health.writeWeight(it) }
            }
        }
    }

    /**
     * Clears the diary (food, drinks, weight, notes). With [everything] also own foods, recipes,
     * favorites, the profile and the not-found list. The AI model and app settings stay.
     */
    fun clearData(everything: Boolean, alsoHealthConnect: Boolean) = viewModelScope.launch {
        db.withTransaction { db.clearDiary() }
        prefs.clearCycle()
        prefs.clearCache()
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

    /** Under 18 (from the profile): teen norms for sleep and movement. */
    private fun isTeen() = prefs.profile.value.age < 18

    /** Reviewed food groups of the built-in foods (for habits and advice). */
    private val groups by lazy { ua.tarilka.data.FoodGroups.load(getApplication()) }

    private val foodsById: StateFlow<Map<Long, Food>> = db.foods().all().map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Patterns for the 7 days ending at the selected day (the "Week" card). */
    val weekHabits: StateFlow<Habits?> = combine(
        // Two weeks: the one before gives the alcohol trend.
        day.flatMapLatest { db.diary().rangeFlow(it.minusDays(14).toEpochDay(), it.toEpochDay()) },
        foodsById,
        combine(day.flatMapLatest { db.sleep().rangeFlow(it.minusDays(14).toEpochDay(), it.toEpochDay()) }, hcSleep, prefs.sleepEnabled, prefs.mealTimes) { manual, hc, on, times ->
            (if (on) hc + manual.associate { it.day to it.hours } else emptyMap()) to times
        },
        day.flatMapLatest { db.bowel().rangeFlow(it.minusDays(14).toEpochDay(), it.toEpochDay()) },
        day.flatMapLatest { db.workouts().rangeFlow(it.minusDays(14).toEpochDay(), it.toEpochDay()) },
    ) { list, foods, (sleepMap, times), bowel, workouts ->
        // Today before the evening is still unfinished: the 7 days end yesterday then, so a lone breakfast does not pull averages down.
        val end = day.value.let { d -> if (d == LocalDate.now() && LocalTime.now().hour < 21) d.minusDays(1) else d }.toEpochDay()
        val cur = (end - 6)..end
        val prev = (end - 13)..(end - 7)
        fun of(r: LongRange) = Habits.of(
            list.filter { it.day in r }, foods, groups, 7, sleep = sleepMap.filterKeys { it in r },
            bowel = bowel.filter { it.day in r }, workouts = workouts.filter { it.day in r }, withTimes = times, teen = isTeen(),
        )
        of(cur).copy(prev = of(prev)).takeIf { it.days >= ua.tarilka.data.Habits.MIN_DAYS }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    enum class Period(val days: Int) { WEEK(7), MONTH(30), YEAR(365) }

    /** Lines of the week / month / year summary, the chart's bar labels and the highlights. */
    data class PeriodReport(
        val metrics: List<ua.tarilka.data.Metric>,
        val barLabels: List<String>,
        val highlights: ua.tarilka.data.Report.Highlights,
        val days: Int,
        /** What could change and what is already good, from the person's own foods. */
        val advice: ua.tarilka.data.Advice.Result = ua.tarilka.data.Advice.Result(emptyList(), null),
    )

    suspend fun report(period: Period): PeriodReport {
        // An unfinished today would pull every average down: summaries end yesterday until the evening.
        val end = LocalDate.now().let { if (LocalTime.now().hour < 21) it.minusDays(1) else it }
        // Bars: days of the week, 7-day stretches of the month, calendar months of the year.
        val bars: List<Pair<LocalDate, LocalDate>> = when (period) {
            Period.WEEK -> (6 downTo 0).map { end.minusDays(it.toLong()).let { d -> d to d } }
            Period.MONTH -> (4 downTo 0).map { i -> end.minusDays(i * 7L + 6).let { a -> maxOf(a, end.minusDays(29)) } to end.minusDays(i * 7L) }
            Period.YEAR -> (11 downTo 0).map { i -> end.withDayOfMonth(1).minusMonths(i.toLong()).let { m -> m to minOf(m.plusMonths(1).minusDays(1), end) } }
        }
        val from = if (period == Period.YEAR) bars.first().first else end.minusDays(period.days - 1L)
        val uk = ua.tarilka.data.isUkrainian()
        return reportFor(from, end, bars) { a ->
            when (period) {
                Period.WEEK -> a.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, java.util.Locale.getDefault())
                Period.MONTH -> "${a.dayOfMonth}.%02d".format(a.monthValue)
                Period.YEAR -> a.month.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, if (uk) java.util.Locale.forLanguageTag("uk") else java.util.Locale.ENGLISH)
            }
        }
    }

    /** Bars for any stretch: days up to a month, weeks up to half a year, then calendar months. */
    fun barsFor(from: LocalDate, end: LocalDate): List<Pair<LocalDate, LocalDate>> {
        val n = end.toEpochDay() - from.toEpochDay() + 1
        return when {
            n <= 31 -> (0 until n).map { from.plusDays(it).let { d -> d to d } }
            n <= 186 -> generateSequence(end) { it.minusDays(7) }.takeWhile { !it.isBefore(from) }
                .map { b -> maxOf(b.minusDays(6), from) to b }.toList().reversed()
            else -> generateSequence(from.withDayOfMonth(1)) { it.plusMonths(1) }.takeWhile { !it.isAfter(end) }
                .map { m -> maxOf(m, from) to minOf(m.plusMonths(1).minusDays(1), end) }.toList()
        }
    }

    suspend fun reportFor(
        from: LocalDate,
        end: LocalDate,
        bars: List<Pair<LocalDate, LocalDate>>,
        label: (LocalDate) -> String,
    ): PeriodReport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val days = (end.toEpochDay() - from.toEpochDay() + 1).toInt()
        val prevFrom = from.minusDays(days.toLong())
        val foods = foodsById.value.ifEmpty { db.foods().allOnce().associateBy { it.id } }
        val diary = db.diary().range(prevFrom.toEpochDay(), end.toEpochDay())
        val sleep = sleepRange(prevFrom, end)
        val bowel = db.bowel().range(prevFrom.toEpochDay(), end.toEpochDay())
        val workouts = db.workouts().range(prevFrom.toEpochDay(), end.toEpochDay())
        val fluids = (
            db.fluids().totalsOnce(prevFrom.toEpochDay(), end.toEpochDay()) +
                db.fluids().drinksByDay(prevFrom.toEpochDay(), end.toEpochDay()).first()
            ).groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.ml } }.filterValues { it > 0 }
        val steps = if (prefs.stepsEnabled) stepsByDay(prevFrom, end).filterValues { it > 0 } else emptyMap()
        val weightsAll = db.weights().allOnce()
        val workoutsOn = prefs.workoutsEnabled.value || workouts.isNotEmpty()
        // Activity counts from when workouts were tracked, so a year is not diluted by months before.
        val activityFrom = listOfNotNull(
            workouts.minOfOrNull { it.day },
            prefs.workoutsSince.takeIf { prefs.workoutsEnabled.value },
        ).minOrNull()
        val withTimes = prefs.mealTimes.value
        // Grouped by day once: each bar then only picks its days.
        val diaryByDay = diary.groupBy { it.day }
        val bowelByDay = bowel.associateBy { it.day }
        val workoutsByDay = workouts.groupBy { it.day }
        val weightByDay = weightsAll.associateBy { it.day }

        fun data(a: LocalDate, b: LocalDate): ua.tarilka.data.PeriodData {
            val r = a.toEpochDay()..b.toEpochDay()
            val n = (b.toEpochDay() - a.toEpochDay() + 1).toInt()
            val wo = r.flatMap { workoutsByDay[it].orEmpty() }
            var h = Habits.of(
                r.flatMap { diaryByDay[it].orEmpty() }, foods, groups, n,
                sleep = r.mapNotNull { d -> sleep[d]?.let { d to it } }.toMap(), bowel = r.mapNotNull { bowelByDay[it] },
                workouts = wo, withTimes = withTimes, teen = isTeen(),
            )
            activityFrom?.let { start ->
                val tracked = (r.last - maxOf(r.first, start) + 1).toInt()
                if (tracked in 1 until n) {
                    val weeks = maxOf(tracked, 7) / 7.0
                    h = h.copy(
                        activeMinPerWeek = wo.sumOf(ua.tarilka.data.Workouts::whoMinutes) / weeks,
                        strengthPerWeek = wo.filter { it.type == ua.tarilka.data.Workouts.STRENGTH }.map { it.day }.distinct().size / weeks,
                    )
                }
            }
            val fl = r.mapNotNull { fluids[it] }
            val st = r.mapNotNull { steps[it] }
            val w = r.mapNotNull { weightByDay[it] }
            return ua.tarilka.data.PeriodData(
                habits = h,
                fluidMl = fl.takeIf { it.isNotEmpty() }?.average(), fluidDays = fl.size,
                steps = st.takeIf { it.isNotEmpty() }?.average(), stepsDays = st.size,
                weight = w.takeIf { it.isNotEmpty() }?.map { it.kg }?.average(),
                weightTrend = Nutrition.weeklyTrend(w),
                // No value for a stretch that ended before workouts were tracked: a made-up zero is not "before".
                activityPerDay = if (workoutsOn && activityFrom?.let { it <= r.last } != false) {
                    workouts.filter { it.day in r }.sumOf(ua.tarilka.data.Workouts::whoMinutes).toDouble() / n
                } else null,
            )
        }
        val cur = data(from, end)
        val prev = data(prevFrom, from.minusDays(1))
        val metrics = ua.tarilka.data.Report.build(cur, prev, bars.map { (a, b) -> data(a, b) }, targets.value, prefs.stepsGoal.value)
        val advice = ua.tarilka.data.Advice.of(
            (from.toEpochDay()..end.toEpochDay()).flatMap { diaryByDay[it].orEmpty() }, foods,
            groups, cur.habits.days, mayReduce = targets.value.limit == null,
        )
        PeriodReport(metrics, bars.map { label(it.first) }, ua.tarilka.data.Report.highlights(metrics), cur.habits.days, advice)
    }

    // ---- PDF report ----

    /** Everything about one day that a PDF report may show. */
    data class ReportDay(
        val date: LocalDate,
        val entries: List<DiaryEntry>,
        val fluidMl: Double,
        val weight: ua.tarilka.data.WeightEntry?,
        val sleep: Double?,
        val bowel: ua.tarilka.data.BowelEntry?,
        val workouts: List<ua.tarilka.data.WorkoutEntry>,
        val steps: Long?,
        val note: String?,
        val periodDay: ua.tarilka.data.PeriodDay?,
        val inPeriod: Boolean,
    )

    data class ReportData(
        val from: LocalDate,
        val to: LocalDate,
        val summary: PeriodReport,
        val days: List<ReportDay>,
        val periods: List<ua.tarilka.data.PeriodEntry>,
        val cycle: ua.tarilka.data.CycleInfo?,
        val targets: Targets,
        val profile: Profile,
    )

    /** Writes a PDF report to [uri]; [context] gives the texts in the app's language. */
    fun exportPdf(context: android.content.Context, uri: android.net.Uri, from: LocalDate, to: LocalDate, parts: Set<ua.tarilka.ui.ReportPart>, withProfile: Boolean) =
        viewModelScope.launch {
            val app = getApplication<Application>()
            runCatching {
                val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { reportData(from, to) }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        PdfReport.write(context, out, data, parts, withProfile, prefs.mealTimes.value)
                    } ?: error("no output")
                }
            }.onSuccess { dataMessage.value = app.getString(ua.tarilka.R.string.pdf_saved) }
                .onFailure {
                    // No half-written file left behind.
                    runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }
                    dataMessage.value = app.getString(ua.tarilka.R.string.pdf_failed)
                }
        }

    /** The first day with any entry: "all time" starts there. */
    suspend fun firstEntryDay(): LocalDate {
        val candidates = listOfNotNull(
            db.diary().firstDay(),
            db.weights().allOnce().minOfOrNull { it.day },
            db.notes().allOnce().minOfOrNull { it.day },
        )
        return candidates.minOrNull()?.let(LocalDate::ofEpochDay) ?: LocalDate.now()
    }

    suspend fun reportData(from: LocalDate, to: LocalDate): ReportData {
        val r = from.toEpochDay()..to.toEpochDay()
        val uk = ua.tarilka.data.isUkrainian()
        val summary = reportFor(from, to, barsFor(from, to)) { a ->
            if (to.toEpochDay() - from.toEpochDay() + 1 > 186) {
                a.month.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, if (uk) java.util.Locale.forLanguageTag("uk") else java.util.Locale.ENGLISH)
            } else "${a.dayOfMonth}.%02d".format(a.monthValue)
        }
        val diary = db.diary().range(r.first, r.last).groupBy { it.day }
        val fluids = (
            db.fluids().totalsOnce(r.first, r.last) +
                db.fluids().drinksByDay(r.first, r.last).first()
            ).groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.ml } }
        val weights = db.weights().allOnce().filter { it.day in r }.associateBy { it.day }
        val sleep = sleepRange(from, to)
        val bowel = db.bowel().range(r.first, r.last).associateBy { it.day }
        val workouts = db.workouts().range(r.first, r.last).groupBy { it.day }
        val steps = if (prefs.stepsEnabled) stepsByDay(from, to) else emptyMap()
        val notes = db.notes().allOnce().filter { it.day in r }.associate { it.day to it.text }
        val cyclePeriods = periods.value
        val pDays = periodDays.value.associateBy { it.day }
        val days = r.map { d ->
            ReportDay(
                LocalDate.ofEpochDay(d), diary[d].orEmpty().sortedBy { it.createdAt }, fluids[d] ?: 0.0, weights[d], sleep[d], bowel[d],
                workouts[d].orEmpty(), steps[d]?.takeIf { it > 0 }, notes[d], pDays[d],
                cyclePeriods.any { p -> d in p.start..(p.end ?: (p.start + 6)) },
            )
        }
        return ReportData(
            from, to, summary, days, cyclePeriods.filter { it.start <= r.last && (it.end ?: it.start) >= r.first - 60 },
            cycle.value, targets.value, profile.value,
        )
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
        val h = Habits.of(cur, foods, groups, period.days, sleep = sleepAll.filterKeys { it >= from.toEpochDay() }, bowel = bowelAll.first, workouts = workAll.first, withTimes = prefs.mealTimes.value, teen = isTeen())
        val hp = Habits.of(prev, foods, groups, period.days, sleep = sleepAll.filterKeys { it < from.toEpochDay() }, bowel = bowelAll.second, workouts = workAll.second, withTimes = prefs.mealTimes.value, teen = isTeen())
        // Water entries plus drinks logged as food, as on the Today screen.
        val fluidsByDay = (
            db.fluids().totalsOnce(prevFrom.toEpochDay(), end.toEpochDay()) +
                db.fluids().drinksByDay(prevFrom.toEpochDay(), end.toEpochDay()).first()
            ).groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.ml } }
        val tg = targets.value
        val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val zone = java.time.ZoneId.systemDefault()
        fun Double.r() = toInt()
        // Under 18 the AI never sees a calorie number (it tends to repeat what it sees).
        fun kc(v: Double) = if (tg.showKcal) "${v.r()} kcal, " else ""
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
                (if (tg.showKcal) "Daily targets: ${tg.kcal.r()} kcal, protein ${tg.protein.r()} g, fat ${tg.fat.r()} g, carbs ${tg.carbs.r()} g, "
                else "Daily targets: protein ${tg.protein.r()} g, ") +
                    "fiber ${tg.fiber.r()} g, fluids ${tg.waterMl.r()} ml." +
                    (if (tg.limit == ua.tarilka.data.Limit.PREGNANT) " Goal: no weight loss; weight gain in pregnancy is expected." else " Goal: ${tg.goal.name.lowercase()} weight.") +
                    " " + tg.aiSafety(),
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
                    appendLine("- $name $d: ${kc(t.kcal)}protein ${t.protein.r()} g, fiber ${t.fiber.r()} g$at$drinks")
                }
                Period.MONTH -> for (w in 0 until 4) {
                    val a = from.plusDays(w * 7L)
                    val b = if (w == 3) end else a.plusDays(6)
                    val hw = Habits.of(cur.filter { it.day in a.toEpochDay()..b.toEpochDay() }, foods, groups, (b.toEpochDay() - a.toEpochDay() + 1).toInt())
                    appendLine("- $a to $b: ${hw.days} days logged, average ${kc(hw.kcalAvg)}protein ${hw.proteinAvg.r()} g")
                }
                Period.YEAR -> cur.groupBy { LocalDate.ofEpochDay(it.day).withDayOfMonth(1) }.toSortedMap().forEach { (m, list) ->
                    val hm = Habits.of(list, foods, groups, m.lengthOfMonth())
                    val kg = weights.value.filter { LocalDate.ofEpochDay(it.day).withDayOfMonth(1) == m }.map { it.kg }
                    appendLine(
                        "- ${m.year}-${"%02d".format(m.monthValue)}: ${hm.days} days logged, average ${kc(hm.kcalAvg)}protein ${hm.proteinAvg.r()} g" +
                            (if (kg.isNotEmpty()) ", weight ${"%.1f".format(java.util.Locale.ROOT, kg.average())} kg" else ""),
                    )
                }
            }
            fun line(label: String, now: String, before: String) = appendLine("$label: $now (previous $periodName: $before)")
            line("Days with food logged", "${h.days}", "${hp.days}")
            if (h.days > 0) {
                line("Average per logged day", "${kc(h.kcalAvg)}protein ${h.proteinAvg.r()} g, fiber ${h.fiberAvg.r()} g", if (hp.days > 0) "${kc(hp.kcalAvg)}protein ${hp.proteinAvg.r()} g" else "no data")
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
                            (if (tg.showKcal && pre.isNotEmpty() && other.isNotEmpty()) "; average kcal in the 7 days before: ${pre.average().r()}, other days: ${other.average().r()}" else "") +
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
            // Cutting-down tips ("alcohol-free days") would read as permission under 18 or in pregnancy.
            val noAlcohol = !tg.showKcal || tg.limit == ua.tarilka.data.Limit.PREGNANT
            if (h.alcoholPerWeek > 0 && noAlcohol) {
                appendLine("Alcohol was logged. For this person no amount of alcohol is advised: say kindly that the safest is none, give no tips for drinking less, and mention a doctor or a trusted adult can help.")
            } else if (h.alcoholPerWeek > 0) {
                appendLine("For alcohol (WHO brief intervention; if there is alcohol, always include 2 of these, more if drinking is frequent or heavy):")
                Habits.ALCOHOL_TECHNIQUES.forEach { appendLine("- $it") }
            }
            append(
                "Write a kind, encouraging summary: first what went well or improved, then 2–3 patterns worth attention, " +
                    "then the techniques. Compare with the previous $periodName where it helps. Days with nothing logged were probably just not recorded. " +
                    "Never shame. Do not set weight targets or suggest diets. If sleep is short, mention that it raises appetite. " +
                    "Suggest seeing a doctor or a specialist only if weight falls faster than about 1 % of body weight a week, " +
                    (if (tg.showKcal) "the average is under the basal metabolism (${tg.bmr.r()} kcal), " else "") + "a quarter or more of calories are eaten late on most days, " +
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

    val waist: StateFlow<List<ua.tarilka.data.WaistEntry>> = db.waist().all()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun saveWaist(cm: Double) = viewModelScope.launch { db.waist().upsert(ua.tarilka.data.WaistEntry(LocalDate.now().toEpochDay(), cm)) }

    fun deleteWaist(w: ua.tarilka.data.WaistEntry) = viewModelScope.launch { db.waist().delete(w) }

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
        }.getOrElse { failedExport(uri, it) }
    }

    /** A failed export leaves no empty file that looks like a backup. */
    private fun failedExport(uri: android.net.Uri, t: Throwable): String {
        val app = getApplication<Application>()
        runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }
        return app.getString(ua.tarilka.R.string.data_error, t.message ?: "")
    }

    fun exportCsv(uri: android.net.Uri) = viewModelScope.launch {
        dataMessage.value = runCatching {
            val ukrainian = ua.tarilka.data.isUkrainian()
            writeText(uri, kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ua.tarilka.data.Backup.diaryCsv(db, ukrainian) })
            getApplication<Application>().getString(ua.tarilka.R.string.export_done)
        }.getOrElse { failedExport(uri, it) }
    }

    private val MAX_BACKUP_BYTES = 64 * 1024 * 1024

    /** Password chosen for the export in progress ("" = no encryption, null = unknown). */
    var exportPassword: String? = null

    /** Step 1: read and check the file. Step 2 ([confirmImport]) replaces the data. */
    val pendingImport = MutableStateFlow<Pair<String, ua.tarilka.data.Backup.Summary>?>(null)

    fun readImport(uri: android.net.Uri) = viewModelScope.launch {
        wrongPassword.value = false
        runCatching {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                    // A real backup is a few MB at most; a huge file is not one.
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        require(out.size() <= MAX_BACKUP_BYTES) { "file too large" }
                    }
                    out.toString(Charsets.UTF_8.name())
                }
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
        val plain = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ua.tarilka.data.Backup.decrypt(text, password) }
        }.getOrElse { wrongPassword.value = true; return@launch }
        encryptedImport.value = null
        wrongPassword.value = false
        // Decrypted, but maybe not a Tarilka backup at all.
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ua.tarilka.data.Backup.peek(plain) } }
            .onSuccess { pendingImport.value = plain to it }
            .onFailure { dataMessage.value = getApplication<Application>().getString(ua.tarilka.R.string.data_error, it.message ?: "") }
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
