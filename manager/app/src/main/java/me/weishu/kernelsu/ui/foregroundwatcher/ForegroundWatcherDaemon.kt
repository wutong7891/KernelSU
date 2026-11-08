package me.weishu.kernelsu.ui.foregroundwatcher

import android.content.Context
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.File

/**
 * 前台应用监听守护进程。
 *
 * 设计要点：
 * - 所有 shell 逻辑都写在 assets 的 install.sh 里，这里只负责准备文件并调用它，
 *   避免在 Kotlin 字符串里拼命令带来的转义问题。
 * - 状态目录放在 /data/local，不在 /data/adb，卸载 KernelSU 不会被清理。
 * - 进程用 setsid 脱离，父进程会变成 init，面具关了、卸载了都带不走它。
 */
object ForegroundWatcherDaemon {

    private const val ROOT_DIR = "/data/local/xhovosu_fgwatch"
    private const val PID_FILE = "$ROOT_DIR/watcher.pid"
    private const val LOCK_FILE = "$ROOT_DIR/watcher.lock/pid"

    fun start(context: Context): Result<Unit> = runCatching {
        val config = ForegroundWatcherStore.read(context)

        require(config.packageName.matches(Regex("^[A-Za-z0-9._]+$"))) {
            context.getString(R.string.foreground_watcher_pick_app_first)
        }

        val script = ForegroundWatcherStore.scriptFile(context)
        require(script.isFile) {
            context.getString(R.string.foreground_watcher_pick_script_first)
        }

        // 1. 把所有要落地的文件准备到缓存目录
        val stage = File(context.cacheDir, "xhovo_fg_stage").apply {
            deleteRecursively()
            mkdirs()
        }

        copyAsset(context, "foreground_watcher/watcher.sh", File(stage, "watcher.sh"))
        copyAsset(context, "foreground_watcher/install.sh", File(stage, "install.sh"))
        File(stage, "package").writeText(config.packageName + "\n")
        File(stage, "pre_input").writeText(normalizePreInput(config.preInput))
        script.copyTo(File(stage, "selected_script.sh"), overwrite = true)

        // 2. 交给 shell 去安装和启动
        val command = "sh " + shQuote(File(stage, "install.sh").absolutePath) +
                " " + shQuote(stage.absolutePath) + " 2>&1"

        val out = ArrayList<String>()
        val err = ArrayList<String>()
        getRootShell().newJob().add(command).to(out, err).exec()

        val detail = (out + err).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

        // 3. 以进程是否真的活着作为成功标准
        var running = false
        repeat(8) {
            if (isRunning()) {
                running = true
                return@repeat
            }
            Thread.sleep(200)
        }

        if (!running) {
            error(detail.ifBlank { context.getString(R.string.foreground_watcher_start_failed) })
        }
        stage.deleteRecursively()
    }

    fun stop(): Result<Unit> = runCatching {
        val command = buildString {
            append("pid=$(cat ").append(shQuote(PID_FILE)).append(" 2>/dev/null); ")
            append("if [ -n \"$pid\" ]; then kill \"$pid\" 2>/dev/null; sleep 1; kill -9 \"$pid\" 2>/dev/null; fi; ")
            append("rm -f ").append(shQuote(PID_FILE)).append(' ').append(shQuote(LOCK_FILE)).append("; ")
            append("rm -rf ").append(shQuote("$ROOT_DIR/watcher.lock")).append(" 2>/dev/null; ")
            append("echo STOPPED")
        }
        val result = getRootShell().newJob().add(command).exec()
        check(result.isSuccess) { result.err.joinToString("\n").ifBlank { "停止失败" } }
    }

    fun isRunning(): Boolean {
        val out = ArrayList<String>()
        val command = buildString {
            append("pid=$(cat ").append(shQuote(PID_FILE)).append(" 2>/dev/null); ")
            append("[ -n \"$pid\" ] && [ -r /proc/$pid/cmdline ] && ")
            append("grep -q xhovosu_fgwatch /proc/$pid/cmdline && echo running")
        }
        return getRootShell().newJob().add(command).to(out, null).exec().isSuccess &&
                out.any { it.trim() == "running" }
    }

    fun readLog(): String {
        val out = ArrayList<String>()
        getRootShell().newJob()
            .add("tail -n 200 " + shQuote("$ROOT_DIR/events.log") + " 2>/dev/null")
            .to(out, null).exec()
        return out.joinToString("\n")
    }

    fun rootDir(): String = ROOT_DIR

    /** 预输入规范化：统一 LF，并保证最后一行也有换行，等于每行末尾自动回车。 */
    private fun normalizePreInput(raw: String): String {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        return when {
            text.isEmpty() -> ""
            text.endsWith("\n") -> text
            else -> text + "\n"
        }
    }

    private fun copyAsset(context: Context, asset: String, target: File) {
        context.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
