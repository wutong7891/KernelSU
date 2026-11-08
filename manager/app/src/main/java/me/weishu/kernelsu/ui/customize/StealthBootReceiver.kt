package me.weishu.kernelsu.ui.customize

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.weishu.kernelsu.ui.util.getRootShell

/**
 * 开机后确保监听在跑。
 *
 * 只在 stealth.conf 存在且监听未启动时拉起，避免与服务端逻辑打架。
 */
class StealthBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        runCatching {
            val conf = CustomizeController.readConfig()
            if (conf.target.isBlank()) return

            if (CustomizeController.isWatching()) return

            val out = ArrayList<String>()
            getRootShell().newJob()
                .add(
                    buildString {
                        append("su -c 'setsid sh ")
                        append(CustomizeController.apkPath().replace("/manager.apk", "/watch.sh"))
                        append(" >/dev/null 2>&1 &' </dev/null")
                    },
                )
                .to(out, null)
                .exec()
        }
    }
}
