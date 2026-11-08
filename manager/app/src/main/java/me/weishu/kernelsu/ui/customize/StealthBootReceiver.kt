package me.weishu.kernelsu.ui.customize

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机后确保监听在跑。
 *
 * 直接复用 CustomizeController.sync()：只在已配置且有镜像时才启动，
 * 和「打开 App」「保存配置」两个入口走同一套逻辑。
 */
class StealthBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                runCatching { CustomizeController.sync(context) }
            }
        }
    }
}
