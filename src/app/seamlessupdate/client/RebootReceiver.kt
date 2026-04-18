package app.seamlessupdate.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class RebootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        reboot(context)
    }

    companion object {
        fun reboot(context: Context) {
            context.getSystemService(PowerManager::class.java).reboot(null)
        }
    }
}