package ua.tarilka.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ua.tarilka.data.Prefs
import java.time.LocalDate
import kotlin.coroutines.resume

/**
 * Fallback when nothing writes steps to Health Connect: the phone's own step counter.
 * The sensor counts steps since the last reboot, so the app remembers the reading
 * at the start of each day. Steps taken before the first reading of a day are
 * counted towards the previous day.
 */
class StepSensor(private val context: Context, private val prefs: Prefs) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val present: Boolean get() = sensor != null

    val permitted: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun readCounter(): Long? {
        val s = sensor ?: return null
        if (!permitted) return null
        return withTimeoutOrNull(3000) {
            suspendCancellableCoroutine { cont ->
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values[0].toLong())
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        }
    }

    /** Steps today, or null when the sensor can't be read. */
    suspend fun today(): Long? {
        val now = readCounter() ?: return null
        val today = LocalDate.now().toEpochDay()
        val last = prefs.stepLast
        if (prefs.stepDay != today) {
            // New day: start from the last reading we saw (or now, on the very first run).
            prefs.stepBase = if (last in 0..now) last else now
            prefs.stepDay = today
        } else if (last >= 0 && now < last) {
            // Phone rebooted and the counter restarted from zero: keep what was counted before.
            prefs.stepBase = -(last - prefs.stepBase)
        }
        prefs.stepLast = now
        return (now - prefs.stepBase).coerceAtLeast(0)
    }
}
