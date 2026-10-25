package me.weishu.kernelsu.ui.foregroundwatcher

import android.content.Context
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.File

object ForegroundWatcherDaemon {
    private const val ROOT_DIR = "/data/adb/night_foreground_watcher"
    private const val PID_FILE = "$ROOT_DIR/watcher.pid"

    fun start(context: Context): Result<Unit> = runCatching {
        val config = ForegroundWatcherStore.read(context)
        require(config.packageName.matches(Regex("^[A-Za-z0-9._]+$"))) { "Invalid package name" }
        val selectedScript = ForegroundWatcherStore.scriptFile(context)
        require(selectedScript.isFile) { "Selected script is missing" }

        val stage = File(context.cacheDir, "foreground_watcher_stage").apply { mkdirs() }
        val watcher = File(stage, "watcher.sh")
        context.assets.open("foreground_watcher/watcher.sh").use { input ->
            watcher.outputStream().use { output -> input.copyTo(output) }
        }
        val packageFile = File(stage, "package").apply { writeText(config.packageName + "\n") }
        val preInputFile = File(stage, "pre_input").apply { writeText(config.preInput) }
        listOf(watcher, packageFile, preInputFile, selectedScript).forEach { it.setReadable(true, false) }

        val command = buildString {
            append("set -e; mkdir -p ").append(quote("$ROOT_DIR/config")).append("; ")
            append(stopCommand()).append("; ")
            append("cp ").append(quote(watcher.absolutePath)).append(' ').append(quote("$ROOT_DIR/watcher.sh")).append("; ")
            append("cp ").append(quote(packageFile.absolutePath)).append(' ').append(quote("$ROOT_DIR/config/package")).append("; ")
            append("cp ").append(quote(preInputFile.absolutePath)).append(' ').append(quote("$ROOT_DIR/config/pre_input")).append("; ")
            append("cp ").append(quote(selectedScript.absolutePath)).append(' ').append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
            append("chmod 0700 ").append(quote("$ROOT_DIR/watcher.sh")).append(' ')
                .append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
            append("chmod 0600 ").append(quote("$ROOT_DIR/config/package")).append(' ')
                .append(quote("$ROOT_DIR/config/pre_input")).append("; ")
            append("if command -v setsid >/dev/null 2>&1; then ")
            append("nohup setsid /system/bin/sh ").append(quote("$ROOT_DIR/watcher.sh"))
            append(" </dev/null >/dev/null 2>&1 & ")
            append("else nohup /system/bin/sh ").append(quote("$ROOT_DIR/watcher.sh"))
            append(" </dev/null >/dev/null 2>&1 & fi")
        }
        val result = getRootShell().newJob().add(command).exec()
        check(result.isSuccess) { result.err.joinToString("\n").ifBlank { "Cannot start watcher" } }
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
