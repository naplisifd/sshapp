package com.sshapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the SSH connection alive while the app is in the background.
 * Without it Android freezes or kills the process shortly after the user switches apps.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wifiLock: WifiManager.WifiLock? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "SSH sessions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while an SSH session is open"
                setShowBadge(false)
            }
        )
        // Keep Wi-Fi from dropping into power save and silently killing the TCP connection.
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(mode, "sshapp:session").apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            SessionHolder.stop(this)
            return START_NOT_STICKY
        }
        val session = SessionHolder.session
        if (session == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(session.host.label, session.state.value.label()),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        watcher?.cancel()
        watcher = scope.launch {
            snapshotFlow { SessionHolder.session }.collectLatest { s ->
                if (s == null) { stopSelf(); return@collectLatest }
                s.state.collect { st ->
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(s.host.label, st.label()))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(host: String, status: String) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(host)
            .setContentText(status)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .addAction(
                0, "Disconnect",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, SessionService::class.java).setAction(ACTION_DISCONNECT),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    override fun onDestroy() {
        scope.cancel()
        wifiLock?.release()
        super.onDestroy()
    }

    private fun ConnectionState.label() = when (this) {
        ConnectionState.Connecting -> "Connecting…"
        ConnectionState.Connected -> "Connected"
        ConnectionState.Reconnecting -> "Connection lost, reconnecting…"
        ConnectionState.Closed -> "Disconnected"
        is ConnectionState.Failed -> "Connection failed"
    }

    companion object {
        private const val CHANNEL = "session"
        private const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "com.sshapp.DISCONNECT"
    }
}
