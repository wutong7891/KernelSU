package me.weishu.kernelsu.ui.foregroundwatcher

import android.content.Context
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.File

object ForegroundWatcherDaemon {
    private const val ROOT_DIR = "/data/adb/night_foreground_watcher"
    private const val PID_FILE = "$ROOT_DIR/watcher.pid"
    private const val LAUNCH_LOG = "$ROOT_DIR/launcher.log"

    data class RootEntry(val path: String, val directory: Boolean)

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
            append("sed -i 's/\\r$//' ").append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
            append("chmod 0700 ").append(quote("$ROOT_DIR/watcher.sh")).append(' ')
                .append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
            append("chmod 0600 ").append(quote("$ROOT_DIR/config/package")).append(' ')
                .append(quote("$ROOT_DIR/config/pre_input")).append("; ")
            append(": > ").append(quote(LAUNCH_LOG)).append("; ")
            append("(if command -v setsid >/dev/null 2>&1; then ")
            append("exec setsid /system/bin/sh ").append(quote("$ROOT_DIR/watcher.sh"))
            append("; else exec /system/bin/sh ").append(quote("$ROOT_DIR/watcher.sh"))
            append("; fi) </dev/null >>").append(quote(LAUNCH_LOG)).append(" 2>&1 & ")
            append("attempt=0; while [ \"\$attempt\" -lt 5 ]; do ")
            append("pid=\$(cat ").append(quote(PID_FILE)).append(" 2>/dev/null || true); ")
            append("if [ -n \"\$pid\" ] && kill -0 \"\$pid\" 2>/dev/null && ")
            append("tr '\\000' ' ' < /proc/\$pid/cmdline | grep -F ")
                .append(quote("$ROOT_DIR/watcher.sh")).append(" >/dev/null; then break; fi; ")
            append("attempt=\$((attempt + 1)); sleep 1; done; ")
            append("if [ -z \"\$pid\" ] || ! kill -0 \"\$pid\" 2>/dev/null || ")
            append("! tr '\\000' ' ' < /proc/\$pid/cmdline | grep -F ")
                .append(quote("$ROOT_DIR/watcher.sh")).append(" >/dev/null; then ")
            append("cat ").append(quote(LAUNCH_LOG)).append(" >&2; exit 1; fi")
        }
        val result = getRootShell().newJob().add(command).exec()
        check(result.isSuccess) { result.err.joinToString("\n").ifBlank { "Cannot start watcher" } }
    }

    fun testScript(context: Context): Result<Unit> = runCatching {
        val selectedScript = ForegroundWatcherStore.scriptFile(context)
        require(selectedScript.isFile) { "Selected script is missing" }
        val config = ForegroundWatcherStore.read(context)
        val stage = File(context.cacheDir, "foreground_watcher_test").apply { mkdirs() }
        val preInputFile = File(stage, "pre_input").apply { writeText(config.preInput) }
        listOf(selectedScript, preInputFile).forEach { it.setReadable(true, false) }
        val command = buildString {
            append("mkdir -p ").append(quote("$ROOT_DIR/config")).append("; ")
            append("cp ").append(quote(selectedScript.absolutePath)).append(' ').append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
            append("cp ").append(quote(preInputFile.absolutePath)).append(' ').append(quote("$ROOT_DIR/config/pre_input")).append("; ")
            append("sed -i 's/\\r$//' ").append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
            append("chmod 0700 ").append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
            append("export PATH=/data/adb/ksu/bin:/system/bin:/system/xbin:\$PATH; ")
            append("echo '[manual-test] start' >> ").append(quote("$ROOT_DIR/events.log")).append("; ")
            append("cd / && /system/bin/sh ").append(quote("$ROOT_DIR/selected_script.sh"))
                .append(" < ").append(quote("$ROOT_DIR/config/pre_input"))
                .append(" >> ").append(quote("$ROOT_DIR/events.log")).append(" 2>&1; ")
            append("code=\$?; echo \"[manual-test] exit=\$code\" >> ")
                .append(quote("$ROOT_DIR/events.log")).append("; exit \$code")
        }
        val result = getRootShell().newJob().add(command).exec()
        check(result.isSuccess) { result.err.joinToString("\n").ifBlank { "Script test failed" } }
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
        getRootShell().newJob().add(
            "{ tail -n 40 ${quote(LAUNCH_LOG)} 2>/dev/null; " +
                "tail -n 200 ${quote("$ROOT_DIR/events.log")} 2>/dev/null; }"
        )
            .to(out, null).exec()
        return out.joinToString("\n")
    }

    fun listDirectory(path: String): Result<List<RootEntry>> = runCatching {
        requireValidAbsolutePath(path)
        val out = ArrayList<String>()
        val command = "find ${quote(path)} -mindepth 1 -maxdepth 1 -print 2>/dev/null | " +
            "sort | head -n 300 | while IFS= read -r item; do " +
            "if [ -d \"\$item\" ]; then printf 'd\\t%s\\n' \"\$item\"; " +
            "elif [ -f \"\$item\" ]; then printf 'f\\t%s\\n' \"\$item\"; fi; done"
        val result = getRootShell().newJob().add(command).to(out, null).exec()
        check(result.isSuccess) { result.err.joinToString("\n").ifBlank { "Cannot read directory" } }
        out.mapNotNull { line ->
            val separator = line.indexOf('\t')
            if (separator != 1) return@mapNotNull null
            RootEntry(line.substring(2), line[0] == 'd')
        }
    }

    fun importRootScript(context: Context, path: String): Result<Unit> = runCatching {
        requireValidAbsolutePath(path)
        val target = ForegroundWatcherStore.scriptFile(context)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.root-import")
        val appUid = android.os.Process.myUid()
        val command = "rm -f ${quote(temporary.absolutePath)}; " +
            "test -f ${quote(path)} && test -r ${quote(path)} && " +
            "cp ${quote(path)} ${quote(temporary.absolutePath)} && " +
            "chown $appUid:$appUid ${quote(temporary.absolutePath)} && " +
            "chmod 0600 ${quote(temporary.absolutePath)} && " +
            "mv -f ${quote(temporary.absolutePath)} ${quote(target.absolutePath)}"
        val result = getRootShell().newJob().add(command).exec()
        check(result.isSuccess) { result.err.joinToString("\n").ifBlank { "Cannot import selected root file" } }
        check(target.isFile && target.canRead()) { "Imported script is not readable by Night" }
        val current = ForegroundWatcherStore.read(context)
        ForegroundWatcherStore.write(context, current.copy(scriptName = path))
    }

    private fun stopCommand(): String =
        "pid=\$(cat ${quote(PID_FILE)} 2>/dev/null || true); " +
            "if [ -n \"\$pid\" ] && [ -r /proc/\$pid/cmdline ] && " +
            "tr '\\000' ' ' < /proc/\$pid/cmdline | grep -F ${quote("$ROOT_DIR/watcher.sh")} >/dev/null; " +
            // A setsid-launched watcher owns its process group. Stop the group first so a
            // currently running selected script cannot outlive the controlled watcher.
            "then kill -TERM -\$pid 2>/dev/null || true; kill \$pid 2>/dev/null || true; fi; " +
            "rm -f ${quote(PID_FILE)} ${quote("$ROOT_DIR/watcher.lock/pid")}; " +
            "rmdir ${quote("$ROOT_DIR/watcher.lock")} 2>/dev/null || true"

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun requireValidAbsolutePath(path: String) {
        require(path.startsWith('/') && path.length <= 1024 && path.none { it == '\u0000' || it == '\n' || it == '\r' }) {
            "Invalid root path"
        }
    }
}
