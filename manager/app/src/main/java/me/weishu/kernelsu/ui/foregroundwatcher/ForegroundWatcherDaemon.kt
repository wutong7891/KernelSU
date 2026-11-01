package me.weishu.kernelsu.ui.foregroundwatcher

import android.content.Context
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.File

object ForegroundWatcherDaemon {
    private const val ROOT_DIR = "/data/adb/night_foreground_watcher"
    private const val PID_FILE = "$ROOT_DIR/watcher.pid"
    private const val LAUNCH_LOG = "$ROOT_DIR/launcher.log"

    // KernelSU 在开机阶段执行 /data/adb/service.d/*.sh，与管理器进程无关。
    // 监听器要满足「面具不在后台、甚至被卸载之后仍然活着」，就必须在这里登记自启。
    private const val BOOT_DIR = "/data/adb/service.d"
    private const val BOOT_HOOK = "$BOOT_DIR/night_foreground_watcher.sh"

    data class RootEntry(val path: String, val directory: Boolean)

    fun start(context: Context): Result<Unit> = runCatching {
        val config = ForegroundWatcherStore.read(context)
        require(config.packageName.matches(Regex("^[A-Za-z0-9._]+$"))) { "Invalid package name" }
        val selectedScript = ForegroundWatcherStore.scriptFile(context)
        val rootScriptPath = config.rootScriptPath
        if (rootScriptPath.isNotBlank()) {
            requireValidAbsolutePath(rootScriptPath)
        } else {
            require(selectedScript.isFile) { "Selected script is missing" }
        }

        val stage = File(context.cacheDir, "foreground_watcher_stage").apply { mkdirs() }
        val watcher = File(stage, "watcher.sh")
        context.assets.open("foreground_watcher/watcher.sh").use { input ->
            watcher.outputStream().use { output -> input.copyTo(output) }
        }
        val packageFile = File(stage, "package").apply { writeText(config.packageName + "\n") }
        val managerPackageFile = File(stage, "manager_package").apply { writeText(context.packageName + "\n") }
        val scriptPathFile = File(stage, "script_path").apply {
            writeText((rootScriptPath.ifBlank { "$ROOT_DIR/selected_script.sh" }) + "\n")
        }
        val preInputFile = File(stage, "pre_input").apply { writeText(config.preInput) }
        val bootHook = File(stage, "boot_hook.sh").apply {
            writeText(
                """
                #!/system/bin/sh
                # Night foreground watcher autostart. Runs from /data/adb, independent of the manager app.
                DIR=/data/adb/night_foreground_watcher
                [ -f "${'$'}DIR/watcher.sh" ] || exit 0
                pid=${'$'}(cat "${'$'}DIR/watcher.pid" 2>/dev/null)
                if [ -n "${'$'}pid" ] && [ -r "/proc/${'$'}pid/cmdline" ] && \
                   tr '\000' ' ' < "/proc/${'$'}pid/cmdline" | grep -F "${'$'}DIR/watcher.sh" >/dev/null 2>&1; then
                  exit 0
                fi
                mkdir -p "${'$'}DIR/config"
                setsid /system/bin/sh "${'$'}DIR/watcher.sh" </dev/null >>"${'$'}DIR/launcher.log" 2>&1 &
                """.trimIndent() + "\n"
            )
        }
        val stagedFiles = mutableListOf(watcher, packageFile, managerPackageFile, scriptPathFile, preInputFile, bootHook)
        if (rootScriptPath.isBlank()) stagedFiles += selectedScript
        stagedFiles.forEach {
            it.setReadable(true, false)
        }

        val command = buildString {
            append("set -e; mkdir -p ").append(quote("$ROOT_DIR/config")).append("; ")
            append(stopCommand()).append("; ")
            append("cp ").append(quote(watcher.absolutePath)).append(' ').append(quote("$ROOT_DIR/watcher.sh")).append("; ")
            append("cp ").append(quote(packageFile.absolutePath)).append(' ').append(quote("$ROOT_DIR/config/package")).append("; ")
            append("cp ").append(quote(managerPackageFile.absolutePath)).append(' ')
                .append(quote("$ROOT_DIR/config/manager_package")).append("; ")
            append("cp ").append(quote(scriptPathFile.absolutePath)).append(' ')
                .append(quote("$ROOT_DIR/config/script_path")).append("; ")
            append("cp ").append(quote(preInputFile.absolutePath)).append(' ').append(quote("$ROOT_DIR/config/pre_input")).append("; ")
            if (rootScriptPath.isBlank()) {
                append("cp ").append(quote(selectedScript.absolutePath)).append(' ')
                    .append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
                append("sed -i 's/\\r$//' ").append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
                append("chmod 0700 ").append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
            } else {
                append("test -f ").append(quote(rootScriptPath)).append(" && test -r ")
                    .append(quote(rootScriptPath)).append("; ")
                append("rm -f ").append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
            }
            append("chmod 0700 ").append(quote("$ROOT_DIR/watcher.sh")).append("; ")
            append("mkdir -p ").append(quote(BOOT_DIR)).append("; ")
            append("cp ").append(quote(bootHook.absolutePath)).append(' ').append(quote(BOOT_HOOK)).append("; ")
            append("chmod 0755 ").append(quote(BOOT_HOOK)).append("; ")
            append("chmod 0600 ").append(quote("$ROOT_DIR/config/package")).append(' ')
                .append(quote("$ROOT_DIR/config/manager_package")).append(' ')
                .append(quote("$ROOT_DIR/config/script_path")).append(' ')
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
        val config = ForegroundWatcherStore.read(context)
        val rootScriptPath = config.rootScriptPath
        if (rootScriptPath.isNotBlank()) {
            requireValidAbsolutePath(rootScriptPath)
        } else {
            require(selectedScript.isFile) { "Selected script is missing" }
        }
        val scriptToRun = rootScriptPath.ifBlank { "$ROOT_DIR/selected_script.sh" }
        val scriptWorkingDirectory = scriptToRun.substringBeforeLast('/', "").ifEmpty { "/" }
        val stage = File(context.cacheDir, "foreground_watcher_test").apply { mkdirs() }
        val preInputFile = File(stage, "pre_input").apply { writeText(config.preInput) }
        listOf(selectedScript, preInputFile).forEach { it.setReadable(true, false) }
        val command = buildString {
            append("mkdir -p ").append(quote("$ROOT_DIR/config")).append("; ")
            if (rootScriptPath.isBlank()) {
                append("cp ").append(quote(selectedScript.absolutePath)).append(' ')
                    .append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
                append("sed -i 's/\\r$//' ").append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
                append("chmod 0700 ").append(quote("$ROOT_DIR/selected_script.sh")).append("; ")
            } else {
                append("test -f ").append(quote(rootScriptPath)).append(" && test -r ")
                    .append(quote(rootScriptPath)).append("; ")
            }
            append("cp ").append(quote(preInputFile.absolutePath)).append(' ').append(quote("$ROOT_DIR/config/pre_input")).append("; ")
            append("export PATH=/data/adb/ksu/bin:/system/bin:/system/xbin:\$PATH; ")
            append("echo '[manual-test] start' >> ").append(quote("$ROOT_DIR/events.log")).append("; ")
            append("cd ").append(quote(scriptWorkingDirectory)).append(" && /system/bin/sh ")
                .append(quote(scriptToRun))
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

    fun purge(): Result<Unit> = runCatching {
        val command = stopCommand() + "; " + purgeCommand()
        val result = getRootShell().newJob().add(command).exec()
        check(result.isSuccess) { result.err.joinToString("\n").ifBlank { "Cannot remove watcher data" } }
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
        val command = "test -f ${quote(path)} && test -r ${quote(path)}"
        val result = getRootShell().newJob().add(command).exec()
        check(result.isSuccess) { result.err.joinToString("\n").ifBlank { "Selected root script is not readable" } }
        val current = ForegroundWatcherStore.read(context)
        ForegroundWatcherStore.write(context, current.copy(scriptName = path, rootScriptPath = path))
        ForegroundWatcherStore.scriptFile(context).delete()
    }

    private fun stopCommand(): String =
        "pid=\$(cat ${quote(PID_FILE)} 2>/dev/null || true); " +
            "if [ -n \"\$pid\" ] && [ -r /proc/\$pid/cmdline ] && " +
            "tr '\\000' ' ' < /proc/\$pid/cmdline | grep -F ${quote("$ROOT_DIR/watcher.sh")} >/dev/null; " +
            // A setsid-launched watcher owns its process group. Stop the group first so a
            // currently running selected script cannot outlive the controlled watcher.
            "then kill -TERM -\$pid 2>/dev/null || true; kill \$pid 2>/dev/null || true; fi; " +
            "rm -f ${quote(PID_FILE)} ${quote("$ROOT_DIR/watcher.lock/pid")} ${quote(BOOT_HOOK)}; " +
            "rmdir ${quote("$ROOT_DIR/watcher.lock")} 2>/dev/null || true"

    private fun purgeCommand(): String =
        "rm -f ${quote("$ROOT_DIR/config/package")} ${quote("$ROOT_DIR/config/manager_package")} " +
            "${quote("$ROOT_DIR/config/script_path")} " +
            "${quote("$ROOT_DIR/config/pre_input")} ${quote("$ROOT_DIR/selected_script.sh")} " +
            "${quote("$ROOT_DIR/watcher.sh")} ${quote("$ROOT_DIR/events.log")} ${quote(LAUNCH_LOG)}; " +
            "rmdir ${quote("$ROOT_DIR/config")} 2>/dev/null || true; " +
            "rmdir ${quote(ROOT_DIR)} 2>/dev/null || true"

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun requireValidAbsolutePath(path: String) {
        require(path.startsWith('/') && path.length <= 1024 && path.none { it == '\u0000' || it == '\n' || it == '\r' }) {
            "Invalid root path"
        }
    }
}
