package com.geminitts.book

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

object Notifier {
    private const val RUN = "run"
    private const val ALERT = "alert"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(RUN, "Conversion progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ALERT, "Needs attention", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun open(c: Context) = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    fun run(c: Context, text: String, done: Int, total: Int): Notification {
        val stop = PendingIntent.getService(
            c, 1, Intent(c, ConvertService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(c, RUN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Gemini TTS Book")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(c))
            .setProgress(total, done, total == 0)
            .addAction(Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "Stop", stop).build())
            .build()
    }

    fun update(c: Context, done: Int, total: Int) {
        try {
            c.getSystemService(NotificationManager::class.java)
                .notify(1, run(c, "$done / $total parts", done, total))
        } catch (e: Exception) { /* notifications are optional */ }
    }

    fun alert(c: Context, text: String) {
        try {
            val n = Notification.Builder(c, ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Gemini needs attention")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open(c))
                .setAutoCancel(true)
                .build()
            c.getSystemService(NotificationManager::class.java).notify(2, n)
        } catch (e: Exception) { /* optional */ }
    }

    fun finish(c: Context, text: String) {
        try {
            val n = Notification.Builder(c, ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Gemini TTS Book")
                .setContentText(text)
                .setContentIntent(open(c))
                .setAutoCancel(true)
                .build()
            c.getSystemService(NotificationManager::class.java).notify(3, n)
        } catch (e: Exception) { /* optional */ }
    }
}

class ConvertService : Service() {
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            Engine.stop()
            return START_NOT_STICKY
        }
        Notifier.channels(this)
        startForeground(1, Notifier.run(this, "Starting…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val job = Bus.job
        if (job == null || Bus.running) {
            if (!Bus.running) stopSelf()
            return START_NOT_STICKY
        }
        Bus.running = true
        wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gtts:run")
            .apply { acquire(12 * 60 * 60 * 1000L) }

        val app = applicationContext
        Thread {
            try {
                Engine.run(app, job)
            } catch (e: Throwable) {
                Bus.log("error: ${e.javaClass.simpleName} ${e.message}")
                Bus.status = "Error: ${e.message}"
            } finally {
                Bus.running = false
                Bus.job = null
                try { wl?.release() } catch (e: Throwable) { /* ignore */ }
                Notifier.finish(app, Bus.status)
                Bus.ping()
                Handler(Looper.getMainLooper()).post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.start()
        return START_NOT_STICKY
    }
}
