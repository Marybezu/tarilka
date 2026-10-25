package ua.tarilka.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.tarilka.R
import ua.tarilka.TarilkaApp
import ua.tarilka.data.FluidRules
import ua.tarilka.data.Nutrition
import ua.tarilka.ui.MainActivity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Optional local reminders, all off by default. They are made on the phone (no internet)
 * and only fire when useful: a meal reminder stays quiet if that meal is already logged,
 * a water reminder if you are on track. The lock screen shows only "Tarilka: reminder".
 */
object Reminders {
    const val MEAL_0 = 0 // breakfast, lunch, dinner = 0, 1, 2
    const val WATER = 10
    const val SLEEP = 20
    const val WEEKLY = 30
    private val WATER_TIMES = listOf(LocalTime.of(12, 0), LocalTime.of(16, 0), LocalTime.of(19, 0))
    private val WEEKLY_TIME = LocalTime.of(19, 0)
    private const val CHANNEL = "reminders"
    private const val EXTRA_KIND = "kind"

    /** Cancels everything and sets the next alarm for each reminder that is on. Call after any change. */
    fun schedule(context: Context) {
        val prefs = (context.applicationContext as TarilkaApp).prefs
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = LocalDateTime.now()
        fun set(kind: Int, at: LocalDateTime?) {
            val pi = pending(context, kind)
            am.cancel(pi)
            if (at == null) return
            val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            // Inexact on purpose: no special permission, and a few minutes either way do not matter.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
        fun next(t: LocalTime) = now.toLocalDate().atTime(t).let { if (it.isAfter(now)) it else it.plusDays(1) }
        for (meal in 0..2) set(MEAL_0 + meal, prefs.mealReminder(meal)?.let(::next))
        set(WATER, if (prefs.waterReminder) WATER_TIMES.map(::next).minOrNull() else null)
        set(SLEEP, prefs.sleepReminder?.let(::next))
        set(WEEKLY, if (prefs.weeklyReminder) {
            var d = next(WEEKLY_TIME)
            while (d.dayOfWeek != DayOfWeek.SUNDAY) d = d.plusDays(1)
            d
        } else null)
    }

    private fun pending(context: Context, kind: Int): PendingIntent = PendingIntent.getBroadcast(
        context, kind,
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_KIND, kind),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Decides whether the reminder is still useful, and shows it. */
    internal suspend fun fire(context: Context, kind: Int) {
        val app = context.applicationContext as TarilkaApp
        val today = LocalDate.now().toEpochDay()
        val text: String = when (kind) {
            in 0..2 -> {
                if (app.db.diary().countMeal(today, kind) > 0) return
                context.getString(R.string.remind_meal, context.resources.getStringArray(R.array.remind_meals)[kind])
            }
            WATER -> {
                val drunk = app.db.fluids().mlOnce(today) +
                    app.db.fluids().drinksFromDiary(today, FluidRules.ALCOHOL, FluidRules.MILK_DRINKS).first()
                val target = Nutrition.targets(app.prefs.profile.value, app.db.weights().allOnce().lastOrNull()?.kg).waterMl
                // Expected by now, spread over 8:00–22:00; remind only when clearly behind.
                val hour = LocalTime.now().hour + LocalTime.now().minute / 60.0
                val expected = target * ((hour - 8) / 14).coerceIn(0.0, 1.0)
                if (drunk >= expected * 0.7) return
                context.getString(R.string.remind_water, "%.1f".format(drunk / 1000), "%.1f".format(target / 1000))
            }
            SLEEP -> context.getString(R.string.remind_sleep)
            WEEKLY -> context.getString(R.string.remind_weekly)
            else -> return
        }
        show(context, kind, text)
    }

    private fun show(context: Context, kind: Int, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.reminders_title), NotificationManager.IMPORTANCE_DEFAULT),
        )
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 100 + kind,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // What the lock screen shows: nothing about food or drinks.
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.remind_public))
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        runCatching { nm.notify(kind, n) }
    }
}

/** Fires a reminder, then sets the next one. Also restores alarms after a restart or an update. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TarilkaApp
        val kind = intent.getIntExtra("kind", -1)
        val done = goAsync()
        app.scope.launch {
            try {
                if (kind >= 0) runCatching { Reminders.fire(context, kind) }
                Reminders.schedule(context)
            } finally {
                done.finish()
            }
        }
    }
}
