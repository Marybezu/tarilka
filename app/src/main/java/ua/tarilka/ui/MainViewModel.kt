package ua.tarilka.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    val tips: StateFlow<List<Tip>> = combine(
        combine(totals, targets, entries) { t, tg, e -> Triple(t, tg, e.size) },
        combine(day, profile, weeklyTrend, latestWeight) { d, p, tr, w -> listOf(d, p, tr, w) },
    ) { (t, tg, n), rest ->
        val d = rest[0] as LocalDate
        val finished = d.isBefore(LocalDate.now()) || LocalTime.now().hour >= 21
        Tips.forDay(t, tg, n, finished, rest[1] as Profile, rest[2] as Double?, rest[3] as Double?)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val searchResults: StateFlow<List<Food>> = query.debounce(120).flatMapLatest { q ->
        val clean = q.trim().lowercase().replace('ʼ', '\'').replace('’', '\'')
        if (clean.isEmpty()) db.foods().all() else db.foods().search(clean)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recent: StateFlow<List<Food>> = db.foods().recent()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun shiftDay(by: Long) { day.value = day.value.plusDays(by) }
    fun today() { day.value = LocalDate.now() }

    fun addFood(food: Food, grams: Double, meal: Int) = viewModelScope.launch {
        db.diary().insert(DiaryEntry.of(food, grams, day.value.toEpochDay(), meal))
    }

    fun addEntries(list: List<DiaryEntry>) = viewModelScope.launch { db.diary().insertAll(list) }

    fun updateGrams(entry: DiaryEntry, grams: Double) = viewModelScope.launch {
        if (entry.grams <= 0) return@launch
        val k = grams / entry.grams
        db.diary().update(
            entry.copy(
                grams = grams, kcal = entry.kcal * k, protein = entry.protein * k,
                fat = entry.fat * k, carbs = entry.carbs * k, fiber = entry.fiber * k,
            ),
        )
    }

    fun moveEntry(entry: DiaryEntry, meal: Int) = viewModelScope.launch { db.diary().update(entry.copy(meal = meal)) }
    fun deleteEntry(entry: DiaryEntry) = viewModelScope.launch { db.diary().delete(entry) }

    fun saveFood(food: Food) = viewModelScope.launch {
        val f = food.copy(searchKey = Food.buildSearchKey(food.nameUk, food.nameEn))
        if (f.id == 0L) db.foods().insert(f) else db.foods().update(f)
    }

    fun deleteFood(food: Food) = viewModelScope.launch { if (food.isCustom) db.foods().delete(food) }
    fun toggleFavorite(food: Food) = viewModelScope.launch { db.foods().update(food.copy(favorite = !food.favorite)) }

    fun saveWeight(kg: Double, date: LocalDate = LocalDate.now()) = viewModelScope.launch {
        db.weights().upsert(WeightEntry(date.toEpochDay(), kg))
    }

    fun deleteWeight(w: WeightEntry) = viewModelScope.launch { db.weights().delete(w) }

    fun saveProfile(p: Profile) = prefs.saveProfile(p)
}
