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
import android.util.Base64
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import me.weishu.kernelsu.ui.util.getRootShell
import me.weishu.kernelsu.ui.util.getRootShellWithRetry
import java.io.File
import java.util.Locale

object RootAppMonitor {
    private const val PREFS = "night_root_app_monitor"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PACKAGE = "package"
    private const val KEY_FOREGROUND_NAME = "foreground_name"
    private const val KEY_BACKGROUND_NAME = "background_name"
    private const val ROOT_DIR = "/data/adb/night_app_monitor"
    private const val WORKER = "$ROOT_DIR/watcher.sh"
    private const val BOOT = "/data/adb/service.d/night_app_monitor.sh"
    private const val PID = "$ROOT_DIR/watcher.pid"
    private const val STATUS = "$ROOT_DIR/status"
    private const val LOG = "$ROOT_DIR/events.log"
    private const val START_LOG = "$ROOT_DIR/startup.log"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun targetPackage(context: Context) = prefs(context).getString(KEY_PACKAGE, "").orEmpty()
    fun foregroundName(context: Context) = prefs(context).getString(KEY_FOREGROUND_NAME, "").orEmpty()
    fun backgroundName(context: Context) = prefs(context).getString(KEY_BACKGROUND_NAME, "").orEmpty()
    fun foregroundFile(context: Context) = File(context.filesDir, "night_app_monitor/foreground.sh")
    fun backgroundFile(context: Context) = File(context.filesDir, "night_app_monitor/background.sh")

    fun save(context: Context, enabled: Boolean, packageName: String) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).putString(KEY_PACKAGE, packageName).apply()
    }

    fun saveScriptName(context: Context, foreground: Boolean, name: String) {
        prefs(context).edit().putString(if (foreground) KEY_FOREGROUND_NAME else KEY_BACKGROUND_NAME, name).apply()
    }

    fun clearScript(context: Context, foreground: Boolean) {
        (if (foreground) foregroundFile(context) else backgroundFile(context)).delete()
        saveScriptName(context, foreground, "")
    }

    private fun b64(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun stopCommand(removeBoot: Boolean = false): String = buildString {
        append("if [ -f '$PID' ]; then p=\$(cat '$PID' 2>/dev/null); [ -n \"\$p\" ] && kill \"\$p\" 2>/dev/null; fi; ")
        append("rm -f '$PID'; ")
        if (removeBoot) append("rm -f '$BOOT'; ")
    }

    fun apply(context: Context): Result<String> = runCatching {
        val shell = getRootShellWithRetry()
        check(shell.isRoot) { "Night 尚未获得 Root 权限" }
        val packageName = targetPackage(context)
        if (!enabled(context)) {
            check(shell.newJob().add(stopCommand(true)).exec().isSuccess) { "停止 Root 监听失败" }
            return@runCatching "Root 监听已关闭"
        }
        check(packageName.matches(Regex("[A-Za-z0-9_.]{3,255}"))) { "请选择监听目标应用" }
        val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
        val foreground = foregroundFile(context).takeIf { it.isFile && it.length() > 0 }
        val background = backgroundFile(context).takeIf { it.isFile && it.length() > 0 }
        check(foreground != null || background != null) { "请至少选择一个前台或后台脚本" }

        val workerText = """#!/system/bin/sh
dir='$ROOT_DIR'
pidfile='$PID'
status='$STATUS'
log='$LOG'
pkg='$packageName'
uid='$uid'
fg='$ROOT_DIR/foreground.sh'
bg='$ROOT_DIR/background.sh'
mkdir -p "${'$'}dir"
if [ -f "${'$'}pidfile" ]; then
  old="${'$'}(cat "${'$'}pidfile" 2>/dev/null)"
  [ -n "${'$'}old" ] && kill -0 "${'$'}old" 2>/dev/null && exit 0
fi
echo ${'$'}${'$'} > "${'$'}pidfile"
trap 'rm -f "${'$'}pidfile"; echo stopped > "${'$'}status"' EXIT INT TERM
is_foreground() {
  state="${'$'}(/system/bin/cmd activity get-uid-state "${'$'}uid" 2>/dev/null | /system/bin/toybox grep -oE '[0-9]+' | /system/bin/toybox head -n 1)"
  [ "${'$'}state" = "2" ] && return 0
  /system/bin/dumpsys activity activities 2>/dev/null | /system/bin/toybox grep -E 'mResumedActivity|topResumedActivity' | /system/bin/toybox grep -Fq "${'$'}pkg/"
}
if is_foreground; then last=1; else last=0; fi
candidate="${'$'}last"
stable=0
echo "running:${'$'}pkg:${'$'}last" > "${'$'}status"
while true; do
  if is_foreground; then now=1; else now=0; fi
  if [ "${'$'}now" = "${'$'}candidate" ]; then stable=${'$'}((stable+1)); else candidate="${'$'}now"; stable=1; fi
  if [ "${'$'}stable" -ge 2 ] && [ "${'$'}candidate" != "${'$'}last" ]; then
    last="${'$'}candidate"
    stamp="${'$'}(/system/bin/date '+%Y-%m-%d %H:%M:%S')"
    if [ "${'$'}last" = "1" ]; then event=foreground; script="${'$'}fg"; else event=background; script="${'$'}bg"; fi
    echo "${'$'}stamp ${'$'}event ${'$'}pkg" >> "${'$'}log"
    echo "running:${'$'}pkg:${'$'}event:${'$'}stamp" > "${'$'}status"
    if [ -s "${'$'}script" ]; then
      /system/bin/sh "${'$'}script" >> "${'$'}log" 2>&1 &
    fi
  fi
  /system/bin/sleep 0.4
done
""".trimIndent()
        val bootText = """#!/system/bin/sh
mkdir -p '$ROOT_DIR'
if [ -f '$PID' ]; then p="${'$'}(cat '$PID' 2>/dev/null)"; [ -n "${'$'}p" ] && kill "${'$'}p" 2>/dev/null; fi
rm -f '$PID'
: > '$START_LOG'
/system/bin/toybox setsid /system/bin/sh '$WORKER' </dev/null >>'$START_LOG' 2>&1 &
starter=${'$'}!
/system/bin/sleep 1
if [ -f '$PID' ]; then
  p="${'$'}(cat '$PID' 2>/dev/null)"
  [ -n "${'$'}p" ] && kill -0 "${'$'}p" 2>/dev/null && exit 0
fi
kill "${'$'}starter" 2>/dev/null
rm -f '$PID'
echo 'setsid unavailable; using plain background launch' >> '$START_LOG'
/system/bin/sh '$WORKER' </dev/null >>'$START_LOG' 2>&1 &
starter=${'$'}!
/system/bin/sleep 1
if [ -f '$PID' ]; then
  p="${'$'}(cat '$PID' 2>/dev/null)"
  [ -n "${'$'}p" ] && kill -0 "${'$'}p" 2>/dev/null && exit 0
fi
kill "${'$'}starter" 2>/dev/null
echo launch_failed > '$STATUS'
exit 1
""".trimIndent()

        val commands = buildString {
            append(stopCommand())
            append("mkdir -p '$ROOT_DIR' /data/adb/service.d; ")
            append("echo '${b64(workerText.toByteArray())}' | /system/bin/toybox base64 -d > '$WORKER'; ")
            append("echo '${b64(bootText.toByteArray())}' | /system/bin/toybox base64 -d > '$BOOT'; ")
            if (foreground != null) append("/system/bin/toybox cp ${shellQuote(foreground.absolutePath)} '$ROOT_DIR/foreground.sh'; ")
            else append("rm -f '$ROOT_DIR/foreground.sh'; ")
            if (background != null) append("/system/bin/toybox cp ${shellQuote(background.absolutePath)} '$ROOT_DIR/background.sh'; ")
            else append("rm -f '$ROOT_DIR/background.sh'; ")
            append("chmod 0700 '$WORKER' '$BOOT' '$ROOT_DIR'/*.sh 2>/dev/null; ")
            append("/system/bin/sh '$BOOT'; ")
            append("i=0; while [ \$i -lt 10 ]; do p=\$(cat '$PID' 2>/dev/null); [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null && exit 0; i=\$((i+1)); /system/bin/sleep 1; done; ")
            append("echo '--- startup log ---' >&2; cat '$START_LOG' >&2 2>/dev/null; echo '--- status ---' >&2; cat '$STATUS' >&2 2>/dev/null; exit 1")
        }
        val stdout = arrayListOf<String>()
        val stderr = arrayListOf<String>()
        val launch = shell.newJob().add(commands).to(stdout, stderr).exec()
        check(launch.isSuccess) {
            val detail = (stderr + stdout).takeLast(6).joinToString(" · ").ifBlank { "未返回详细日志" }
            "Root 守护启动失败：$detail"
        }
        "Root 监听已运行：$packageName"
    }

    fun status(): String {
        val out = arrayListOf<String>()
        val result = getRootShell().newJob().add("cat '$STATUS' 2>/dev/null || echo stopped").to(out, null).exec()
        return if (result.isSuccess) out.lastOrNull().orEmpty().ifBlank { "状态未知" } else "无法读取 Root 监听状态"
    }
}

class RootAppMonitorSettingsActivity : Activity() {
    private lateinit var enabled: CheckBox
    private lateinit var appSpinner: Spinner
    private lateinit var foregroundLabel: TextView
    private lateinit var backgroundLabel: TextView
    private lateinit var statusLabel: TextView
    private var appPackages = emptyList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val foreground = requestCode == REQUEST_FOREGROUND
        if (!foreground && requestCode != REQUEST_BACKGROUND) return
        runCatching { importScript(data.data!!, foreground) }
            .onSuccess { refreshScriptLabels(); Toast.makeText(this, "脚本已导入", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "导入失败：${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun importScript(uri: Uri, foreground: Boolean) {
        val target = if (foreground) RootAppMonitor.foregroundFile(this) else RootAppMonitor.backgroundFile(this)
        target.parentFile?.mkdirs()
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取文件" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        require(target.length() > 0) { "脚本不能为空" }
        RootAppMonitor.saveScriptName(this, foreground, displayName(uri))
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "script.sh"
        }
        return uri.lastPathSegment ?: "script.sh"
    }

    private fun chooseScript(requestCode: Int) {
        AlertDialog.Builder(this)
            .setTitle("选择脚本来源")
            .setItems(arrayOf("浏览 Root 根目录", "使用系统文件选择器")) { _, which ->
                if (which == 0) {
                    browseRoot("/", requestCode == REQUEST_FOREGROUND)
                } else {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }, requestCode)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private data class RootEntry(val path: String, val directory: Boolean)

    private fun browseRoot(path: String, foreground: Boolean) {
        Toast.makeText(this, "正在读取 $path", Toast.LENGTH_SHORT).show()
        Thread {
            val result = readRootDirectory(path)
            runOnUiThread {
                result.onSuccess { entries -> showRootDirectory(path, foreground, entries) }
                    .onFailure { Toast.makeText(this, "读取 Root 目录失败：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun readRootDirectory(path: String): Result<List<RootEntry>> = runCatching {
        val shell = getRootShellWithRetry()
        check(shell.isRoot) { "Night 尚未获得 Root 权限" }
        val output = arrayListOf<String>()
        val quoted = shellQuote(path)
        val command = """
            dir=$quoted
            [ -d "${'$'}dir" ] || exit 2
            for p in "${'$'}dir"/* "${'$'}dir"/.[!.]* "${'$'}dir"/..?*; do
              [ -e "${'$'}p" ] || continue
              if [ -d "${'$'}p" ]; then t=d; elif [ -f "${'$'}p" ]; then t=f; else continue; fi
              n="${'$'}(printf %s "${'$'}p" | /system/bin/toybox base64 | /system/bin/toybox tr -d '\n')"
              printf '%s:%s\n' "${'$'}t" "${'$'}n"
            done
        """.trimIndent()
        check(shell.newJob().add(command).to(output, null).exec().isSuccess) { "目录不存在或没有读取权限" }
        output.mapNotNull { line ->
            val split = line.indexOf(':')
            if (split != 1) return@mapNotNull null
            val decoded = runCatching {
                String(Base64.decode(line.substring(split + 1), Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: return@mapNotNull null
            RootEntry(decoded.replace(Regex("^//+"), "/"), line[0] == 'd')
        }.sortedWith(compareBy<RootEntry> { !it.directory }.thenBy { File(it.path).name.lowercase(Locale.ROOT) })
    }

    private fun showRootDirectory(path: String, foreground: Boolean, entries: List<RootEntry>) {
        val visible = buildList {
            if (path != "/") add(RootEntry(File(path).parent ?: "/", true))
            addAll(entries)
        }
        val labels = visible.mapIndexed { index, entry ->
            if (path != "/" && index == 0) "↰  返回上级目录"
            else (if (entry.directory) "📁  " else "📄  ") + File(entry.path).name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Root：$path")
            .setItems(labels) { _, which ->
                val entry = visible[which]
                if (entry.directory) browseRoot(entry.path, foreground)
                else confirmRootScript(entry.path, foreground)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRootScript(path: String, foreground: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("导入 Root 脚本")
            .setMessage(path)
            .setPositiveButton("导入") { _, _ -> importRootScript(path, foreground) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importRootScript(path: String, foreground: Boolean) {
        Thread {
            val result = runCatching {
                val shell = getRootShellWithRetry()
                check(shell.isRoot) { "Night 尚未获得 Root 权限" }
                val target = if (foreground) RootAppMonitor.foregroundFile(this) else RootAppMonitor.backgroundFile(this)
                target.parentFile?.mkdirs()
                target.writeBytes(byteArrayOf(10))
                val command = "p=${shellQuote(path)}; [ -s \"${'$'}p\" ] || exit 2; " +
                    "/system/bin/toybox cp \"${'$'}p\" ${shellQuote(target.absolutePath)} && " +
                    "chown ${applicationInfo.uid}:${applicationInfo.uid} ${shellQuote(target.absolutePath)} && " +
                    "chmod 0600 ${shellQuote(target.absolutePath)}"
                check(shell.newJob().add(command).exec().isSuccess && target.length() > 0) { "脚本不存在、为空或无法读取" }
                RootAppMonitor.saveScriptName(this, foreground, path)
            }
            runOnUiThread {
                result.onSuccess {
                    refreshScriptLabels()
                    Toast.makeText(this, "Root 脚本已导入", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "导入 Root 脚本失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(36))
            setBackgroundColor(Color.rgb(7, 11, 20))
        }
        root.addView(label("Root 应用监听", 27f, Color.WHITE, true))
        root.addView(label("监听指定应用进入前台或后台，并以 Root 权限执行你选择的脚本。守护进程不依赖 Night 保持后台。", 15f, Color.rgb(185, 195, 214)))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.rgb(20, 29, 49), 24f, Color.rgb(73, 93, 130))
        }
        enabled = CheckBox(this).apply {
            text = "启用 Root 监听"
            textSize = 17f
            setTextColor(Color.WHITE)
            isChecked = RootAppMonitor.enabled(this@RootAppMonitorSettingsActivity)
        }
        panel.addView(enabled)
        panel.addView(label("监听目标应用", 14f, Color.rgb(185, 195, 214)))
        val choices = loadAppChoices()
        appPackages = choices.map { it.second }
        appSpinner = Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(this@RootAppMonitorSettingsActivity, android.R.layout.simple_spinner_dropdown_item, choices.map { it.first }) {
                override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup) =
                    (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Color.WHITE); setPadding(dp(14), dp(12), dp(14), dp(12)) }
                override fun getDropDownView(position: Int, convertView: android.view.View?, parent: ViewGroup) =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply { setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(20, 29, 49)); setPadding(dp(14), dp(14), dp(14), dp(14)) }
            }
            background = rounded(Color.rgb(10, 16, 29), 18f, Color.rgb(89, 110, 151))
            setSelection(appPackages.indexOf(RootAppMonitor.targetPackage(this@RootAppMonitorSettingsActivity)).coerceAtLeast(0))
        }
        panel.addView(appSpinner, wide().apply { topMargin = dp(8) })
        foregroundLabel = label("", 14f, Color.rgb(155, 173, 207))
        panel.addView(foregroundLabel, wide().apply { topMargin = dp(16) })
        panel.addView(button("选择进入前台脚本") { chooseScript(REQUEST_FOREGROUND) })
        panel.addView(button("清除前台脚本") { RootAppMonitor.clearScript(this, true); refreshScriptLabels() })
        backgroundLabel = label("", 14f, Color.rgb(155, 173, 207))
        panel.addView(backgroundLabel, wide().apply { topMargin = dp(16) })
        panel.addView(button("选择进入后台脚本") { chooseScript(REQUEST_BACKGROUND) })
        panel.addView(button("清除后台脚本") { RootAppMonitor.clearScript(this, false); refreshScriptLabels() })
        statusLabel = label("监听状态：尚未检查", 14f, Color.rgb(111, 224, 174), true).apply { setPadding(0, dp(16), 0, 0) }
        panel.addView(statusLabel)
        root.addView(panel, wide().apply { topMargin = dp(24) })

        root.addView(button("保存并应用") {
            val target = appPackages.getOrNull(appSpinner.selectedItemPosition).orEmpty()
            if (enabled.isChecked && target.isBlank()) {
                Toast.makeText(this, "请选择监听目标应用", Toast.LENGTH_LONG).show()
                return@button
            }
            RootAppMonitor.save(this, enabled.isChecked, target)
            statusLabel.text = "正在应用 Root 配置…"
            Thread {
                val result = RootAppMonitor.apply(this)
                runOnUiThread {
                    statusLabel.text = result.fold({ it }, { "启动失败：${it.message}" })
                    Toast.makeText(this, result.fold({ "设置已应用" }, { "应用失败" }), Toast.LENGTH_LONG).show()
                }
            }.start()
        }, wide(dp(58)).apply { topMargin = dp(18) })
        root.addView(button("刷新监听状态") {
            Thread { val state = RootAppMonitor.status(); runOnUiThread { statusLabel.text = "监听状态：$state" } }.start()
        }, wide(dp(58)).apply { topMargin = dp(12) })
        root.addView(label("只会在前台/后台状态发生切换时各执行一次。脚本由你主动选择，并以 Root 权限运行；请只选择可信脚本。", 13f, Color.rgb(145, 158, 183)).apply { setPadding(0, dp(18), 0, 0) })
        refreshScriptLabels()
        return ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.rgb(7, 11, 20)); addView(root) }
    }

    private fun loadAppChoices(): List<Pair<String, String>> {
        return packageManager.getInstalledApplications(0).asSequence()
            .filter { it.enabled && it.packageName != packageName }
            .map {
                val label = it.loadLabel(packageManager).toString().trim().ifBlank { it.packageName }
                "$label  ·  ${it.packageName}" to it.packageName
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(Locale.ROOT) }
            .toList()
    }

    private fun refreshScriptLabels() {
        if (::foregroundLabel.isInitialized) foregroundLabel.text = "进入前台：${RootAppMonitor.foregroundName(this).ifBlank { "未选择" }}"
        if (::backgroundLabel.isInitialized) backgroundLabel.text = "进入后台：${RootAppMonitor.backgroundName(this).ifBlank { "未选择" }}"
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(0f, 1.15f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 15f; isAllCaps = false; setTextColor(Color.WHITE)
        background = rounded(Color.rgb(83, 87, 190), 22f, Color.rgb(130, 155, 244)); setOnClickListener { action() }
    }
    private fun rounded(fill: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radius.toInt()).toFloat(); setStroke(dp(1), stroke)
    }
    private fun wide(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_FOREGROUND = 7101
        private const val REQUEST_BACKGROUND = 7102
    }
}
