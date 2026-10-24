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
