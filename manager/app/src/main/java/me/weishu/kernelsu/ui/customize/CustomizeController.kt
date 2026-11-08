package me.weishu.kernelsu.ui.customize

import android.content.Context
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.File

/**
 * 仙鹤定制：ADB 隐藏、痕迹清理、安装包落地。
 *
 * 所有逻辑都在 assets/customize/xh_hide.sh 里，这里只负责把它推到
 * /data/local 并带参数执行，避免在 Kotlin 里拼 shell 造成转义问题。
 */
object CustomizeController {

    private const val SCRIPT_DIR = "/data/local/xh_customize"
    private const val SCRIPT_PATH = "$SCRIPT_DIR/xh_hide.sh"
    private const val APK_TARGET = "/data/adb/XhovoSU-manager.apk"

    /** 部署脚本并执行指定动作，返回 shell 输出。 */
    fun run(context: Context, action: String): Result<String> = runCatching {
        val stage = File(context.cacheDir, "xh_customize_stage").apply {
            deleteRecursively()
            mkdirs()
        }
        val local = File(stage, "xh_hide.sh")
        context.assets.open("customize/xh_hide.sh").use { input ->
            local.outputStream().use { output -> input.copyTo(output) }
        }

        val pkg = context.packageName
        val command = buildString {
            append("mkdir -p ").append(quote(SCRIPT_DIR)).append(" 2>/dev/null; ")
            append("cp -f ").append(quote(local.absolutePath)).append(' ')
            append(quote(SCRIPT_PATH)).append(" 2>/dev/null; ")
            append("chmod 0755 ").append(quote(SCRIPT_PATH)).append(" 2>&1; ")
            append("PKG=").append(quote(pkg)).append(" sh ")
            append(quote(SCRIPT_PATH)).append(' ').append(action).append(" 2>&1")
        }

        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val result = getRootShell().newJob().add(command).to(out, err).exec()
        stage.deleteRecursively()

        val text = (out + err).joinToString("\n").trim()
        if (!result.isSuccess && text.isEmpty()) {
            error("命令执行失败")
        }
        text
    }

    /** 读取安装包副本状态。 */
    fun apkInfo(): Pair<Boolean, Long> {
        val out = ArrayList<String>()
        getRootShell().newJob()
            .add("[ -f ${quote(APK_TARGET)} ] && stat -c %s ${quote(APK_TARGET)} 2>/dev/null || echo 0")
            .to(out, null).exec()
        val size = out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
        return size > 0 to size
    }

    fun apkPath(): String = APK_TARGET

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
