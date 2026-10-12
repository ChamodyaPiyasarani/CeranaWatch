package com.example.ceranawatch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Shows a notification on the phone. */
object Notifier {
    private const val CHANNEL = "hive_alerts"

    /** Only for tests: receives the notifications instead of the phone. */
    var testSink: ((String, String) -> Unit)? = null

    /** From Android 13 the user must allow notifications. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, id: Int, title: String, text: String) {
        try {
            val sink = testSink
            if (sink != null) {
                sink(title, text)
                return
            }
            if (!allowed(context)) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Hive alerts / මී පෙට්ටි අනතුරු ඇඟවීම්", NotificationManager.IMPORTANCE_HIGH)
                )
                Notification.Builder(context, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            builder.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (open != null) {
                builder.setContentIntent(
                    PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                )
            }
            manager.notify(id, builder.build())
        } catch (_: Exception) {
            // a notification problem must never stop the app
        }
    }
}

/**
 * Looks in the cloud for new alerts and shows them on the phone.
 * On the owner's phone: every new alert.
 * On the second contact's phone: alerts that were sent on, and alerts the owner did not answer in time.
 */
object AlertChecker {

    class Result(val alerts: List<AlertItem>, val open: Int, val error: String)

    private const val MAX_AGE_MINUTES = 12 * 60L

    @Synchronized
    fun check(context: Context, store: Store): Result {
        val cloud = Cloud(store)
        if (!cloud.ready()) return Result(emptyList(), 0, "")
        val alerts: List<AlertItem>
        try {
            alerts = cloud.alerts(30)
        } catch (e: Exception) {
            val saved = AlertItem.listFromSaved(store.savedText("alerts"))
            return Result(saved, saved.count { !it.ack }, netErrorText(e, store.sinhala()))
        }
        store.saveText("alerts", AlertItem.listToSaved(alerts))
        store.rememberAlertLabels(alerts)   // real / false answers stay on the phone for the alert checker
        val si = store.sinhala()
        var shown = 0
        for (a in alerts) {
            if (a.ack) continue
            val fresh = !a.hasClock() || a.ageMinutes() <= MAX_AGE_MINUTES
            if (!store.secondContact()) {
                val mark = "a:" + a.id
                if (store.alertWasShown(mark)) continue
                store.markAlertShown(mark)
                if (fresh && shown < 3) {
                    shown++
                    Notifier.show(context, a.id.hashCode(), title(a, si), body(a, si))
                }
            } else {
                val late = a.hasClock() && a.ageMinutes() >= store.escalateMinutes()
                if (!a.escalated && !late) continue
                val mark = "e:" + a.id
                if (store.alertWasShown(mark)) continue
                store.markAlertShown(mark)
                if (!a.escalated) {
                    try {
                        cloud.escalate(a.id, "No reply from the owner for " + store.escalateMinutes() + " minutes")
                    } catch (_: Exception) {
                    }
                }
                if (fresh && shown < 3) {
                    shown++
                    val head = if (si) "අයිතිකාරයා තවම බලලා නැහැ: " else "The owner has not answered: "
                    Notifier.show(context, a.id.hashCode(), head + a.hive, body(a, si))
                }
            }
        }
        if (store.secondContact()) {
            try {
                for (m in cloud.messages(5)) {
                    val mark = "m:" + m.first
                    if (store.alertWasShown(mark)) continue
                    store.markAlertShown(mark)
                    val ageMinutes = (System.currentTimeMillis() / 1000L - m.second) / 60L
                    if (ageMinutes <= MAX_AGE_MINUTES) {
                        Notifier.show(context, m.first.hashCode(), if (si) "CeranaWatch පණිවිඩයක්" else "CeranaWatch message", m.third)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return Result(alerts, alerts.count { !it.ack }, "")
    }

    fun title(a: AlertItem, sinhala: Boolean): String = when (a.type) {
        AlertItem.QUIET -> if (sinhala) a.hive + ": මී මැස්සන්ගේ ශබ්දය ගොඩක් අඩු වෙලා" else a.hive + ": unusually quiet"
        AlertItem.NO_BEES -> if (sinhala) a.hive + ": මී මැසි ශබ්දය ඇහෙන්නේ නැහැ" else a.hive + ": no bee sound"
        AlertItem.HOT -> if (sinhala) a.hive + ": පෙට්ටිය ඇතුළ ගොඩක් රස්නෙයි" else a.hive + ": very hot inside"
        else -> if (sinhala) a.hive + ": මී මැස්සන්ගේ ශබ්දය ගොඩක් වැඩි වෙලා" else a.hive + ": unusually loud"
    }

    fun body(a: AlertItem, sinhala: Boolean): String {
        val now = String.format(java.util.Locale.US, "%.1f", a.lowDb)
        val normal = String.format(java.util.Locale.US, "%.1f", a.normalDb)
        val temp = if (a.tempC != null) String.format(java.util.Locale.US, "%.1f", a.tempC) else "?"
        val time = if (a.hasClock()) dayAndTime(a.t * 1000L, sinhala) else "?"
        return when (a.type) {
            AlertItem.NO_BEES ->
                if (sinhala) "මිනුම් කිහිපයක් එක දිගට මී මැසි ශබ්දය ඇහුණේ නැහැ. පෙට්ටිය බලන්න. ($time)"
                else "No bee sound for several readings in a row. Please look at the hive. ($time)"
            AlertItem.HOT ->
                if (sinhala) "පෙට්ටිය ඇතුළේ $temp °C, මිනුම් දෙකක් එක දිගට. සෙවණ දීලා පෙට්ටිය බලන්න. ($time)"
                else "$temp °C inside the hive, two readings in a row. Give shade and check the hive. ($time)"
            else ->
                if (sinhala) "එවෙලේ $now dB, සුපුරුදු මට්ටම $normal dB. ඇප් එක ඇරලා බලන්න. ($time)"
                else "Then $now dB, normal $normal dB. Open the app to see more. ($time)"
        }
    }
}

/** Android runs this about every 15 minutes, also when the app is closed. */
class AlertJob : JobService() {

    companion object {
        private const val JOB_ID = 4201

        /** Turns the background check on or off. */
        fun schedule(context: Context, on: Boolean) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                if (!on) {
                    scheduler.cancel(JOB_ID)
                    return
                }
                val info = JobInfo.Builder(JOB_ID, ComponentName(context, AlertJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .build()
                scheduler.schedule(info)
            } catch (_: Exception) {
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            try {
                AlertChecker.check(applicationContext, Store.get(applicationContext))
            } catch (_: Exception) {
            }
            try {
                jobFinished(params, false)
            } catch (_: Exception) {
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
