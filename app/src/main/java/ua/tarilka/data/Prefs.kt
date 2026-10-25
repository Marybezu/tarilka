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
