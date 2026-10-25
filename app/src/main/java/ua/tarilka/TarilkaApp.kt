package ua.tarilka

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.tarilka.ai.GemmaEngine
import ua.tarilka.ai.ModelStore
import ua.tarilka.data.AppDatabase
import ua.tarilka.data.FoodSeeder
import ua.tarilka.data.Prefs
import ua.tarilka.health.HealthSync
import ua.tarilka.health.StepSensor

/** Light / dark / system for the whole app, including system bars. */
fun applyThemeMode(mode: Int) {
    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
        when (mode) {
            1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        },
    )
}

class TarilkaApp : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var models: ModelStore
        private set
    lateinit var gemma: GemmaEngine
        private set
    lateinit var health: HealthSync
        private set
    lateinit var stepSensor: StepSensor
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.create(this)
        prefs = Prefs(this)
        applyThemeMode(prefs.themeMode)
        models = ModelStore(this)
        gemma = GemmaEngine(this, models, scope)
        health = HealthSync(this)
        stepSensor = StepSensor(this, prefs)
        scope.launch {
            FoodSeeder.seedIfNeeded(this@TarilkaApp, db, prefs)
        }
        // Alarms can be lost (force stop, clock change); setting them again is cheap.
        runCatching { ua.tarilka.notify.Reminders.schedule(this, missedOnly = true) }
    }
}
