package me.weishu.kernelsu.ui.screen.cardkey

import android.content.Context
import me.weishu.kernelsu.ksuApp

/**
 * 卡密页的本地状态。
 *
 * 卡密本身不在这里校验 —— 校验交给 [CardKeyApi] 做本机验签。这里只负责三件事：
 *
 * 1. 记住上次通过的卡密，下次启动预填，用户点一下就能进；
 * 2. 记住「记住卡密」开关本身，免得每次启动都弹回默认；
 * 3. 给校验过程提供 SharedPreferences 存放位置。
 *
 * 注意：**这里不保存任何「已通过」标记**。闸门每次冷启动都会重新验签，
 * 改 XML 或删文件都只是让用户重新输卡密，绕不过签名校验。
 */
object CardKeyStore {

    const val PREFS_NAME = "xhovo_su_cardkey"

    private const val KEY_REMEMBERED = "remembered_key"
    private const val KEY_REMEMBER_ENABLED = "remember_enabled"

    private fun prefs(context: Context = ksuApp) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 上次通过、且用户勾了「记住卡密」的那一张；没有就是 null。 */
    fun rememberedKey(context: Context = ksuApp): String? =
        prefs(context).getString(KEY_REMEMBERED, null)?.takeIf { it.isNotBlank() }

    fun remember(key: String, context: Context = ksuApp) {
        prefs(context).edit().putString(KEY_REMEMBERED, key.trim()).apply()
    }

    fun forget(context: Context = ksuApp) {
        prefs(context).edit().remove(KEY_REMEMBERED).apply()
    }

    fun isRememberEnabled(context: Context = ksuApp): Boolean =
        prefs(context).getBoolean(KEY_REMEMBER_ENABLED, true)

    fun setRememberEnabled(enabled: Boolean, context: Context = ksuApp) {
        prefs(context).edit().putBoolean(KEY_REMEMBER_ENABLED, enabled).apply()
    }
}
