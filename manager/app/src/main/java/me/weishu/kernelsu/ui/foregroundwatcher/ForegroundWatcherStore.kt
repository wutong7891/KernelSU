package me.weishu.kernelsu.ui.foregroundwatcher

import android.content.Context
import android.net.Uri
import java.io.File

data class ForegroundWatcherConfig(
    val enabled: Boolean = false,
    val packageName: String = "",
    val appLabel: String = "",
    val scriptName: String = "",
    val rootScriptPath: String = "",
    val useRoot: Boolean = true,
    val preInput: String = "",
)

object ForegroundWatcherStore {
    private const val PREFS = "foreground_watcher"
    private const val SCRIPT_DIRECTORY = "foreground_watcher"
    private const val SCRIPT_FILE = "selected_script.sh"

    fun read(context: Context): ForegroundWatcherConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val scriptName = prefs.getString("script_name", "").orEmpty()
        val rootScriptPath = if (prefs.contains("root_script_path")) {
            prefs.getString("root_script_path", "").orEmpty()
        } else {
            scriptName.takeIf { it.startsWith('/') }.orEmpty()
        }
        return ForegroundWatcherConfig(
            enabled = prefs.getBoolean("enabled", false),
            packageName = prefs.getString("package_name", "").orEmpty(),
            appLabel = prefs.getString("app_label", "").orEmpty(),
            scriptName = scriptName,
            rootScriptPath = rootScriptPath,
            useRoot = prefs.getBoolean("use_root", true),
            preInput = prefs.getString("pre_input", "").orEmpty(),
        )
    }

    fun write(context: Context, config: ForegroundWatcherConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", config.enabled)
            .putString("package_name", config.packageName)
            .putString("app_label", config.appLabel)
            .putString("script_name", config.scriptName)
            .putString("root_script_path", config.rootScriptPath)
            .putBoolean("use_root", config.useRoot)
            .putString("pre_input", config.preInput)
            .apply()
    }

    fun scriptFile(context: Context): File =
        File(File(context.filesDir, SCRIPT_DIRECTORY), SCRIPT_FILE)

    fun importScript(context: Context, uri: Uri, displayName: String) {
        val target = scriptFile(context)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "$SCRIPT_FILE.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot open selected file")
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        target.setReadable(true, true)
        target.setExecutable(true, true)
        val current = read(context)
        write(context, current.copy(scriptName = displayName, rootScriptPath = ""))
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        scriptFile(context).delete()
        File(context.filesDir, SCRIPT_DIRECTORY).delete()
    }

    fun logFile(context: Context): File = File(context.filesDir, "$SCRIPT_DIRECTORY/events.log")
}
