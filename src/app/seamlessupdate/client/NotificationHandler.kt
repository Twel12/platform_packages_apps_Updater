package app.seamlessupdate.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.NotificationManager.IMPORTANCE_MIN
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.text.Html

class NotificationHandler(private val service: Service) {

    private enum class Phase {
        CHECK, DOWNLOAD, VERIFY, INSTALL
    }

    private val notificationManager = service.getSystemService(NotificationManager::class.java)
    private var phase = Phase.CHECK

    init {
        val rebootChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID_REBOOT,
            service.getString(R.string.notification_channel_reboot),
            IMPORTANCE_HIGH
        ).apply {
            enableLights(true)
            enableVibration(true)
        }

        notificationManager.createNotificationChannels(listOf(
            NotificationChannel(NOTIFICATION_CHANNEL_ID_PROGRESS,
                service.getString(R.string.notification_channel_progress), IMPORTANCE_LOW),
            rebootChannel,
            NotificationChannel(NOTIFICATION_CHANNEL_ID_FAILURE,
                service.getString(R.string.notification_channel_failure), IMPORTANCE_LOW),
            NotificationChannel(NOTIFICATION_CHANNEL_ID_UPDATED,
                service.getString(R.string.notification_channel_updated), IMPORTANCE_MIN)
        ))
    }

    private fun buildProgressNotification(resId: Int, progress: Long, max: Long): Notification {
        return Notification.Builder(service, NOTIFICATION_CHANNEL_ID_PROGRESS)
            .setContentIntent(getPendingSettingsIntent())
            .setContentTitle(service.getString(resId))
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.system_update_fill0_wght400_grad0_opsz48)
            .apply {
                if (max <= 0) {
                    setProgress(0, 0, true)
                } else {
                    val fraction = progress.toDouble() / max.toDouble()
                    setProgress(100, (fraction * 100).toInt(), false)
                }
            }
            .build()
    }

    fun start() {
        phase = Phase.CHECK
        notificationManager.cancelAll()
        service.startForeground(NOTIFICATION_ID_PROGRESS,
            Notification.Builder(service, NOTIFICATION_CHANNEL_ID_PROGRESS)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(R.string.notification_check_title))
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.system_update_fill0_wght400_grad0_opsz48)
                .build()
        )
    }

    fun showUpdatedNotification(channel: String) {
        val channelText = when (channel) {
            "stable" -> service.getString(R.string.channel_stable)
            "beta"   -> service.getString(R.string.channel_beta)
            "alpha"  -> service.getString(R.string.channel_alpha)
            else     -> channel
        }
        notificationManager.notify(NOTIFICATION_ID_UPDATED,
            Notification.Builder(service, NOTIFICATION_CHANNEL_ID_UPDATED)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(R.string.notification_updated_title))
                .setContentText(service.getString(R.string.notification_updated_text, channelText))
                .setShowWhen(true)
                .setSmallIcon(R.drawable.security_update_good_fill0_wght400_grad0_opsz48)
                .build()
        )
    }

    fun showDownloadNotification(progress: Long, max: Long) {
        phase = Phase.DOWNLOAD
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(R.string.notification_download_title, progress, max))
    }

    fun showVerifyNotification(progress: Int) {
        phase = Phase.VERIFY
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(R.string.notification_verify_title, progress.toLong(), 100))
    }

    fun showInstallNotification(progress: Int) {
        phase = Phase.INSTALL
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(R.string.notification_install_title, progress.toLong(), 100))
    }

    fun showValidateNotification(progress: Int) {
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(R.string.notification_validate_title, progress.toLong(), 100))
    }

    fun showFinalizeNotification(progress: Int) {
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(R.string.notification_finalize_title, progress.toLong(), 100))
    }

    fun cancelProgressNotification() {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    fun showRebootNotification() {
        val rebootIntent = PendingIntent.getBroadcast(
            service, PENDING_REBOOT_ID,
            Intent(service, RebootReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val rebootAction = Notification.Action.Builder(
            Icon.createWithResource(service.application, R.drawable.restart_alt_fill0_wght400_grad0_opsz48),
            service.getString(R.string.notification_reboot_action),
            rebootIntent
        ).build()

        notificationManager.notify(NOTIFICATION_ID_REBOOT,
            Notification.Builder(service, NOTIFICATION_CHANNEL_ID_REBOOT)
                .addAction(rebootAction)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(R.string.notification_reboot_title))
                .setContentText(service.getString(R.string.notification_reboot_text))
                .setOngoing(true)
                .setShowWhen(true)
                .setSmallIcon(R.drawable.system_update_fill0_wght400_grad0_opsz48)
                .build()
        )
    }

    fun showFailureNotification(exceptionMessage: String) {
        val (titleResId, contentResId) = when (phase) {
            Phase.CHECK    -> R.string.notification_failed_check_title    to R.string.notification_failed_check_text
            Phase.DOWNLOAD -> R.string.notification_failed_download_title to R.string.notification_failed_download_text
            Phase.VERIFY   -> R.string.notification_failed_verify_title   to R.string.notification_failed_verify_text
            Phase.INSTALL  -> R.string.notification_failed_install_title  to R.string.notification_failed_install_text
        }

        val styledText = Html.fromHtml(
            "${service.getString(contentResId)}<br><br><tt>$exceptionMessage</tt>",
            Html.FROM_HTML_MODE_LEGACY
        )

        notificationManager.notify(NOTIFICATION_ID_FAILURE,
            Notification.Builder(service, NOTIFICATION_CHANNEL_ID_FAILURE)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(titleResId))
                .setContentText(styledText)
                .setStyle(Notification.BigTextStyle().bigText(styledText))
                .setShowWhen(true)
                .setSmallIcon(R.drawable.security_update_warning_fill0_wght400_grad0_opsz48)
                .build()
        )
    }

    private fun getPendingSettingsIntent(): PendingIntent =
        PendingIntent.getActivity(
            service, PENDING_SETTINGS_ID,
            Intent(service, Settings::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        private const val NOTIFICATION_ID_PROGRESS = 1
        private const val NOTIFICATION_ID_REBOOT = 2
        private const val NOTIFICATION_ID_FAILURE = 3
        private const val NOTIFICATION_ID_UPDATED = 4
        private const val NOTIFICATION_CHANNEL_ID_PROGRESS = "progress"
        private const val NOTIFICATION_CHANNEL_ID_REBOOT = "updates2"
        private const val NOTIFICATION_CHANNEL_ID_FAILURE = "failure"
        private const val NOTIFICATION_CHANNEL_ID_UPDATED = "updated"
        private const val PENDING_REBOOT_ID = 1
        private const val PENDING_SETTINGS_ID = 2
    }
}