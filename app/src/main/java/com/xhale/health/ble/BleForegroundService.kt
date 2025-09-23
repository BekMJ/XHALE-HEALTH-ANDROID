package com.xhale.health.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xhale.health.R

class BleForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "xh_ble_foreground"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(channelId, "BLE Sampling", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("XHale Health")
            .setContentText("Sampling in progress")
            .setSmallIcon(R.drawable.ic_stat_xhale)
            .setOngoing(true)
            .build()
    }
}

