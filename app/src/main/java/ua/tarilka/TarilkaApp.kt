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
        CrashLog.install(this)
        prefs = Prefs(this)
        reconcileProtection()
        // With app protection the database stays closed until fingerprint or the phone PIN.
        if (!prefs.appLock) openDb(null)
        applyThemeMode(prefs.themeMode)
        models = ModelStore(this)
        gemma = GemmaEngine(this, models, scope)
        health = HealthSync(this)
        stepSensor = StepSensor(this, prefs)
        watchForLock()
        // Alarms can be lost (force stop, clock change); setting them again is cheap.
        runCatching { ua.tarilka.notify.Reminders.schedule(this, missedOnly = true) }
    }

    // ---- app protection: one place for the open database, its key and locking ----

    /** The key of the open encrypted database; wiped when it is closed. */
    private var currentKey: ByteArray? = null
    /** Bumped by every lock: an unlock or conversion that started before a lock must not open the database. */
    val lockGen = java.util.concurrent.atomic.AtomicLong()
    /** While protection is being turned on or off, a lock request still counts. */
    @Volatile var converting = false
    /** Work outside the screens (built-in foods, reminders) that is using the database right now. */
    private val users = java.util.concurrent.atomic.AtomicInteger()

    /**
     * After an interrupted switch on or off, the database file decides: a plain file means protection
     * is off, an encrypted one means it is on (or its key is gone). Also removes a leftover old copy.
     */
    private fun reconcileProtection() {
        ua.tarilka.security.DbCrypt.recover(this)
        val main = getDatabasePath(AppDatabase.NAME)
        // Unknown (unreadable): change nothing.
        val plain = if (main.exists()) ua.tarilka.security.DbCrypt.isPlain(main) else null
        runCatching {
            when {
                plain == true && prefs.appLock -> {
                    prefs.appLock = false
                    prefs.clearCache()
                    ua.tarilka.security.KeyVault(this).reset()
                }
                plain == false -> {
                    if (!prefs.appLock) prefs.appLock = true
                    val vault = ua.tarilka.security.KeyVault(this)
                    if (!vault.isSetUp()) vault.markInvalidated()
                }
            }
        }
        // Starting locked (the process may have ended with the camera open): no photo stays behind.
        if (prefs.appLock) runCatching { ua.tarilka.ai.PhotoUtil.clear(this) }
    }

    /**
     * Opens the database (plain, or encrypted with [rawKey]); the built-in foods are refreshed after.
     * With a key: if a lock happened since [gen] was taken, it is closed again at once.
     */
    fun openDb(rawKey: ByteArray?, gen: Long = lockGen.get()) {
        val d = AppDatabase.create(this, rawKey)
        // Encrypted: open now, so a wrong key fails here and not later in a screen.
        if (rawKey != null) {
            try {
                d.openHelper.writableDatabase
            } catch (e: Exception) {
                runCatching { d.close() }
                rawKey.fill(0)
                throw e
            }
        }
        val published = synchronized(gate) {
            if (rawKey != null && lockGen.get() != gen) {
                false
            } else {
                currentKey = rawKey
                _db.value = d
                true
            }
        }
        if (!published) {
            d.close()
            rawKey?.fill(0)
            return
        }
        // A camera photo saved while the diary was locked could not be used: it does not stay unencrypted.
        if (rawKey != null) runCatching { ua.tarilka.ai.PhotoUtil.clear(this) }
        scope.launch {
            runCatching { withDb { FoodSeeder.seedIfNeeded(this@TarilkaApp, it, prefs) } }
            // The weekly automatic copy, when it is due (works with protection too: the diary is open now).
            backupIfDue()
        }
    }

    /** The weekly automatic copy, when it is due and the diary is open (at start, after unlocking, on return). */
    fun backupIfDue() {
        // One copy at a time (start and return can both ask).
        if (ua.tarilka.data.AutoBackup.isDue(prefs) && _db.value != null && backingUp.compareAndSet(false, true)) scope.launch {
            try {
                runCatching { withDb { ua.tarilka.data.AutoBackup.run(this@TarilkaApp, it, prefs) } }
            } finally {
                backingUp.set(false)
            }
        }
    }
    private val backingUp = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Opening and locking never interleave. */
    private val gate = Any()
    /** The close in progress, if any: a conversion waits for it, not just for the database to look closed. */
    @Volatile private var closing: kotlinx.coroutines.Job? = null

    /**
     * Runs [block] with the open database, or returns null when it is locked; closing waits for it.
     * Errors are passed on to the caller (a reminder then simply does not show).
     */
    suspend fun <T> withDb(block: suspend (AppDatabase) -> T): T? {
        val d = _db.value ?: return null
        users.incrementAndGet()
        try {
            if (_db.value !== d) return null
            return block(d)
        } finally {
            users.decrementAndGet()
        }
    }

    /**
     * Takes the database away from the screens, waits until nothing uses it, closes it and wipes the key.
     * The returned job ends when it is really closed.
     */
    fun closeDb(): kotlinx.coroutines.Job = synchronized(gate) {
        val d = _db.value ?: return closing?.takeIf { it.isActive } ?: kotlinx.coroutines.CompletableDeferred(Unit)
        _db.value = null
        val key = currentKey
        currentKey = null
        scope.launch {
            // The screens' own work stops when they are cleared; give it time, then wait for the rest.
            kotlinx.coroutines.delay(1500)
            // ...but not for ever: after 30 s it closes anyway (a copy stuck on a slow folder just fails).
            kotlinx.coroutines.withTimeoutOrNull(30_000) { while (users.get() > 0) kotlinx.coroutines.delay(100) }
            runCatching { d.close() }
            key?.fill(0)
        }.also { closing = it }
    }

    /** Locks the protected diary (screen off, a minute in the background, or by hand). */
    fun lock() {
        if (!prefs.appLock && !converting) return
        synchronized(gate) {
            lockGen.incrementAndGet()
            closeDb()
        }
        // A photo taken just before must not stay unencrypted in the cache.
        runCatching { ua.tarilka.ai.PhotoUtil.clear(this) }
    }

    /** Screen off locks at once; coming back after more than a minute away locks too. Kept here, not in a screen. */
    private fun watchForLock() {
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) = lock()
            },
            android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            private var awaySince = 0L
            private var timer: kotlinx.coroutines.Job? = null
            override fun onActivityStarted(activity: android.app.Activity) {
                if (started++ == 0) {
                    timer?.cancel()
                    if (awaySince > 0 && System.currentTimeMillis() - awaySince > AWAY_MS) lock()
                    awaySince = 0
                }
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                if (--started == 0 && !activity.isChangingConfigurations) {
                    awaySince = System.currentTimeMillis()
                    // Also close the diary while the app stays in the background, not only on return.
                    timer = scope.launch { kotlinx.coroutines.delay(AWAY_MS); lock() }
                }
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }
}

/** How long the app may stay in the background before the protected diary closes. */
private const val AWAY_MS = 60_000L
