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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    }
    val query = MutableStateFlow("")

    val profile: StateFlow<Profile> = prefs.profile

    val weights: StateFlow<List<WeightEntry>> =
        db.weights().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        day.flatMapLatest { db.fluids().drinksFromDiary(it.toEpochDay(), ua.tarilka.data.FluidRules.ALCOHOL, ua.tarilka.data.FluidRules.MILK_DRINKS) },
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
        combine(fluidMl, steps) { f, st -> f to st },
    ) { (t, tg, n), rest, (fluid, st) ->
        val d = rest[0] as LocalDate
        val now = LocalTime.now().hour
        val finished = d.isBefore(LocalDate.now()) || now >= 21
        val afternoon = d == LocalDate.now() && now >= 15
        Tips.forDay(t, tg, n, finished, rest[1] as Profile, rest[2] as Double?, rest[3] as Double?, fluid, afternoon, st)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { day.collect { refreshSteps() } }
    }

    /** Reads steps for the selected day: Health Connect first, then the phone's step sensor (today only). */
    fun refreshSteps() = viewModelScope.launch {
        if (!prefs.stepsEnabled) { _steps.value = null; return@launch }
        val d = day.value
        val fromHc = runCatching { health.steps(d) }.getOrNull()
        val fromSensor = if (d == LocalDate.now()) runCatching { stepSensor.today() }.getOrNull() else null
        val useHc = fromHc != null && (fromHc > 0 || fromSensor == null)
        _steps.value = if (useHc) fromHc else fromSensor
        stepsFromHc.value = useHc
    }

    fun setStepsEnabled(on: Boolean) {
        prefs.stepsEnabled = on
        _stepsEnabled.value = on
        refreshSteps()
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
            db.diary().totals(from, to), db.fluids().totals(from, to), db.weights().all(), db.notes().all(),
        ) { food, fluid, weight, notes ->
            val f = food.associateBy { it.day }
            val w = fluid.associateBy { it.day }
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

    fun addFood(food: Food, grams: Double, meal: Int) = addEntries(listOf(DiaryEntry.of(food, grams, day.value.toEpochDay(), meal)))

    fun addEntries(list: List<DiaryEntry>) = viewModelScope.launch {
        val saved = list.map { e ->
            val withTime = e.copy(createdAt = timestampFor(LocalDate.ofEpochDay(e.day)))
            withTime.copy(id = db.diary().insert(withTime))
        }
        sync { saved.forEach { health.writeMeal(it) } }
    }

    fun updateGrams(entry: DiaryEntry, grams: Double) = viewModelScope.launch {
        if (entry.grams <= 0) return@launch
        val k = grams / entry.grams
        val updated = entry.copy(
            grams = grams, kcal = entry.kcal * k, protein = entry.protein * k,
            fat = entry.fat * k, carbs = entry.carbs * k, fiber = entry.fiber * k,
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

    // ---- habits and summaries ----

    private val foodsById: StateFlow<Map<Long, Food>> = db.foods().all().map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Patterns for the 7 days ending at the selected day (the "Week" card). */
    val weekHabits: StateFlow<Habits?> = combine(
        day.flatMapLatest { db.diary().rangeFlow(it.minusDays(6).toEpochDay(), it.toEpochDay()) },
        foodsById,
    ) { list, foods -> Habits.of(list, foods, 7).takeIf { it.days >= 2 } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    enum class Period(val days: Int) { WEEK(7), MONTH(30), YEAR(365) }

    suspend fun habitsFor(period: Period): Habits {
        val end = LocalDate.now()
        val list = db.diary().range(end.minusDays(period.days - 1L).toEpochDay(), end.toEpochDay())
        return Habits.of(list, foodsById.value.ifEmpty { db.foods().allOnce().associateBy { it.id } }, period.days)
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
        val h = Habits.of(cur, foods, period.days)
        val hp = Habits.of(prev, foods, period.days)
        val fluidsByDay = db.fluids().totalsOnce(prevFrom.toEpochDay(), end.toEpochDay()).associate { it.day to it.ml }
        val tg = targets.value
        val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val zone = java.time.ZoneId.systemDefault()
        fun Double.r() = toInt()
        fun avgFluid(a: LocalDate, b: LocalDate) =
            fluidsByDay.filterKeys { it in a.toEpochDay()..b.toEpochDay() }.values.let { if (it.isEmpty()) null else it.average().r() }
        val steps = if (prefs.stepsEnabled) health.steps(from, end) else null
        val prevSteps = if (prefs.stepsEnabled) health.steps(prevFrom, from.minusDays(1)) else null
        val periodName = period.name.lowercase()
        return buildString {
            appendLine("Summary of the person's last $periodName ($from to $end), compared with the $periodName before.")
            appendLine(
                "Daily targets: ${tg.kcal.r()} kcal, protein ${tg.protein.r()} g, fat ${tg.fat.r()} g, carbs ${tg.carbs.r()} g, " +
                    "fiber ${tg.fiber.r()} g, fluids ${tg.waterMl.r()} ml. Goal: ${profile.value.goal.name.lowercase()} weight.",
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
                    appendLine("- $name $d: ${t.kcal.r()} kcal, protein ${t.protein.r()} g, fiber ${t.fiber.r()} g; logged at ${times.joinToString()}$drinks")
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
                line("Calories logged after 22:00", "${h.latePct.r()} %", if (hp.days > 0) "${hp.latePct.r()} %" else "no data")
                line("Alcohol", "${"%.1f".format(java.util.Locale.ROOT, h.alcoholPerWeek)} standard drinks a week", "%.1f".format(java.util.Locale.ROOT, hp.alcoholPerWeek))
                line("Days with fast food", "${h.fastFoodDays}", "${hp.fastFoodDays}")
            }
            line("Average drinks (water etc.) on days with any", avgFluid(from, end)?.let { "$it ml" } ?: "none logged", avgFluid(prevFrom, from.minusDays(1))?.let { "$it ml" } ?: "none")
            if (steps != null) line("Average steps a day", "${steps / n}", prevSteps?.let { "${it / n}" } ?: "no data")
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
            appendLine("Techniques you may suggest (pick 1–2 that fit what you see, explain simply):")
            Habits.TECHNIQUES.forEach { appendLine("- $it") }
            append(
                "Write a kind, encouraging summary: first what went well or improved, then 2–3 patterns worth attention, " +
                    "then the techniques. Compare with the previous $periodName where it helps. Days with nothing logged were probably just not recorded. " +
                    "Never shame. Do not set weight targets or suggest diets. Suggest seeing a doctor or a specialist only if weight falls faster " +
                    "than about 1 % of body weight a week, the average is under 1200 kcal, or a quarter or more of calories are eaten late on most days.",
            )
        }
    }

    fun saveFood(food: Food) = viewModelScope.launch {
        val f = food.copy(searchKey = Food.buildSearchKey(food.nameUk, food.nameEn))
        if (f.id == 0L) db.foods().insert(f) else db.foods().update(f)
    }

    fun deleteFood(food: Food) = viewModelScope.launch { if (food.isCustom) db.foods().delete(food) }
    fun toggleFavorite(food: Food) = viewModelScope.launch { db.foods().update(food.copy(favorite = !food.favorite)) }

    fun saveWeight(kg: Double, date: LocalDate = LocalDate.now()) = viewModelScope.launch {
        val w = WeightEntry(date.toEpochDay(), kg)
        db.weights().upsert(w)
        sync { health.writeWeight(w) }
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
}
