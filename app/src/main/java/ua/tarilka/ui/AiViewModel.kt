package ua.tarilka.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.tarilka.TarilkaApp
import ua.tarilka.ai.DetectedFood
import ua.tarilka.ai.FoodMatcher
import ua.tarilka.ai.ModelStore
import ua.tarilka.ai.PhotoUtil
import ua.tarilka.data.DiaryEntry
import ua.tarilka.data.Food

data class ReviewItem(
    val detected: DetectedFood,
    val food: Food?,
    val grams: Double,
    val include: Boolean = true,
) {
    val kcal: Double get() = (food?.kcal ?: 0.0) * grams / 100
    val isEstimate: Boolean get() = food == null
}

data class PhotoUi(
    /** Small in-memory preview; the photo files are deleted right after analysis. */
    val preview: android.graphics.Bitmap? = null,
    val note: String = "",
    val working: Boolean = false,
    val analyzed: Boolean = false,
    val items: List<ReviewItem> = emptyList(),
    val error: String? = null,
    val seconds: Int = 0,
    /** Shown when nothing was recognized, to see what went wrong. */
    val raw: String = "",
    val backend: String = "",
    val loadSec: Int = 0,
    val analyzeSec: Int = 0,
)

class AiViewModel(app: Application) : AndroidViewModel(app) {
    private val tapp = app as TarilkaApp
    private val models = tapp.models
    private val gemma = tapp.gemma

    private val _model = MutableStateFlow<ModelStore.State>(ModelStore.State.Missing)
    val model: StateFlow<ModelStore.State> = _model.asStateFlow()

    private val _importProgress = MutableStateFlow<Float?>(null)
    val importProgress: StateFlow<Float?> = _importProgress.asStateFlow()

    private val _photo = MutableStateFlow(PhotoUi())
    val photo: StateFlow<PhotoUi> = _photo.asStateFlow()

    private val _advice = MutableStateFlow<String?>(null)
    val advice: StateFlow<String?> = _advice.asStateFlow()
    private val _adviceLoading = MutableStateFlow(false)
    val adviceLoading: StateFlow<Boolean> = _adviceLoading.asStateFlow()

    init {
        refreshModel()
    }

    fun refreshModel() {
        _model.value = models.state()
    }

    /** Message after importing a model (e.g. the original file could not be deleted). */
    val importNote = MutableStateFlow<Int?>(null)

    fun deleteModel() = viewModelScope.launch {
        gemma.release()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { models.delete() }
        refreshModel()
    }

    fun import(uri: Uri, deleteOriginal: Boolean) = viewModelScope.launch {
        _importProgress.value = 0f
        importNote.value = null
        gemma.release()
        runCatching { models.import(uri, deleteOriginal) { p -> _importProgress.value = p } }
            .onSuccess { r ->
                refreshModel()
                importNote.value = when (r.originalDeleted) {
                    true -> ua.tarilka.R.string.ai_original_deleted
                    false -> ua.tarilka.R.string.ai_original_kept
                    null -> null
                }
            }
            .onFailure { _model.value = ModelStore.State.Failed(it.message ?: "import failed") }
        _importProgress.value = null
    }

    // ---- photo analysis ----

    fun warmUp() {
        if (_model.value is ModelStore.State.Ready) viewModelScope.launch { gemma.warmUp() }
    }

    fun setNote(text: String) = _photo.update { it.copy(note = text) }

    fun resetPhoto() {
        _photo.value = PhotoUi()
        PhotoUtil.clear(getApplication())
    }

    fun analyze(uri: Uri) = viewModelScope.launch {
        if (_importProgress.value != null) {
            _photo.update { it.copy(error = "the model is still being copied") }
            return@launch
        }
        _photo.update { it.copy(working = true, error = null, items = emptyList(), analyzed = false, seconds = 0) }
        val ticker = launch {
            while (isActive) { delay(1000); _photo.update { it.copy(seconds = it.seconds + 1) } }
        }
        try {
            val img = PhotoUtil.prepare(getApplication(), uri)
            _photo.update { it.copy(preview = PhotoUtil.thumbnail(img)) }
            val foods = tapp.db.foods().allOnce()
            val result = gemma.analyzePhoto(img, _photo.value.note)
            _photo.update {
                it.copy(raw = result.raw.take(600), backend = result.backend, loadSec = result.loadSec, analyzeSec = result.analyzeSec)
            }
            val items = result.foods.map { d ->
                val match = FoodMatcher.best(d.nameEn, d.nameUk, foods)
                // Not in the database: off until the person picks the right food with "Replace".
                ReviewItem(detected = d, food = match?.food, grams = d.grams, include = match != null)
            }
            // Unknown foods go to the "Not found" list in Options, like empty searches.
            items.filter { it.food == null }.forEach { tapp.prefs.recordMissing(it.detected.nameEn) }
            _photo.update { it.copy(items = items, analyzed = true) }
        } catch (t: Throwable) {
            _photo.update { it.copy(error = t.message ?: t.javaClass.simpleName) }
        } finally {
            // Nothing of the photo stays on disk.
            PhotoUtil.clear(getApplication())
            ticker.cancel()
            _photo.update { it.copy(working = false) }
        }
    }

    fun setGrams(i: Int, grams: Double) = updateItem(i) { it.copy(grams = grams) }
    fun setInclude(i: Int, on: Boolean) = updateItem(i) { it.copy(include = on) }
    fun replaceFood(i: Int, food: Food) = updateItem(i) { it.copy(food = food, include = true) }

    fun addManualItem(food: Food) = _photo.update {
        val d = DetectedFood(food.nameEn, food.nameUk, food.portionG ?: 100.0, null, null, null, null, "manual")
        it.copy(items = it.items + ReviewItem(d, food, d.grams))
    }

    private fun updateItem(i: Int, f: (ReviewItem) -> ReviewItem) = _photo.update { ui ->
        ui.copy(items = ui.items.mapIndexed { idx, item -> if (idx == i) f(item) else item })
    }

    /** Only foods from the database are saved: the numbers never come from the model. */
    fun entriesFor(day: Long, meal: Int): List<DiaryEntry> = _photo.value.items
        .filter { it.include && it.grams > 0 && it.food != null }
        .map { DiaryEntry.of(it.food!!, it.grams, day, meal, fromAi = true) }

    // ---- advice ----

    private val _weekAdvice = MutableStateFlow<String?>(null)
    val weekAdvice: StateFlow<String?> = _weekAdvice.asStateFlow()
    private val _weekLoading = MutableStateFlow(false)
    val weekLoading: StateFlow<Boolean> = _weekLoading.asStateFlow()

    fun requestAdvice(summary: String, ukrainian: Boolean) = ask(summary, ukrainian, _advice, _adviceLoading)

    /** The summary is built by the caller (it needs the diary), so it is passed as a function. */
    fun requestWeekAdvice(summary: suspend () -> String, ukrainian: Boolean) = viewModelScope.launch {
        _weekLoading.value = true
        ask(summary(), ukrainian, _weekAdvice, _weekLoading)
    }

    fun clearWeekAdvice() { _weekAdvice.value = null }

    private fun ask(summary: String, ukrainian: Boolean, out: MutableStateFlow<String?>, loading: MutableStateFlow<Boolean>) = viewModelScope.launch {
        loading.value = true
        out.value = runCatching {
            var text = gemma.advice(summary, ukrainian)
            // The small model sometimes slips into Russian or another alphabet: ask once more, then show nothing.
            val lang = ua.tarilka.data.Lang
            if (!lang.isClean(text, ukrainian)) text = gemma.advice(summary, ukrainian)
            if (lang.isClean(text, ukrainian)) text else null
        }.getOrElse { "⚠ ${it.message}" }
        loading.value = false
    }

    fun clearAdvice() { _advice.value = null }
}
