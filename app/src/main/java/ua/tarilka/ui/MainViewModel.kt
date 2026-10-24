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
        day.flatMapLatest { db.fluids().drinksFromDiary(it.toEpochDay()) },
    ) { f, drinks -> f.sumOf { it.ml } + drinks }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)

    private val _stepsEnabled = MutableStateFlow(prefs.stepsEnabled)
    val stepsEnabled: StateFlow<Boolean> = _stepsEnabled.asStateFlow()
    private val _healthSync = MutableStateFlow(prefs.healthSync)
    val healthSync: StateFlow<Boolean> = _healthSync.asStateFlow()
    private val _steps = MutableStateFlow<Long?>(null)
    val steps: StateFlow<Long?> = _steps.asStateFlow()

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
        _steps.value = when {
            fromHc != null && fromHc > 0 -> fromHc
            fromSensor != null -> fromSensor
            else -> fromHc
        }
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

    data class HistoryDay(val day: LocalDate, val kcal: Double, val fluidMl: Double, val weight: Double?, val note: Boolean)

    val history: StateFlow<List<HistoryDay>> = run {
        val to = LocalDate.now().toEpochDay()
        val from = to - 365
        combine(
            db.diary().totals(from, to), db.fluids().totals(from, to), db.weights().all(), db.notes().all(),
        ) { food, fluid, weight, notes ->
            val f = food.associateBy { it.day }
            val w = fluid.associateBy { it.day }
            val kg = weight.associateBy { it.day }
            val n = notes.map { it.day }.toSet()
            (f.keys + w.keys + kg.keys.filter { it >= from } + n).sortedDescending().map { d ->
                HistoryDay(LocalDate.ofEpochDay(d), f[d]?.kcal ?: 0.0, w[d]?.ml ?: 0.0, kg[d]?.kg, d in n)
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

    fun moveEntry(entry: DiaryEntry, meal: Int) = viewModelScope.launch { db.diary().update(entry.copy(meal = meal)) }
    fun deleteEntry(entry: DiaryEntry) = viewModelScope.launch {
        db.diary().delete(entry)
        sync { health.deleteMeal(entry) }
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

    fun deleteWeight(w: WeightEntry) = viewModelScope.launch { db.weights().delete(w) }

    fun saveProfile(p: Profile) = prefs.saveProfile(p)

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
            val json = ua.tarilka.data.Backup.export(db, prefs)
            writeText(uri, if (password.isEmpty()) json else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                ua.tarilka.data.Backup.encrypt(json, password)
            })
            getApplication<Application>().getString(ua.tarilka.R.string.export_done)
        }.getOrElse { getApplication<Application>().getString(ua.tarilka.R.string.data_error, it.message ?: "") }
    }

    fun exportCsv(uri: android.net.Uri) = viewModelScope.launch {
        dataMessage.value = runCatching {
            writeText(uri, ua.tarilka.data.Backup.diaryCsv(db, ua.tarilka.data.isUkrainian()))
            getApplication<Application>().getString(ua.tarilka.R.string.export_done)
        }.getOrElse { getApplication<Application>().getString(ua.tarilka.R.string.data_error, it.message ?: "") }
    }

    /** Step 1: read and check the file. Step 2 ([confirmImport]) replaces the data. */
    val pendingImport = MutableStateFlow<Pair<String, ua.tarilka.data.Backup.Summary>?>(null)

    fun readImport(uri: android.net.Uri) = viewModelScope.launch {
        runCatching {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            }
            if (ua.tarilka.data.Backup.isEncrypted(text)) {
                encryptedImport.value = text
            } else {
                pendingImport.value = text to ua.tarilka.data.Backup.peek(text)
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
            val s = ua.tarilka.data.Backup.restore(db, prefs, text)
            getApplication<Application>().getString(ua.tarilka.R.string.import_done, s.diary, s.weights)
        }.getOrElse { getApplication<Application>().getString(ua.tarilka.R.string.data_error, it.message ?: "") }
    }

    private suspend fun writeText(uri: android.net.Uri, text: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
}
