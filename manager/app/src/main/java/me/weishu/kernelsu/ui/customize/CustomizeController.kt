package me.weishu.kernelsu.ui.customize

import android.content.Context
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.File

/**
 * 仙鹤隐形模式。
 *
 * 流程：固化安装包 → 部署守护 → 静默卸载面具。
 * 之后打开指定应用，守护从 /data/adb 静默装回面具并拉起。
 */
object CustomizeController {

    private const val ROOT_DIR = "/data/local/xh_guard"
    private const val SCRIPT_PATH = "$ROOT_DIR/xh_guard.sh"
    private const val CONF_PATH = "$ROOT_DIR/target"
    private const val APK_PATH = "/data/adb/XhovoSU-manager.apk"

    /** 把脚本推到 /data/local 并带参数执行。 */
    fun run(context: Context, arguments: String): Result<String> = runCatching {
        ensureScript(context)

        val pkg = context.packageName
        val command = buildString {
            append("PKG=").append(quote(pkg)).append(' ')
            append("sh ").append(quote(SCRIPT_PATH)).append(' ')
            append(arguments).append(" 2>&1")
        }

        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val result = getRootShell().newJob().add(command).to(out, err).exec()

        val text = (out + err).joinToString("\n").trim()
        if (!result.isSuccess && text.isEmpty()) error("命令执行失败")
        text
    }

    /** 开启：配置目标应用 + 注入守护 + 卸载面具。 */
    fun arm(context: Context, targetPackage: String): Result<String> =
        run(context, "arm " + quote(targetPackage))

    /** 关闭守护，不触碰面具。 */
    fun disarm(context: Context): Result<String> = run(context, "disarm")

    /** 立刻恢复一次面具。 */
    fun revive(context: Context): Result<String> = run(context, "now")

    fun status(context: Context): Result<String> = run(context, "status")

    /** 读取已配置的目标应用包名。 */
    fun targetPackage(): String {
        val out = ArrayList<String>()
        getRootShell().newJob()
            .add("cat " + quote(CONF_PATH) + " 2>/dev/null")
            .to(out, null).exec()
        return out.firstOrNull()?.trim().orEmpty()
    }

    /** 守护是否在跑。 */
    fun isGuarding(): Boolean {
        val out = ArrayList<String>()
        getRootShell().newJob()
            .add(
                "pid=$(cat " + quote("$ROOT_DIR/guard.pid") + " 2>/dev/null); " +
                    "[ -n \"\$pid\" ] && [ -r /proc/\$pid/cmdline ] && echo running",
            )
            .to(out, null).exec()
        return out.any { it.trim() == "running" }
    }

    /** 面具当前是否可见（enabled / 未 hide）。 */
    fun isManagerVisible(context: Context): Boolean {
        val out = ArrayList<String>()
        val pkg = context.packageName
        getRootShell().newJob()
            .add("pm list packages -e 2>/dev/null | grep -c '^package:" + pkg + "$'")
            .to(out, null).exec()
        return (out.firstOrNull()?.trim()?.toIntOrNull() ?: 0) > 0
    }

    /** 面具包是否还在系统里（可能处于 hide 状态）。 */
    fun isManagerPresent(context: Context): Boolean {
        val out = ArrayList<String>()
        val pkg = context.packageName
        getRootShell().newJob()
            .add("pm list packages 2>/dev/null | grep -c '^package:" + pkg + "$'")
            .to(out, null).exec()
        return (out.firstOrNull()?.trim()?.toIntOrNull() ?: 0) > 0
    }

    private fun ensureScript(context: Context) {
        val stage = File(context.cacheDir, "xh_guard_stage").apply {
            deleteRecursively()
            mkdirs()
        }
        val local = File(stage, "xh_guard.sh")
        context.assets.open("customize/xh_guard.sh").use { input ->
            local.outputStream().use { output -> input.copyTo(output) }
        }

        val command = buildString {
            append("mkdir -p ").append(quote(ROOT_DIR)).append(" 2>/dev/null; ")
            append("cp -f ").append(quote(local.absolutePath)).append(' ')
            append(quote(SCRIPT_PATH)).append(" 2>/dev/null; ")
            append("chmod 0755 ").append(quote(SCRIPT_PATH)).append(" 2>/dev/null")
        }
        getRootShell().newJob().add(command).exec()
        stage.deleteRecursively()
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
