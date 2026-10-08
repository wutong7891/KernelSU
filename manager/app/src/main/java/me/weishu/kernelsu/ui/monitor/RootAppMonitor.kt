package me.weishu.kernelsu.ui.monitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.File
import java.util.Locale

object RootAppMonitor {
    private const val PREFS = "night_foreground_listener"
    private const val ROOT_DIR = "/data/adb/night_foreground_listener"
    private const val CONTROL = "$ROOT_DIR/bin/control.sh"
    private const val DEFAULT_SCRIPT = "$ROOT_DIR/scripts/target.sh"
    private const val BOOT = "/data/adb/service.d/night_foreground_listener.sh"

    data class Config(
        val enabled: Boolean,
        val packageName: String,
        val scriptPath: String,
        val preInput: String,
        val interval: Int,
        val cooldown: Int,
    )

    data class RootEntry(val path: String, val directory: Boolean)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) = Config(
        enabled = prefs(context).getBoolean("enabled", false),
        packageName = prefs(context).getString("package", "").orEmpty(),
        scriptPath = prefs(context).getString("script", DEFAULT_SCRIPT).orEmpty().ifBlank { DEFAULT_SCRIPT },
        preInput = prefs(context).getString("preinput", "").orEmpty(),
        interval = prefs(context).getInt("interval", 2),
        cooldown = prefs(context).getInt("cooldown", 2),
    )

    fun save(context: Context, config: Config) {
        prefs(context).edit()
            .putBoolean("enabled", config.enabled)
            .putString("package", config.packageName)
            .putString("script", config.scriptPath)
            .putString("preinput", config.preInput)
            .putInt("interval", config.interval)
            .putInt("cooldown", config.cooldown)
            .apply()
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun runRoot(command: String): Result<String> = runCatching {
        val shell = getRootShell()
        check(shell.isRoot) { "Night 尚未获得 Root 权限" }
        val output = arrayListOf<String>()
        val errors = arrayListOf<String>()
        val result = shell.newJob().add(command).to(output, errors).exec()
        check(result.isSuccess) { (errors + output).joinToString("\n").ifBlank { "Root 命令执行失败" } }
        (output + errors).joinToString("\n").trim()
    }

    private fun asset(context: Context, name: String) =
        context.assets.open("foreground_listener/$name").use { it.readBytes() }

    private fun installCommand(context: Context): String {
        val files = listOf("common.sh", "watcher.sh", "control.sh", "target.sh")
        val writes = files.joinToString("\n") { name ->
            val destination = if (name == "target.sh") "$ROOT_DIR/scripts/$name" else "$ROOT_DIR/bin/$name"
            "printf %s ${shellQuote(b64(asset(context, name)))} | /system/bin/toybox base64 -d > ${shellQuote(destination)}"
        }
        val boot = """#!/system/bin/sh
ROOT='$ROOT_DIR'
PID="${'$'}ROOT/watcher.pid"
mkdir -p "${'$'}ROOT/config" "${'$'}ROOT/logs"
if [ -f "${'$'}PID" ]; then
  p="${'$'}(cat "${'$'}PID" 2>/dev/null)"
  if [ -n "${'$'}p" ] && [ -r "/proc/${'$'}p/cmdline" ] && tr '\000' ' ' < "/proc/${'$'}p/cmdline" | grep -F "${'$'}ROOT/bin/watcher.sh" >/dev/null 2>&1; then
    kill "${'$'}p" 2>/dev/null
  fi
fi
rm -f "${'$'}PID" "${'$'}ROOT/watcher.lock/pid"
rmdir "${'$'}ROOT/watcher.lock" 2>/dev/null
/system/bin/toybox setsid /system/bin/sh "${'$'}ROOT/bin/watcher.sh" </dev/null >/dev/null 2>&1 &
exit 0
""".trimIndent()
        return """
            mkdir -p '$ROOT_DIR/bin' '$ROOT_DIR/scripts' '$ROOT_DIR/config' '$ROOT_DIR/logs' /data/adb/service.d
            $writes
            printf %s ${shellQuote(b64(boot.toByteArray()))} | /system/bin/toybox base64 -d > '$BOOT'
            chmod 0700 '$ROOT_DIR' '$ROOT_DIR/bin/'*.sh '$ROOT_DIR/scripts/'*.sh '$BOOT'
            if [ -f /data/adb/night_app_monitor/watcher.pid ]; then
              old="${'$'}(cat /data/adb/night_app_monitor/watcher.pid 2>/dev/null)"
              [ -n "${'$'}old" ] && kill "${'$'}old" 2>/dev/null
            fi
            rm -f /data/adb/service.d/night_app_monitor.sh
        """.trimIndent()
    }

    fun apply(context: Context, config: Config): Result<String> = runCatching {
        check(config.scriptPath.startsWith('/')) { "脚本路径必须从根目录 / 开始" }
        check(config.interval in 1..60) { "检测间隔必须为 1 到 60 秒" }
        check(config.cooldown in 0..3600) { "冷却时间必须为 0 到 3600 秒" }
        if (config.enabled) {
            check(config.packageName.matches(Regex("[A-Za-z0-9_.]{3,255}"))) { "请选择监听目标应用" }
        }
        save(context, config)
        val preInput = b64(config.preInput.toByteArray())
        val configure = if (config.enabled) {
            "'$CONTROL' configure ${shellQuote(config.packageName)} ${shellQuote(config.scriptPath)} ${config.interval} 1 ${config.cooldown}"
        } else {
            "printf '0\\n' > '$ROOT_DIR/config/enabled'"
        }
        runRoot(
            """
                ${installCommand(context)}
                printf %s ${shellQuote(preInput)} | /system/bin/toybox base64 -d > '$ROOT_DIR/config/preinput'
                chmod 0600 '$ROOT_DIR/config/preinput'
                $configure
                /system/bin/sh '$BOOT'
            """.trimIndent(),
        ).getOrThrow()
        if (config.enabled) "前台监听服务已运行：${config.packageName}" else "前台监听已关闭（服务保持待命）"
    }

    private fun control(context: Context, arguments: String): Result<String> = runCatching {
        if (runRoot("[ -x '$CONTROL' ]").isFailure) {
            runRoot("${installCommand(context)}\n/system/bin/sh '$BOOT'").getOrThrow()
        }
        runRoot("'$CONTROL' $arguments").getOrThrow()
    }

    fun status(context: Context) = control(context, "status")
    fun openApp(context: Context) = control(context, "open")
    fun runScript(context: Context) = control(context, "run")
    fun logs(context: Context) = control(context, "log 200")
    fun clearLogs(context: Context) = control(context, "clear-log")
    fun executeConsole(command: String) = runRoot("cd / && $command")

    fun listDirectory(path: String): Result<List<RootEntry>> = runCatching {
        check(path.startsWith('/')) { "目录必须从 / 开始" }
        val output = runRoot(
            """
                dir=${shellQuote(path)}
                [ -d "${'$'}dir" ] || { echo '目录不存在' >&2; exit 2; }
                find "${'$'}dir" -mindepth 1 -maxdepth 1 -print 2>/dev/null | sort | head -n 500 | while IFS= read -r item; do
                  [ -d "${'$'}item" ] && kind=d || kind=f
                  encoded="${'$'}(printf %s "${'$'}item" | /system/bin/toybox base64 | tr -d '\n')"
                  printf '%s:%s\n' "${'$'}kind" "${'$'}encoded"
                done
            """.trimIndent(),
        ).getOrThrow()
        output.lineSequence().mapNotNull { line ->
            if (line.length < 3 || line[1] != ':') return@mapNotNull null
            val decoded = runCatching { String(Base64.decode(line.drop(2), Base64.DEFAULT)) }.getOrNull()
                ?: return@mapNotNull null
            RootEntry(decoded, line[0] == 'd')
        }.sortedWith(compareBy<RootEntry> { !it.directory }.thenBy { it.path.lowercase(Locale.ROOT) }).toList()
    }

    fun importDocument(context: Context, uri: Uri, displayName: String): Result<String> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取文件" }
            input.readBytes()
        }
        check(bytes.isNotEmpty()) { "脚本不能为空" }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "selected.sh" }
        val path = "$ROOT_DIR/imported/$safeName"
        runRoot(
            "mkdir -p '$ROOT_DIR/imported'; printf %s ${shellQuote(b64(bytes))} | /system/bin/toybox base64 -d > ${shellQuote(path)}; chmod 0700 ${shellQuote(path)}",
        ).getOrThrow()
        path
    }
}

class RootAppMonitorSettingsActivity : Activity() {
    private lateinit var enabled: CheckBox
    private lateinit var appSpinner: Spinner
    private lateinit var scriptPath: EditText
    private lateinit var preInput: EditText
    private lateinit var interval: EditText
    private lateinit var cooldown: EditText
    private lateinit var statusLabel: TextView
    private lateinit var consoleInput: EditText
    private lateinit var consoleOutput: TextView
    private lateinit var logOutput: TextView
    private var appPackages = emptyList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCRIPT || resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        runAsync("正在导入脚本…", {
            RootAppMonitor.importDocument(this, uri, displayName(uri))
        }) { result ->
            scriptPath.setText(result)
            Toast.makeText(this, "脚本已导入 Root 存储", Toast.LENGTH_SHORT).show()
        }
    }

    private fun currentConfig(): RootAppMonitor.Config {
        val target = appPackages.getOrNull(appSpinner.selectedItemPosition).orEmpty()
        return RootAppMonitor.Config(
            enabled = enabled.isChecked,
            packageName = target,
            scriptPath = scriptPath.text.toString().trim(),
            preInput = preInput.text.toString(),
            interval = interval.text.toString().toIntOrNull() ?: 2,
            cooldown = cooldown.text.toString().toIntOrNull() ?: 2,
        )
    }

    private fun applyThen(action: (() -> Result<String>)? = null) {
        val config = currentConfig()
        runAsync("正在应用 Root 配置…", {
            runCatching {
                RootAppMonitor.apply(this, config).getOrThrow()
                action?.invoke()?.getOrThrow()
                    ?: if (config.enabled) "设置已应用，服务正在运行" else "监听已关闭"
            }
        })
    }

    private fun buildUi(): ScrollView {
        val saved = RootAppMonitor.load(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(42), dp(22), dp(38))
            setBackgroundColor(Color.rgb(7, 11, 20))
        }
        root.addView(label("前台脚本监听", 27f, Color.WHITE, true))
        root.addView(label("应用每次进入前台时，以 Root 从 / 目录执行一次脚本。服务由 service.d 独立运行，划掉 Night 后仍会继续监听。", 15f, Color.rgb(185, 195, 214)))

        val settings = panel()
        enabled = CheckBox(this).apply {
            text = "启用前台监听"
            textSize = 17f
            setTextColor(Color.WHITE)
            isChecked = saved.enabled
        }
        settings.addView(enabled)
        settings.addView(label("监听目标应用", 14f, Color.rgb(185, 195, 214)))
        val choices = loadAppChoices()
        appPackages = choices.map { it.second }
        appSpinner = Spinner(this).apply {
            adapter = darkAdapter(choices.map { it.first })
            background = rounded(Color.rgb(10, 16, 29), 18, Color.rgb(89, 110, 151))
            setSelection(appPackages.indexOf(saved.packageName).coerceAtLeast(0))
        }
        settings.addView(appSpinner, wide().apply { topMargin = dp(7) })
        settings.addView(label("Shell 脚本绝对路径", 14f, Color.rgb(185, 195, 214)), wide().apply { topMargin = dp(15) })
        scriptPath = input(saved.scriptPath, "例如 /sdcard/script.sh")
        settings.addView(scriptPath)
        settings.addView(button("浏览 Root 根目录") { browseRoot("/") })
        settings.addView(button("从系统文件选择器导入") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQUEST_SCRIPT)
        })
        settings.addView(label("预输入（每行会依次作为数字、文字或回车输入）", 14f, Color.rgb(185, 195, 214)), wide().apply { topMargin = dp(15) })
        preInput = input(saved.preInput, "例如：\n1\ny\n确认文字\n", multiline = true)
        settings.addView(preInput)
        interval = input(saved.interval.toString(), "检测间隔（秒）", numeric = true)
        cooldown = input(saved.cooldown.toString(), "冷却时间（秒）", numeric = true)
        settings.addView(label("检测间隔（1–60 秒）", 14f, Color.rgb(185, 195, 214)), wide().apply { topMargin = dp(15) })
        settings.addView(interval)
        settings.addView(label("冷却时间（0–3600 秒）", 14f, Color.rgb(185, 195, 214)), wide().apply { topMargin = dp(10) })
        settings.addView(cooldown)
        statusLabel = label("服务状态：尚未检查", 14f, Color.rgb(111, 224, 174), true)
        settings.addView(statusLabel, wide().apply { topMargin = dp(14) })
        root.addView(settings, wide().apply { topMargin = dp(20) })

        root.addView(button("保存并启动服务") { applyThen() }, wide(dp(56)).apply { topMargin = dp(16) })
        root.addView(button("打开目标应用") { applyThen { RootAppMonitor.openApp(this) } })
        root.addView(button("立即执行一次（使用预输入）") { applyThen { RootAppMonitor.runScript(this) } })
        root.addView(button("刷新服务状态") { runAsync("正在读取状态…", { RootAppMonitor.status(this) }) })

        val console = panel()
        console.addView(label("Root 控制台", 20f, Color.WHITE, true))
        console.addView(label("命令默认从根目录 / 执行。", 13f, Color.rgb(155, 173, 207)))
        consoleInput = input("", "输入 Root Shell 命令")
        console.addView(consoleInput)
        console.addView(button("执行命令") {
            val command = consoleInput.text.toString().trim()
            if (command.isBlank()) return@button
            runAsync("正在执行…", { RootAppMonitor.executeConsole(command) }) { consoleOutput.text = it.ifBlank { "命令执行成功（无输出）" } }
        })
        consoleOutput = label("等待命令", 13f, Color.rgb(196, 214, 243)).apply { setTextIsSelectable(true) }
        console.addView(consoleOutput, wide().apply { topMargin = dp(9) })
        root.addView(console, wide().apply { topMargin = dp(20) })

        val logs = panel()
        logs.addView(label("触发日志", 20f, Color.WHITE, true))
        logs.addView(button("刷新日志") { runAsync("正在读取日志…", { RootAppMonitor.logs(this) }) { logOutput.text = it } })
        logs.addView(button("清空日志") { runAsync("正在清空…", { RootAppMonitor.clearLogs(this) }) { logOutput.text = it } })
        logOutput = label("尚未读取", 13f, Color.rgb(196, 214, 243)).apply { setTextIsSelectable(true) }
        logs.addView(logOutput, wide().apply { topMargin = dp(9) })
        root.addView(logs, wide().apply { topMargin = dp(20) })

        root.addView(label("仅检测目标应用进入前台；一次前台会话只执行一次。Root 授权事件不会触发脚本。请只运行可信脚本。", 13f, Color.rgb(145, 158, 183)).apply { setPadding(0, dp(18), 0, 0) })
        return ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.rgb(7, 11, 20)); addView(root) }
    }

    private fun browseRoot(path: String) {
        runAsync("正在读取 $path", { RootAppMonitor.listDirectory(path) }) { entries ->
            val visible = buildList {
                if (path != "/") add(RootAppMonitor.RootEntry(File(path).parent ?: "/", true))
                addAll(entries)
            }
            val labels = visible.mapIndexed { index, item ->
                if (path != "/" && index == 0) "↰ 返回上级目录"
                else (if (item.directory) "📁 " else "📄 ") + File(item.path).name
            }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Root：$path").setItems(labels) { _, index ->
                val selected = visible[index]
                if (selected.directory) browseRoot(selected.path) else scriptPath.setText(selected.path)
            }.setNegativeButton("取消", null).show()
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "selected.sh"
        }
        return uri.lastPathSegment ?: "selected.sh"
    }

    private fun loadAppChoices(): List<Pair<String, String>> = packageManager.getInstalledApplications(0).asSequence()
        .filter { it.enabled && it.packageName != packageName }
        .map {
            val name = it.loadLabel(packageManager).toString().trim().ifBlank { it.packageName }
            "$name  ·  ${it.packageName}" to it.packageName
        }
        .distinctBy { it.second }
        .sortedBy { it.first.lowercase(Locale.ROOT) }
        .toList()

    private fun <T> runAsync(message: String, task: () -> Result<T>, success: (T) -> Unit = {}) {
        statusLabel.text = message
        Thread {
            val result = runCatching { task().getOrThrow() }
            runOnUiThread {
                result.onSuccess {
                    statusLabel.text = if (it is String) it else "操作成功"
                    success(it)
                }.onFailure {
                    statusLabel.text = "操作失败：${it.message}"
                    Toast.makeText(this, "操作失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(17), dp(17), dp(17))
        background = rounded(Color.rgb(20, 29, 49), 22, Color.rgb(73, 93, 130))
    }

    private fun input(value: String, hintValue: String, multiline: Boolean = false, numeric: Boolean = false) = EditText(this).apply {
        setText(value)
        hint = hintValue
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(121, 137, 166))
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = rounded(Color.rgb(10, 16, 29), 16, Color.rgb(89, 110, 151))
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) {
            minLines = 5
            gravity = android.view.Gravity.TOP
        }
    }

    private fun darkAdapter(values: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
            (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Color.WHITE); setPadding(dp(12), dp(11), dp(12), dp(11)) }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) =
            (super.getDropDownView(position, convertView, parent) as TextView).apply { setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(20, 29, 49)); setPadding(dp(12), dp(13), dp(12), dp(13)) }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.14f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 15f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(83, 87, 190), 20, Color.rgb(130, 155, 244))
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun wide(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_SCRIPT = 7101
    }
}
