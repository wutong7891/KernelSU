package me.weishu.kernelsu.ui.foregroundwatcher

import android.content.Context
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.File

object ForegroundWatcherDaemon {
    private const val ROOT_DIR = "/data/local/xhovosu_fgwatch"
    private const val PID_FILE = "$ROOT_DIR/watcher.pid"

    fun start(context: Context): Result<Unit> = runCatching {
        val config = ForegroundWatcherStore.read(context)
        require(config.packageName.matches(Regex("^[A-Za-z0-9._]+$"))) {
            context.getString(R.string.foreground_watcher_invalid_package)
        }
        val selectedScript = ForegroundWatcherStore.scriptFile(context)
        require(selectedScript.isFile) {
            context.getString(R.string.foreground_watcher_pick_script_first)
        }

        val stage = File(context.cacheDir, "foreground_watcher_stage").apply { mkdirs() }
        val watcher = File(stage, "watcher.sh")
        context.assets.open("foreground_watcher/watcher.sh").use { input ->
            watcher.outputStream().use { output -> input.copyTo(output) }
        }
        val packageFile = File(stage, "package").apply { writeText(config.packageName + "\n") }
        val preInputFile = File(stage, "pre_input").apply { writeText(config.preInput) }
        listOf(watcher, packageFile, preInputFile, selectedScript).forEach { it.setReadable(true, false) }

        // 每一步都容错，避免某条命令返回非零就把整条链掐断
        val command = buildString {
            append("mkdir -p ").append(quote("$ROOT_DIR/config")).append(" 2>/dev/null; ")
            append("chmod 0755 ").append(quote(ROOT_DIR)).append(" 2>/dev/null; ")
            // 注意：不能用 pkill -f，因为它会匹配到执行这条命令的 shell 自身
            append("for f in ").append(quote("$ROOT_DIR/watcher.pid"))
                .append(' ').append(quote("/data/adb/night_foreground_watcher/watcher.pid")).append("; do ")
            append("p=$(cat \"$f\" 2>/dev/null); ")
            append("[ -n \"$p\" ] && [ -r \"/proc/$p/cmdline\" ] && kill \"$p\" 2>/dev/null; ")
            append("rm -f \"$f\"; done; ")
            append("cp -f ").append(quote(watcher.absolutePath)).append(' ').append(quote("$ROOT_DIR/watcher.sh")).append(" 2>&1; ")
            append("cp -f ").append(quote(packageFile.absolutePath)).append(' ').append(quote("$ROOT_DIR/config/package")).append(" 2>&1; ")
            append("cp -f ").append(quote(preInputFile.absolutePath)).append(' ').append(quote("$ROOT_DIR/config/pre_input")).append(" 2>&1; ")
            append("cp -f ").append(quote(selectedScript.absolutePath)).append(' ').append(quote("$ROOT_DIR/selected_script.sh")).append(" 2>&1; ")
            append("chmod 0755 ").append(quote("$ROOT_DIR/watcher.sh")).append(' ')
                .append(quote("$ROOT_DIR/selected_script.sh")).append(" 2>&1; ")
            append("chmod 0600 ").append(quote("$ROOT_DIR/config/package")).append(' ')
                .append(quote("$ROOT_DIR/config/pre_input")).append(" 2>&1; ")
            append("rm -f ").append(quote("$ROOT_DIR/watcher.pid")).append("; ")
            append("if command -v setsid >/dev/null 2>&1; then ")
            append("nohup setsid /system/bin/sh ").append(quote("$ROOT_DIR/watcher.sh"))
            append(" </dev/null >/dev/null 2>&1 & ")
            append("else nohup /system/bin/sh ").append(quote("$ROOT_DIR/watcher.sh"))
            append(" </dev/null >/dev/null 2>&1 & fi; ")
            append("sleep 1; echo XHOVO-DONE")
        }

        val out = ArrayList<String>()
        val err = ArrayList<String>()
        getRootShell().newJob().add(command).to(out, err).exec()

        // 以进程是否真的跑起来为准，而不是命令退出码
        var running = false
        repeat(6) {
            if (isRunning()) {
                running = true
                return@repeat
            }
            Thread.sleep(250)
        }
        if (!running) {
            val detail = (err + out).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            error(
                if (detail.isBlank()) {
                    context.getString(R.string.foreground_watcher_start_failed)
                } else {
                    detail.take(400)
                }
            )
        }
    }

    fun stop(): Result<Unit> = runCatching {
        val result = getRootShell().newJob().add(stopCommand()).exec()
        check(result.isSuccess) { result.err.joinToString("\n").ifBlank { "Cannot stop watcher" } }
    }

    fun isRunning(): Boolean {
        val out = ArrayList<String>()
        val command = "pid=\$(cat ${quote(PID_FILE)} 2>/dev/null); " +
            "[ -n \"\$pid\" ] && [ -r /proc/\$pid/cmdline ] && " +
            "tr '\\000' ' ' < /proc/\$pid/cmdline | grep -F ${quote("$ROOT_DIR/watcher.sh")} >/dev/null && echo running"
        return getRootShell().newJob().add(command).to(out, null).exec().isSuccess && out.any { it.trim() == "running" }
    }

    fun readLog(): String {
        val out = ArrayList<String>()
        getRootShell().newJob().add("tail -n 200 ${quote("$ROOT_DIR/events.log")} 2>/dev/null")
            .to(out, null).exec()
        return out.joinToString("\n")
    }

    private fun stopCommand(): String =
        "pid=\$(cat ${quote(PID_FILE)} 2>/dev/null); " +
            "if [ -n \"\$pid\" ] && [ -r /proc/\$pid/cmdline ] && " +
            "tr '\\000' ' ' < /proc/\$pid/cmdline | grep -F ${quote("$ROOT_DIR/watcher.sh")} >/dev/null; " +
            "then kill \$pid 2>/dev/null || true; fi; " +
            "rm -f ${quote(PID_FILE)} ${quote("$ROOT_DIR/watcher.lock/pid")}; " +
            "rmdir ${quote("$ROOT_DIR/watcher.lock")} 2>/dev/null || true"

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
