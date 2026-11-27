package ua.andrii.homepower

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) PowerWidget.refresh(context, null)
        if (ok && (Prefs(context).monitor || Prefs(context).hub)) MonitorService.start(context)
    }
}
