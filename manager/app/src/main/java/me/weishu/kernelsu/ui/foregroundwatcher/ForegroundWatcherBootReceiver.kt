package me.weishu.kernelsu.ui.foregroundwatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ForegroundWatcherBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && ForegroundWatcherStore.read(context).enabled) {
            ForegroundWatcherService.setEnabled(context, true)
        }
    }
}
