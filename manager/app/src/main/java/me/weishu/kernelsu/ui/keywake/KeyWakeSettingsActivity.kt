package me.weishu.kernelsu.ui.keywake

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KeyWakeSettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("night_key_wake", MODE_PRIVATE) }
    private lateinit var keyInput: EditText
    private lateinit var targetSpinner: Spinner
    private lateinit var baiduOnly: CheckBox
    private lateinit var logView: TextView
    private var packages = emptyList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packages = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.filter { it != packageName }.distinct().sorted()
        setContentView(buildUi())
        handleWakeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWakeIntent(intent)
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
            setBackgroundColor(Color.rgb(7, 11, 20))
        }
        root.addView(text("密钥唤醒应用", 26f, Color.WHITE))
        root.addView(text("通过显式 night://wake?key=... 链接触发；不会读取浏览器输入。", 14f, Color.LTGRAY))
        keyInput = EditText(this).apply { hint = "输入自定义密钥"; setText(prefs.getString("key", "")); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        root.addView(keyInput, wide())
        root.addView(text("目标应用选择", 16f, Color.WHITE))
        targetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@KeyWakeSettingsActivity, android.R.layout.simple_spinner_dropdown_item, packages)
            val saved = prefs.getString("target", "")
            setSelection(packages.indexOf(saved).coerceAtLeast(0))
        }
        root.addView(targetSpinner, wide())
        baiduOnly = CheckBox(this).apply {
            text = "仅百度浏览器触发（依据系统提供的来源信息）"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("baidu_only", false)
        }
        root.addView(baiduOnly, wide())
        root.addView(button("保存设置") {
            prefs.edit().putString("key", keyInput.text.toString().trim())
                .putString("target", packages.getOrNull(targetSpinner.selectedItemPosition).orEmpty())
                .putBoolean("baidu_only", baiduOnly.isChecked).apply()
            appendLog("设置已保存")
        })
        root.addView(button("复制测试链接") {
            val link = "night://wake?key=${Uri.encode(keyInput.text.toString().trim())}"
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Night wake link", link))
            Toast.makeText(this, "测试链接已复制", Toast.LENGTH_SHORT).show()
        })
        root.addView(button("手动隐藏目标应用（需确认）") { confirmManualHide() })
        root.addView(text("状态日志", 17f, Color.WHITE))
        logView = text(prefs.getString("log", "暂无记录") ?: "暂无记录", 13f, Color.LTGRAY).apply { setTextIsSelectable(true) }
        root.addView(logView, wide())
        return ScrollView(this).apply { addView(root) }
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW || intent.data?.scheme != "night") return
        val supplied = intent.data?.getQueryParameter("key").orEmpty()
        val expected = prefs.getString("key", "").orEmpty()
        if (expected.isBlank() || supplied != expected) { appendLog("密钥匹配失败"); return }
        if (prefs.getBoolean("baidu_only", false) && !isBaiduSource(intent)) { appendLog("来源不是百度浏览器，已拒绝"); return }
        val target = prefs.getString("target", "").orEmpty()
        val launch = packageManager.getLaunchIntentForPackage(target)
        if (launch == null) { appendLog("目标应用不可启动：$target"); return }
        appendLog("密钥匹配成功：$target")
        AlertDialog.Builder(this).setTitle("打开目标应用？").setMessage(target)
            .setNegativeButton("取消", null).setPositiveButton("打开") { _, _ -> startActivity(launch) }.show()
    }

    private fun isBaiduSource(intent: Intent): Boolean {
        val referrer = intent.getParcelableExtra<Uri>(Intent.EXTRA_REFERRER)?.toString().orEmpty()
        val source = listOf(referrer, callingPackage.orEmpty()).joinToString("|").lowercase(Locale.ROOT)
        return source.contains("baidu") || source.contains("baiduboxapp")
    }

    private fun confirmManualHide() {
        val target = packages.getOrNull(targetSpinner.selectedItemPosition).orEmpty()
        AlertDialog.Builder(this).setTitle("确认隐藏应用").setMessage("将执行 pm disable-user --user 0 $target。可在系统设置中重新启用。")
            .setNegativeButton("取消", null).setPositiveButton("确认隐藏") { _, _ ->
                Thread {
                    val result = runCatching { ProcessBuilder("su", "-c", "pm disable-user --user 0 $target").redirectErrorStream(true).start().run { inputStream.bufferedReader().readText(); waitFor() } }.getOrDefault(-1)
                    runOnUiThread { appendLog("手动隐藏 $target：退出代码 $result") }
                }.start()
            }.show()
    }

    private fun appendLog(message: String) {
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}  $message"
        val log = (prefs.getString("log", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList().takeLast(19) + line).joinToString("\n")
        prefs.edit().putString("log", log).apply()
        if (::logView.isInitialized) logView.text = log
    }
    private fun text(value: String, size: Float, color: Int) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setPadding(0, 12, 0, 12) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
