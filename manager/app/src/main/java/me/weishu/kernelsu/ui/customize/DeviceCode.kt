package me.weishu.kernelsu.ui.customize

import me.weishu.kernelsu.ui.util.getRootShell

/**
 * 设备码。
 *
 * 与参考项目同样的思路：优先 IMEI，读不到退 ANDROID_ID，都没有才随机。
 * 明文 IMEI 不外传，只参与本地摘要。
 */
object DeviceCode {

    private const val SALT = "xhovo-su"

    fun compute(): String {
        val raw = readImei() ?: readAndroidId() ?: randomSeed()
        val digest = md5(raw + SALT)
        return "xhovo-" + digest.take(28)
    }

    /** IMEI：先查属性，再用 service call 兜底。 */
    private fun readImei(): String? {
        val props = listOf(
            "persist.radio.imei1",
            "persist.radio.imei",
            "ro.ril.oem.imei1",
            "gsm.sim.imei",
            "ril.gsm.imei",
        )
        for (p in props) {
            val v = exec("getprop $p").trim()
            if (v.length >= 14 && v.all { it.isDigit() }) return v
        }

        // service call iphonesubinfo 兜底
        val raw = exec("service call iphonesubinfo 1")
        val digits = raw.filter { it.isDigit() }
        if (digits.length >= 14) return digits.takeLast(15)
        return null
    }

    private fun readAndroidId(): String? {
        val v = exec("settings get secure android_id").trim()
        return v.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun randomSeed(): String =
        System.currentTimeMillis().toString() + Math.random()

    private fun md5(input: String): String = try {
        val md = java.security.MessageDigest.getInstance("MD5")
        md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        input.hashCode().toString()
    }

    private fun exec(cmd: String): String {
        val out = ArrayList<String>()
        getRootShell().newJob().add(cmd).to(out, null).exec()
        return out.joinToString("\n")
    }
}
