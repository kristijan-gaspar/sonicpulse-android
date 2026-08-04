package hr.sonicpulse.app.service.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import hr.sonicpulse.app.R
import hr.sonicpulse.app.domain.model.SessionDetection
import javax.inject.Inject

/**
 * A dismissible alert for one local detection, on its own channel — never the mandatory
 * foreground-service channel/id (`monitoring_channel` / [MONITORING_NOTIFICATION_ID] in
 * [hr.sonicpulse.app.service.MonitoringService]), and never posted at all without the runtime
 * POST_NOTIFICATIONS grant (checked here, not assumed from the manifest declaration — required on
 * API 33+, always granted below that). A denied/missing permission or a rejected [notify] call is
 * swallowed, never thrown, so it can never take monitoring down with it.
 */
class AndroidDetectionNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) : DetectionNotifier {

    override fun notify(detection: SessionDetection) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannelIfNeeded()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.detection_alert_title))
            .setContentText(context.getString(R.string.detection_alert_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationIdFor(detection), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Detection notification rejected by the system")
        }
    }

    private fun createChannelIfNeeded() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.detection_alert_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    /** Never [hr.sonicpulse.app.service.MonitoringService]'s own foreground-service notification
     * id (1) — offset well clear of it so a hash collision with that fixed id is impossible, not
     * just unlikely. */
    private fun notificationIdFor(detection: SessionDetection): Int =
        (detection.localEventId.hashCode() and 0x7FFFFFFF) or ID_OFFSET

    private companion object {
        const val TAG = "DetectionNotifier"
        const val CHANNEL_ID = "detection_alert_channel"
        const val ID_OFFSET = 0x1000
    }
}
