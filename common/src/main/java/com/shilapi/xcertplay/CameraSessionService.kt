package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.camera.CameraSettings
import com.shilapi.xcertplay.host.R
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps owner-enabled mirror capture alive independently of the iPhone connection and settings page. */
class CameraSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!requested.get() && !CameraSettings.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        requested.set(true)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "电子后视镜", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 2, Intent(this, CameraSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("BYD Carplay")
            .setContentText("电子后视镜运行中")
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(2, notification)
        // ensure is idempotent; an active settings draft must never be replaced by saved preferences.
        CameraServices.ensure(applicationContext)
        NavigationWheelServiceRecovery.ensure(applicationContext)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "byd_carplay_cameras"
        private val main = Handler(Looper.getMainLooper())
        private val requested = AtomicBoolean(false)

        fun update(context: Context, enabled: Boolean) {
            if (requested.getAndSet(enabled) == enabled) return
            val app = context.applicationContext
            main.post {
                // A rapid Cancel/OFF supersedes a queued start.
                if (requested.get() != enabled) return@post
                val intent = Intent(app, CameraSessionService::class.java)
                runCatching {
                    if (!enabled) app.stopService(intent)
                    else if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent)
                    else app.startService(intent)
                }.onFailure {
                    if (enabled) requested.compareAndSet(true, false)
                    Log.w("DiPlay-Camera", "camera foreground service unavailable type=${it.javaClass.simpleName}")
                }
            }
        }
    }
}
