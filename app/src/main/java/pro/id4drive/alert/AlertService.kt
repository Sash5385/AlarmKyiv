package pro.id4drive.alert

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AlertService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var neptunClient: NeptunClient? = null
    private var wasKyivActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification(active = false))

        neptunClient = NeptunClient(
            scope = scope,
            onAreasUpdated = { areas ->
                AlertState.updateAreas(areas)
                handleTransition(AlertState.state.value.kyivActive)
            },
            onConnectionStateChanged = { state -> AlertState.updateConnection(state) },
        ).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        neptunClient?.stop()
        AlertSound.stop(this)
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleTransition(kyivActive: Boolean) {
        if (kyivActive == wasKyivActive) return
        wasKyivActive = kyivActive
        if (kyivActive) {
            AlertSound.playAlertLoop(this)
        } else {
            AlertSound.playClearOnce(this)
        }
        updateNotification(kyivActive)
    }

    private fun updateNotification(active: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(active))
    }

    private fun buildNotification(active: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = getString(if (active) R.string.notification_title_active else R.string.notification_title_idle)
        val text = getString(if (active) R.string.notification_text_active else R.string.notification_text_idle)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** startForeground(id, notification, type) існує лише з API 29 — на старіших викликаємо 2-аргументну версію. */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "alert_service_channel"
        const val NOTIFICATION_ID = 1
    }
}
