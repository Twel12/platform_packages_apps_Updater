package app.seamlessupdate.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.getSystemService(UserManager::class.java).isSystemUser) {
            Settings.clearWaitingForReboot(context)
            PeriodicJob.schedule(context)
        } else {
            context.packageManager.setApplicationEnabledSetting(
                context.packageName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                0
            )
        }
    }
}