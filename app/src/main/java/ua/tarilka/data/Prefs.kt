package ua.tarilka.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Sex { FEMALE, MALE }
enum class Goal { LOSE, MAINTAIN, GAIN }

data class Profile(
    val sex: Sex = Sex.FEMALE,
    val age: Int = 30,
    val heightCm: Int = 165,
    val weightKg: Double = 65.0,
    /** 0 = sedentary … 4 = very active */
    val activity: Int = 1,
    val goal: Goal = Goal.MAINTAIN,
    /** User can type their own calorie target instead of the calculated one. */
    val customKcal: Int? = null,
    val isSet: Boolean = false,
    /** Pregnant or breastfeeding: no weight-loss target. */
    val pregnant: Boolean = false,
)

/** Values that are personal health data: encrypted with the diary while app protection is on (see [Prefs.sealed]). */
private val PERSONAL_KEYS = listOf("sex", "age", "height", "weight", "activity", "goal", "customKcal", "profileSet", "pregnant", "cycleTypical", "missing", "under18")

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tarilka", Context.MODE_PRIVATE).also { sp ->
        // From 0.1.81: the expected cycle date stays only for cycle reminders; Health Connect writes are remembered.
        if (sp.getInt("cycleRemindDays", -1) <= 0 && !sp.getBoolean("cycleLateRemind", false)) sp.edit().remove("cycleNext").apply()
        if (sp.getBoolean("healthSync", false) && !sp.contains("hcWrote")) sp.edit().putBoolean("hcWrote", true).apply()
    }

    /**
     * Personal values ([PERSONAL_KEYS]: profile incl. pregnancy, usual cycle length, the not-found list) live in
     * [sealed] while app protection is on, encrypted with the diary's key, and only in the plain settings when it is off.
     */
    val sealed = ua.tarilka.security.Sealed(context)

    private fun personal(key: String): Any? =
        if (appLock) sealed.snapshot()?.opt(key)?.takeIf { it != org.json.JSONObject.NULL } else sp.all[key]

    /** False when protection is on and the diary is locked: nothing was written. */
    private fun putPersonal(values: Map<String, Any?>): Boolean {
        if (appLock) {
            if (!sealed.isOpen) return false
            sealed.write { d -> values.forEach { (k, v) -> if (v == null) d.remove(k) else d.put(k, v) } }
        } else {
            sp.edit().apply {
                values.forEach { (k, v) ->
                    when (v) {
                        null -> remove(k)
                        is String -> putString(k, v)
                        is Int -> putInt(k, v)
                        is Double -> putFloat(k, v.toFloat())
                        is Boolean -> putBoolean(k, v)
                    }
                }
            }.apply()
        }
        return true
    }

    /**
     * The diary opened with protection on: personal values move from the plain settings into [sealed]
     * (first time, or left over) and are read again.
     */
    fun onUnlocked(rawKey: ByteArray) {
        sealed.open(rawKey)
        if (appLock) {
            val plain = sp.all.filterKeys { it in PERSONAL_KEYS }
            if (plain.isNotEmpty()) {
                // Existing users keep "intro shown", which was inferred from the profile.
                if (!sp.contains("introShown")) introShown = true
                sealed.write { d -> plain.forEach { (k, v) -> d.put(k, if (v is Float) v.toDouble() else v) } }
                sp.edit().apply { plain.keys.forEach { remove(it) } }.commit()
            }
        }
        reloadPersonal()
    }

    /** The diary locked: personal values are closed again and leave memory. */
    fun onLocked() {
        sealed.close()
        reloadPersonal()
    }

    /**
     * Turning protection off, step 1 (before the database is decrypted): personal values are copied to the plain
     * settings with the diary key, so an interruption can never lose them. Left-over copies are moved back
     * into [sealed] on the next unlock if protection stays on.
     */
    fun copyPersonalToPlain(rawKey: ByteArray) {
        val d = sealed.readWith(rawKey) ?: sealed.snapshot() ?: return
        sp.edit().apply {
            d.keys().forEach { k ->
                when (val v = d.get(k)) {
                    is String -> putString(k, v)
                    is Boolean -> putBoolean(k, v)
                    is Number -> if (k == "weight") putFloat(k, v.toFloat()) else putInt(k, v.toInt())
                }
            }
        }.commit()
    }

    /** Turning protection off, step 2 (the database is plain now): the encrypted copy goes. */
    fun dropSealed() {
        sealed.wipe()
        reloadPersonal()
    }

    /** Reads the personal values again (after the protection state was settled at start). */
    fun reloadPersonal() {
        _profile.value = loadProfile()
        _missing.value = loadMissing()
        _cycleTypical.value = (personal("cycleTypical") as? Number)?.toInt() ?: -1
    }

    private val _noScreenshots = MutableStateFlow(sp.getBoolean("noScreenshots", false))
    /** No screenshots or screen recording of the app (also hides it in recent apps and screen sharing). */
    val noScreenshots: StateFlow<Boolean> = _noScreenshots.asStateFlow()
    fun setNoScreenshots(on: Boolean) { sp.edit().putBoolean("noScreenshots", on).apply(); _noScreenshots.value = on }

    /** The three-screen introduction was shown (set for existing users too, see below). */
    private val _introShown = MutableStateFlow(sp.getBoolean("introShown", sp.contains("profileSet")))
    /** Watched by the screen, so the age question comes back at once after "Delete all". */
    val introShownFlow: StateFlow<Boolean> = _introShown.asStateFlow()
    var introShown: Boolean
        get() = _introShown.value
        set(v) { sp.edit().putBoolean("introShown", v).apply(); _introShown.value = v }

    /** App protection: the diary is encrypted and opens only after fingerprint or the phone PIN. */
    var appLock: Boolean
        get() = sp.getBoolean("appLock", false)
        set(v) = sp.edit().putBoolean("appLock", v).commit().let { }

    /**
     * What reminders need while the diary is locked: which meals were logged today, today's fluids and the
     * fluid target. Only these, never what was eaten. Updated while the app is open.
     */
    fun cacheToday(day: Long, meals: Set<Int>, fluidMl: Double, waterTargetMl: Double) {
        // Rounded to half a litre, so the target does not reveal the weight.
        val target = (Math.round(waterTargetMl / 500) * 500).toFloat()
        sp.edit().putLong("cacheDay", day).putString("cacheMeals", meals.joinToString(","))
            .putFloat("cacheFluid", fluidMl.toFloat()).putFloat("cacheWaterTarget", target).apply()
    }
    fun clearCache() {
        sp.edit().remove("cacheDay").remove("cacheMeals").remove("cacheFluid").remove("cacheWaterTarget").apply()
    }
    fun cachedMealLogged(day: Long, meal: Int): Boolean =
        sp.getLong("cacheDay", -1) == day && sp.getString("cacheMeals", "")!!.split(',').contains(meal.toString())
    fun cachedFluid(day: Long): Double = if (sp.getLong("cacheDay", -1) == day) sp.getFloat("cacheFluid", 0f).toDouble() else 0.0
    fun cachedWaterTarget(): Double? = sp.getFloat("cacheWaterTarget", -1f).takeIf { it > 0 }?.toDouble()

    var seedVersion: Int
        get() = sp.getInt("seedVersion", 0)
        set(v) = sp.edit().putInt("seedVersion", v).apply()

    /** Show steps on the Today screen (Health Connect or the phone's step sensor). */
    var stepsEnabled: Boolean
        get() = sp.getBoolean("stepsEnabled", false)
        set(v) = sp.edit().putBoolean("stepsEnabled", v).apply()

    /** Write meals, drinks and weight to Health Connect. */
    var healthSync: Boolean
        get() = sp.getBoolean("healthSync", false)
        set(v) = sp.edit().putBoolean("healthSync", v).apply()

    /** Step sensor counts since boot; remember the reading at the start of each day. */
    var stepDay: Long
        get() = sp.getLong("stepDay", -1)
        set(v) = sp.edit().putLong("stepDay", v).apply()
    var stepBase: Long
        get() = sp.getLong("stepBase", 0)
        set(v) = sp.edit().putLong("stepBase", v).apply()
    var stepLast: Long
        get() = sp.getLong("stepLast", -1)
        set(v) = sp.edit().putLong("stepLast", v).apply()

    private val _sleepEnabled = MutableStateFlow(sp.getBoolean("sleepEnabled", false))
    /** Show the sleep row (typed in, or read from Health Connect). */
    val sleepEnabled: StateFlow<Boolean> = _sleepEnabled.asStateFlow()
    fun setSleepEnabled(on: Boolean) { sp.edit().putBoolean("sleepEnabled", on).apply(); _sleepEnabled.value = on }

    private val _mealTimes = MutableStateFlow(sp.getBoolean("mealTimes", true))
    /** Track the time of meals (late eating badge, times for the AI). On by default. */
    val mealTimes: StateFlow<Boolean> = _mealTimes.asStateFlow()
    fun setMealTimes(on: Boolean) { sp.edit().putBoolean("mealTimes", on).apply(); _mealTimes.value = on }

    private val _bowelEnabled = MutableStateFlow(sp.getBoolean("bowelEnabled", false))
    val bowelEnabled: StateFlow<Boolean> = _bowelEnabled.asStateFlow()
    fun setBowelEnabled(on: Boolean) { sp.edit().putBoolean("bowelEnabled", on).apply(); _bowelEnabled.value = on }

    private val _workoutsEnabled = MutableStateFlow(sp.getBoolean("workoutsEnabled", false))
    val workoutsEnabled: StateFlow<Boolean> = _workoutsEnabled.asStateFlow()
    fun setWorkoutsEnabled(on: Boolean) {
        sp.edit().putBoolean("workoutsEnabled", on).apply { if (on) putLong("workoutsSince", java.time.LocalDate.now().toEpochDay()) }.apply()
        _workoutsEnabled.value = on
    }

    /** The day workout tracking was switched on (for those who had it on before: the first day this is read). */
    val workoutsSince: Long
        get() {
            if (!sp.contains("workoutsSince")) sp.edit().putLong("workoutsSince", java.time.LocalDate.now().toEpochDay()).apply()
            return sp.getLong("workoutsSince", 0)
        }

    /** The last day the "no workouts this week" note was shown: at most once a week. */
    var workoutNudgeDay: Long
        get() = sp.getLong("workoutNudgeDay", -100)
        set(v) = sp.edit().putLong("workoutNudgeDay", v).apply()

    private val _cycleEnabled = MutableStateFlow(sp.getBoolean("cycleEnabled", false))
    val cycleEnabled: StateFlow<Boolean> = _cycleEnabled.asStateFlow()
    fun setCycleEnabled(on: Boolean) { sp.edit().putBoolean("cycleEnabled", on).apply(); _cycleEnabled.value = on }

    private val _cycleTypical = MutableStateFlow((personal("cycleTypical") as? Number)?.toInt() ?: -1)
    /** The usual cycle length the person gave at setup (days), or -1; used until 2 starts are recorded. */
    val cycleTypical: StateFlow<Int> = _cycleTypical.asStateFlow()
    fun setCycleTypical(v: Int) { if (putPersonal(mapOf("cycleTypical" to v))) _cycleTypical.value = v }

    /** Remind this many days before the expected start (1–3), or -1 = off. */
    var cycleRemindDays: Int
        get() = sp.getInt("cycleRemindDays", -1)
        set(v) = sp.edit().putInt("cycleRemindDays", v).apply()
    /** Ask "did it start?" when the expected day passed by 2 days. */
    var cycleLateRemind: Boolean
        get() = sp.getBoolean("cycleLateRemind", false)
        set(v) = sp.edit().putBoolean("cycleLateRemind", v).apply()
    /** Expected next start (epoch day) for the reminders, or -1; kept up to date by the app. */
    var cycleNext: Long
        get() = sp.getLong("cycleNext", -1)
        // Kept only while a cycle reminder needs it (reminders run while the diary is locked).
        set(v) = sp.edit().apply { if (v > 0 && (cycleRemindDays > 0 || cycleLateRemind)) putLong("cycleNext", v) else remove("cycleNext") }.apply()

    /** When the "update your activity level?" hint was last dismissed (epoch day). */
    var activityHintDismissed: Long
        get() = sp.getLong("activityHintDismissed", 0)
        set(v) = sp.edit().putLong("activityHintDismissed", v).apply()

    private val _reasonsEnabled = MutableStateFlow(sp.getBoolean("reasonsEnabled", false))
    /** Ask "why do I eat?" after adding food. */
    val reasonsEnabled: StateFlow<Boolean> = _reasonsEnabled.asStateFlow()
    fun setReasonsEnabled(on: Boolean) { sp.edit().putBoolean("reasonsEnabled", on).apply(); _reasonsEnabled.value = on }

    private val _stepsGoal = MutableStateFlow(sp.getInt("stepsGoal", 8_000))
    /**
     * Daily steps goal. 8 000 by default: large studies see the health benefit level off around
     * 6 000–8 000 steps (older adults) and 8 000–10 000 (younger); 10 000 comes from a 1960s advert.
     */
    val stepsGoal: StateFlow<Int> = _stepsGoal.asStateFlow()
    fun setStepsGoal(v: Int) { sp.edit().putInt("stepsGoal", v).apply(); _stepsGoal.value = v }

    /** Daily totals from the phone's step sensor (Health Connect keeps its own history). */
    fun logSteps(day: Long, steps: Long) {
        val o = runCatching { org.json.JSONObject(sp.getString("stepLog", "{}")!!) }.getOrDefault(org.json.JSONObject())
        o.put(day.toString(), steps)
        // Kept for all years (about 7 KB a year), so an export has every day.
        sp.edit().putString("stepLog", o.toString()).apply()
    }

    /** Adds imported days in one write; a day already logged keeps the larger number. */
    fun mergeSteps(days: Map<Long, Long>) {
        val o = runCatching { org.json.JSONObject(sp.getString("stepLog", "{}")!!) }.getOrDefault(org.json.JSONObject())
        days.forEach { (d, n) -> o.put(d.toString(), maxOf(o.optLong(d.toString(), 0L), n)) }
        sp.edit().putString("stepLog", o.toString()).apply()
    }

    fun loggedSteps(): Map<Long, Long> = runCatching {
        val o = org.json.JSONObject(sp.getString("stepLog", "{}")!!)
        o.keys().asSequence().associate { it.toLong() to o.getLong(it) }
    }.getOrDefault(emptyMap())

    // ---- reminders (all off by default); a time is stored as minutes after midnight, -1 = off ----

    private fun time(key: String): java.time.LocalTime? =
        sp.getInt(key, -1).takeIf { it >= 0 }?.let { java.time.LocalTime.of(it / 60, it % 60) }
    private fun setTime(key: String, t: java.time.LocalTime?) =
        sp.edit().putInt(key, t?.let { it.hour * 60 + it.minute } ?: -1).apply()

    fun mealReminder(meal: Int) = time("remindMeal$meal")
    fun setMealReminder(meal: Int, t: java.time.LocalTime?) = setTime("remindMeal$meal", t)
    var sleepReminder: java.time.LocalTime?
        get() = time("remindSleep")
        set(v) = setTime("remindSleep", v)
    var waterReminder: Boolean
        get() = sp.getBoolean("remindWater", false)
        set(v) = sp.edit().putBoolean("remindWater", v).apply()
    var weeklyReminder: Boolean
        get() = sp.getBoolean("remindWeekly", false)
        set(v) = sp.edit().putBoolean("remindWeekly", v).apply()

    /** Plate size for photos: 0 small, 1 dinner, 2 large, 3 bowl. */
    var plateSize: Int
        get() = sp.getInt("plateSize", 1)
        set(v) = sp.edit().putInt("plateSize", v).apply()

    /**
     * How the person corrects the AI's grams: final ÷ suggested, last 30 foods.
     * The median becomes a personal factor for the next photos.
     */
    fun addGramsRatio(r: Double) {
        val list = (gramsRatios() + r.coerceIn(0.2, 3.0)).takeLast(30)
        sp.edit().putString("gramsRatios", list.joinToString(",")).apply()
    }
    fun gramsRatios(): List<Double> = sp.getString("gramsRatios", "")!!.split(',').mapNotNull { it.toDoubleOrNull() }

    /** 1.0 until there are 5 corrections; never below ×0.5 or above ×1.5. */
    fun gramsFactor(): Double {
        val r = gramsRatios().sorted()
        if (r.size < 5) return 1.0
        return r[r.size / 2].coerceIn(0.5, 1.5)
    }

    /** 0 = follow system, 1 = light, 2 = dark */
    var themeMode: Int
        get() = sp.getInt("themeMode", 0)
        set(v) = sp.edit().putInt("themeMode", v).apply()

    private val _dynamicColor = MutableStateFlow(sp.getBoolean("dynamicColor", false))
    /** Material You: colors from the wallpaper. */
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()
    fun setDynamicColor(on: Boolean) {
        sp.edit().putBoolean("dynamicColor", on).apply()
        _dynamicColor.value = on
    }

    // ---- searches that found nothing (kept only on this phone) ----

    private val _missing = MutableStateFlow(loadMissing())
    /** Search text → how many times it found nothing. */
    val missing: StateFlow<Map<String, Int>> = _missing.asStateFlow()

    fun recordMissing(query: String) {
        val q = query.trim().lowercase()
        if (q.length < 3) return
        val m = _missing.value.toMutableMap()
        // "сирн" then "сирники": keep only the longer, finished word.
        m.keys.filter { q.startsWith(it) && it != q }.forEach { m.remove(it) }
        if (m.keys.any { it.startsWith(q) && it != q }) return
        m[q] = (m[q] ?: 0) + 1
        val trimmed = m.entries.sortedByDescending { it.value }.take(50).associate { it.key to it.value }
        if (putPersonal(mapOf("missing" to org.json.JSONObject(trimmed).toString()))) _missing.value = trimmed
    }

    fun clearMissing() {
        if (putPersonal(mapOf("missing" to null))) _missing.value = emptyMap()
    }

    private fun loadMissing(): Map<String, Int> = runCatching {
        val o = org.json.JSONObject(personal("missing") as? String ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getInt(it) }
    }.getOrDefault(emptyMap())

    /** Turning protection off failed before the switch: the plain copies go again, the encrypted ones stay. */
    fun dropPlainPersonal() {
        sp.edit().apply { PERSONAL_KEYS.forEach { remove(it) } }.commit()
    }

    /** Forget the profile and the not-found list; app settings stay. */
    fun clearPersonal() {
        // introShown goes too, so the "are you 18?" question comes again before anything is filled in.
        sp.edit().apply { (PERSONAL_KEYS + STEP_KEYS).forEach { remove(it) } }.remove("gramsRatios").apply()
        introShown = false
        sealed.write { d -> PERSONAL_KEYS.forEach { d.remove(it) } }
        clearCache()
        _profile.value = Profile()
        _missing.value = emptyMap()
        _cycleTypical.value = -1
    }

    /** Cycle settings that go with the cycle records (also cleared with the diary). */
    fun clearCycle() {
        sp.edit().remove("cycleNext").apply()
        if (putPersonal(mapOf("cycleTypical" to null))) _cycleTypical.value = -1
    }
    private val STEP_KEYS = listOf("stepLog", "stepDay", "stepBase", "stepLast", "cycleNext")

    private val _profile = MutableStateFlow(loadProfile())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    /** Answer at the start, before the profile is filled in: under 18 means no calories from the first screen. */
    fun setUnder18(under: Boolean): Boolean {
        if (!putPersonal(mapOf("under18" to under))) return false
        _profile.value = loadProfile()
        return true
    }

    /** [strict]: fail loudly when nothing could be written (restore); from the screens it simply does nothing. */
    fun saveProfile(p: Profile, strict: Boolean = false) {
        putPersonal(
            mapOf(
                "sex" to p.sex.name, "age" to p.age, "height" to p.heightCm, "weight" to p.weightKg,
                "activity" to p.activity, "goal" to p.goal.name, "customKcal" to (p.customKcal ?: -1),
                "profileSet" to true, "pregnant" to p.pregnant,
            ),
        ).let { ok -> if (!ok) { if (strict) error("the diary is locked") else return } }
        _profile.value = p.copy(isSet = true)
    }

    private fun loadProfile(): Profile {
        // Not filled in yet: a person who said at the start that they are under 18 is treated as 16 until then.
        if (personal("profileSet") != true) return if (personal("under18") == true) Profile(age = 16) else Profile()
        fun int(k: String, d: Int) = (personal(k) as? Number)?.toInt() ?: d
        return Profile(
            sex = runCatching { Sex.valueOf(personal("sex") as String) }.getOrDefault(Sex.FEMALE),
            age = int("age", 30),
            heightCm = int("height", 165),
            weightKg = (personal("weight") as? Number)?.toDouble() ?: 65.0,
            activity = int("activity", 1),
            goal = runCatching { Goal.valueOf(personal("goal") as String) }.getOrDefault(Goal.MAINTAIN),
            customKcal = int("customKcal", -1).takeIf { it > 0 },
            isSet = true,
            pregnant = personal("pregnant") == true,
        )
    }
}
