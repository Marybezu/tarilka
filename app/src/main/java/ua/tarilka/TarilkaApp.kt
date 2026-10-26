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
    private val _db = kotlinx.coroutines.flow.MutableStateFlow<AppDatabase?>(null)
    /** The open diary database; null while app protection keeps it locked. */
    val dbState: kotlinx.coroutines.flow.StateFlow<AppDatabase?> = _db
    /** For screens and view models, which exist only while the database is open. */
    val db: AppDatabase get() = _db.value ?: error("The diary is locked")
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
        prefs = Prefs(this)
        ua.tarilka.security.DbCrypt.recover(this)
        // With app protection the database stays closed until fingerprint or the phone PIN.
        if (!prefs.appLock) openDb(null)
        applyThemeMode(prefs.themeMode)
        models = ModelStore(this)
        gemma = GemmaEngine(this, models, scope)
        health = HealthSync(this)
        stepSensor = StepSensor(this, prefs)
        // Alarms can be lost (force stop, clock change); setting them again is cheap.
        runCatching { ua.tarilka.notify.Reminders.schedule(this, missedOnly = true) }
    }

    /** Opens the database (plain, or encrypted with [rawKey]); the built-in foods are refreshed after. */
    fun openDb(rawKey: ByteArray?) {
        val d = AppDatabase.create(this, rawKey)
        // Encrypted: open now, so a wrong key fails here and not later in a screen.
        if (rawKey != null) d.openHelper.writableDatabase
        _db.value = d
        scope.launch { FoodSeeder.seedIfNeeded(this@TarilkaApp, d, prefs) }
    }

    /** Closes the database: screens switch to the lock screen first, then it is closed. */
    fun closeDb(now: Boolean = false) {
        val d = _db.value ?: return
        _db.value = null
        if (now) d.close() else scope.launch { kotlinx.coroutines.delay(1500); d.close() }
    }
}
