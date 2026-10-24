package ua.tarilka

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ua.tarilka.ai.GemmaEngine
import ua.tarilka.ai.ModelStore
import ua.tarilka.data.AppDatabase
import ua.tarilka.data.FoodSeeder
import ua.tarilka.data.Prefs

class TarilkaApp : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var models: ModelStore
        private set
    lateinit var gemma: GemmaEngine
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ready = MutableStateFlow(false)
    /** True once the built-in food database is loaded. */
    val ready: StateFlow<Boolean> get() = _ready

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.create(this)
        prefs = Prefs(this)
        models = ModelStore(this)
        gemma = GemmaEngine(this, models)
        scope.launch {
            FoodSeeder.seedIfNeeded(this@TarilkaApp, db, prefs)
            _ready.value = true
        }
    }
}
