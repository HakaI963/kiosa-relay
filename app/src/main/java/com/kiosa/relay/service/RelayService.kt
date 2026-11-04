package com.kiosa.relay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kiosa.relay.MainActivity
import com.kiosa.relay.R
import com.kiosa.relay.RelayController

/** Foreground service that keeps the local relay server alive. */
class RelayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        RelayController.actuallyStart()
        return START_STICKY
    }

    override fun onDestroy() {
        RelayController.stopRelay(this)
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Kiosa Relay", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val s = RelayController.settings.value
        val ip = RelayController.lanIp() ?: "127.0.0.1"
        val url = "http://$ip:${s.port}/v1"
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Kiosa Relay running")
            .setContentText(url)
            .setOngoing(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val CHANNEL_ID = "kiosa_relay"
        const val NOTIF_ID = 4242
    }
}
