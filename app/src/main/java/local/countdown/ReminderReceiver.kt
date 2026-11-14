package local.countdown

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

internal data class StoredData(val timers: List<Timer>, val categories: List<Category>)

internal fun decode(text: String, backup: Boolean): StoredData {
    val root = JSONObject(text)
    if (backup || root.has("version")) require(root.getInt("version") == 1)
    val categories = (if (backup) root.getJSONArray("categories") else root.optJSONArray("categories") ?: JSONArray()).objects()
        .map { Category(it.getString("name"), it.getInt("rgb")) }
    require(categories.all { it.name.isNotBlank() && it.rgb in 0..0xFFFFFF })
    val names = categories.map { it.name }.toSet()
    require(categories.map { it.name.lowercase() }.toSet().size == categories.size)

    val timers = (if (backup) root.getJSONArray("timers") else root.optJSONArray("timers") ?: JSONArray()).objects()
        .map {
            Timer(it.getString("id"), it.getString("name"), it.getBoolean("up"),
                it.getLong("time"), it.getString("emoji"), it.getString("category"),
                it.optBoolean("reminder", false), TimeUnit.parse(it.optString("unit")))
        }
    require(timers.map { it.id }.toSet().size == timers.size)
    require(timers.all {
        it.id.isNotBlank() && it.name.isNotBlank() && it.time in 0..253402300799999L &&
            (it.category.isEmpty() || it.category in names) && !(it.up && it.reminder)
    })
    return StoredData(timers, categories)
}

private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

internal enum class ReminderType(val label: String, val days: Int, val message: String) {
    AT_END("When a countdown ends", 0, "Your countdown has ended"),
    ONE_DAY("One day before", 1, "Your countdown ends in one day"),
    ONE_WEEK("One week before", 7, "Your countdown ends in one week");

    fun enabled(prefs: android.content.SharedPreferences) = prefs.getBoolean(name, this == AT_END)

    fun trigger(end: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = end
        add(java.util.Calendar.DAY_OF_YEAR, -days)
    }.timeInMillis
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("countdown", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notifications", true)) return
        val raw = prefs.getString("data", "{}")
        val timers = runCatching { decode(raw ?: "{}", backup = false).timers }.getOrNull() ?: return
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            timers.forEach { schedule(context, it) }
            return
        }
        if (intent.action != ACTION_REMIND) return
        val timer = timers.find { it.id == intent.data?.lastPathSegment && !it.up && it.reminder } ?: return
        val type = ReminderType.entries.find { it.name == intent.data?.pathSegments?.firstOrNull() } ?: return
        if (!type.enabled(prefs)) return
        if (timer.time != intent.getLongExtra("time", -1L)) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Countdown reminders",
                NotificationManager.IMPORTANCE_DEFAULT))
            Notification.Builder(context, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).setDefaults(Notification.DEFAULT_ALL)
        }
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        manager.notify(timer.id, type.ordinal + 1, notification.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(timer.name).setContentText(type.message)
            .setContentIntent(openApp).setAutoCancel(true).build())
    }

    companion object {
        private const val CHANNEL = "reminders"
        private const val ACTION_REMIND = "local.countdown.REMIND"

        private fun alarmIntent(context: Context, id: String, type: ReminderType) = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMIND).setData(Uri.Builder().scheme("countdown").authority("timer").appendPath(type.name).appendPath(id).build())

        internal fun schedule(context: Context, timer: Timer) {
            if (timer.up || !timer.reminder || timer.time <= System.currentTimeMillis()) return
            val prefs = context.getSharedPreferences("countdown", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("notifications", true)) return
            ReminderType.entries.filter { it.enabled(prefs) }.forEach { type ->
                val trigger = type.trigger(timer.time)
                if (trigger > System.currentTimeMillis()) scheduleAlarm(context, timer, type, trigger)
            }
        }

        private fun scheduleAlarm(context: Context, timer: Timer, type: ReminderType, trigger: Long) {
            val pending = PendingIntent.getBroadcast(context, 0,
                alarmIntent(context, timer.id, type).putExtra("time", timer.time),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val alarms = context.getSystemService(AlarmManager::class.java)
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                try {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
                    return
                } catch (_: SecurityException) {
                    // Access may be revoked between checking and scheduling.
                }
            }
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }

        internal fun cancel(context: Context, id: String) {
            ReminderType.entries.forEach { type ->
                val pending = PendingIntent.getBroadcast(context, 0, alarmIntent(context, id, type),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                if (pending != null) {
                    context.getSystemService(AlarmManager::class.java).cancel(pending)
                    pending.cancel()
                }
                context.getSystemService(NotificationManager::class.java).cancel(id, type.ordinal + 1)
            }
        }
    }
}
