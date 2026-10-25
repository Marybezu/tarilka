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
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tarilka", Context.MODE_PRIVATE)

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

    private val _bowelEnabled = MutableStateFlow(sp.getBoolean("bowelEnabled", false))
    val bowelEnabled: StateFlow<Boolean> = _bowelEnabled.asStateFlow()
    fun setBowelEnabled(on: Boolean) { sp.edit().putBoolean("bowelEnabled", on).apply(); _bowelEnabled.value = on }

    private val _workoutsEnabled = MutableStateFlow(sp.getBoolean("workoutsEnabled", false))
    val workoutsEnabled: StateFlow<Boolean> = _workoutsEnabled.asStateFlow()
    fun setWorkoutsEnabled(on: Boolean) { sp.edit().putBoolean("workoutsEnabled", on).apply(); _workoutsEnabled.value = on }

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
        // Keep a little over a year.
        o.keys().asSequence().toList().filter { it.toLong() < day - 400 }.forEach { o.remove(it) }
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
        _missing.value = trimmed
        sp.edit().putString("missing", org.json.JSONObject(trimmed).toString()).apply()
    }

    fun clearMissing() {
        _missing.value = emptyMap()
        sp.edit().remove("missing").apply()
    }

    private fun loadMissing(): Map<String, Int> = runCatching {
        val o = org.json.JSONObject(sp.getString("missing", null) ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getInt(it) }
    }.getOrDefault(emptyMap())

    /** Forget the profile and the not-found list; app settings stay. */
    fun clearPersonal() {
        sp.edit().apply { (PROFILE_KEYS + STEP_KEYS).forEach { remove(it) } }.remove("missing").apply()
        _profile.value = Profile()
        _missing.value = emptyMap()
    }
    private val STEP_KEYS = listOf("stepLog", "stepDay", "stepBase", "stepLast")
    private val PROFILE_KEYS = listOf("sex", "age", "height", "weight", "activity", "goal", "customKcal", "profileSet")

    private val _profile = MutableStateFlow(loadProfile())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    fun saveProfile(p: Profile) {
        sp.edit()
            .putString("sex", p.sex.name).putInt("age", p.age).putInt("height", p.heightCm)
            .putFloat("weight", p.weightKg.toFloat()).putInt("activity", p.activity)
            .putString("goal", p.goal.name).putInt("customKcal", p.customKcal ?: -1)
            .putBoolean("profileSet", true)
            .apply()
        _profile.value = p.copy(isSet = true)
    }

    private fun loadProfile(): Profile {
        if (!sp.contains("profileSet")) return Profile()
        return Profile(
            sex = runCatching { Sex.valueOf(sp.getString("sex", null)!!) }.getOrDefault(Sex.FEMALE),
            age = sp.getInt("age", 30),
            heightCm = sp.getInt("height", 165),
            weightKg = sp.getFloat("weight", 65f).toDouble(),
            activity = sp.getInt("activity", 1),
            goal = runCatching { Goal.valueOf(sp.getString("goal", null)!!) }.getOrDefault(Goal.MAINTAIN),
            customKcal = sp.getInt("customKcal", -1).takeIf { it > 0 },
            isSet = true,
        )
    }
}
