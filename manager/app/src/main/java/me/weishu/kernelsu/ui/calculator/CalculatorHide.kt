package me.weishu.kernelsu.ui.calculator

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.Executors
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.MainActivity
import me.weishu.kernelsu.ui.util.getRootShell
import me.weishu.kernelsu.ui.util.rootAvailable

object CalculatorHide {
    const val ACTION_STOP = "me.weishu.kernelsu.calculator.STOP_ACCESSIBILITY"
    const val ACTION_RESTORE_LAUNCHER = "me.weishu.kernelsu.calculator.RESTORE_LAUNCHER"
    const val ACTION_START_KEEPALIVE = "com.Night.night.action.START_ACCESSIBILITY_KEEPALIVE"

    private const val PREFS = "night_calculator_accessibility"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGET = "target"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_STATUS = "status"
    private const val KEY_PROMPTED = "accessibility_prompted"
    private const val KEY_LAUNCHER_HIDDEN = "launcher_hidden"
    private const val KEY_AUTO_HIDE_LAUNCHER = "auto_hide_launcher"
    private const val DEFAULT_TARGET = "100"
    private const val RUNTIME_CONFIG = "night_calculator_accessibility.conf"
    private const val WATCHDOG_ASSET = "night-accessibility-watchdog.sh"
    private const val WATCHDOG_SCRIPT = "/data/adb/service.d/99-night-accessibility-watchdog.sh"
    private const val WATCHDOG_MARKER = "/data/adb/night_accessibility_watchdog.enabled"
    private const val WATCHDOG_PID = "/data/adb/night_accessibility_watchdog.pid"
    private const val WATCHDOG_LOCK = "/data/adb/night_accessibility_watchdog.lock"
    // The manager applicationId is com.Night.night, while the manifest
    // namespace remains me.weishu.kernelsu.  Android therefore registers the
    // launcher alias under the namespace-qualified class name.
    private const val LAUNCHER_ALIAS = "me.weishu.kernelsu.NightLauncher"
    @Volatile private var backgroundProtectionRunning = false
    @Volatile private var watchdogConfigRunning = false
    private val launcherVisibilityExecutor = Executors.newSingleThreadExecutor()

    data class RuntimeConfig(
        val enabled: Boolean,
        val target: BigDecimal,
        val targetPackage: String,
    )

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        writeRuntimeConfig(context, enabled, target(context), targetPackage(context))
        if (enabled) applyRootBackgroundProtection(context)
        configureRootWatchdog(context, enabled)
        updateStatus(context, if (enabled) "等待计算器界面变化" else "监听已停止")
    }

    fun target(context: Context): BigDecimal = runCatching {
        BigDecimal(prefs(context).getString(KEY_TARGET, DEFAULT_TARGET) ?: DEFAULT_TARGET)
    }.getOrDefault(BigDecimal(DEFAULT_TARGET))

    fun targetText(context: Context): String = target(context).stripTrailingZeros().toPlainString()

    fun targetPackage(context: Context): String =
        prefs(context).getString(KEY_TARGET_PACKAGE, "").orEmpty()

    fun save(context: Context, target: BigDecimal, targetPackage: String, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_TARGET, target.stripTrailingZeros().toPlainString())
            .putString(KEY_TARGET_PACKAGE, targetPackage)
            .apply()
        writeRuntimeConfig(context, enabled, target, targetPackage)
        if (enabled) applyRootBackgroundProtection(context)
        configureRootWatchdog(context, enabled)
    }

    fun configureRootWatchdog(context: Context, enabled: Boolean) {
        if (enabled) {
            startKeepAlive(context)
        } else {
            context.applicationContext.stopService(
                Intent(context.applicationContext, CalculatorAccessibilityKeepAliveService::class.java),
            )
        }
        if (watchdogConfigRunning) return
        watchdogConfigRunning = true
        val appContext = context.applicationContext
        Thread({
            try {
                var lastFailure = "Root 权限确认失败"
                repeat(8) { attempt ->
                    if (!rootAvailable()) {
                        lastFailure = "Root 权限尚未就绪"
                        if (attempt < 7) Thread.sleep(1_000L)
                        return@repeat
                    }
                    val shell = getRootShell()
                    if (!shell.isRoot) {
                        lastFailure = "Root Shell 不可用"
                        if (attempt < 7) Thread.sleep(1_000L)
                        return@repeat
                    }
                    val command = if (enabled) {
                        val script = appContext.assets.open(WATCHDOG_ASSET).bufferedReader().use { it.readText() }
                        val encoded = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        "mkdir -p /data/adb/service.d; " +
                            "echo '$encoded' | /system/bin/toybox base64 -d > '$WATCHDOG_SCRIPT'; " +
                            "chmod 0700 '$WATCHDOG_SCRIPT'; " +
                            "touch '$WATCHDOG_MARKER'; " +
                            // Clean up daemons left by older builds before
                            // starting the single-instance watchdog.
                            "for pid in \$(ps -A -o PID,ARGS 2>/dev/null | /system/bin/toybox grep -F '$WATCHDOG_SCRIPT' | /system/bin/toybox grep -Fv grep | /system/bin/toybox sed 's/^ *//' | /system/bin/toybox sed 's/ .*//'); do [ -n \"\$pid\" ] && kill \"\$pid\" 2>/dev/null; done; " +
                            "rmdir '$WATCHDOG_LOCK' 2>/dev/null; rm -f '$WATCHDOG_LOCK/pid'; " +
                            "if [ -f '$WATCHDOG_PID' ]; then " +
                            "old_pid=\$(cat '$WATCHDOG_PID' 2>/dev/null); " +
                            "if [ -n \"\$old_pid\" ]; then kill \"\$old_pid\" 2>/dev/null; fi; " +
                            "sleep 1; rm -f '$WATCHDOG_PID'; fi; " +
                            "/system/bin/sh '$WATCHDOG_SCRIPT'; sleep 1; " +
                            "watchdog_pid=\$(cat '$WATCHDOG_PID' 2>/dev/null); " +
                            "[ -n \"\$watchdog_pid\" ] && kill -0 \"\$watchdog_pid\" 2>/dev/null"
                    } else {
                        "rm -f '$WATCHDOG_MARKER'; " +
                            "for pid in \$(ps -A -o PID,ARGS 2>/dev/null | /system/bin/toybox grep -F '$WATCHDOG_SCRIPT' | /system/bin/toybox grep -Fv grep | /system/bin/toybox sed 's/^ *//' | /system/bin/toybox sed 's/ .*//'); do [ -n \"\$pid\" ] && kill \"\$pid\" 2>/dev/null; done; " +
                            "if [ -f '$WATCHDOG_PID' ]; then " +
                            "kill \$(cat '$WATCHDOG_PID') 2>/dev/null; fi; " +
                            "rm -f '$WATCHDOG_PID'; rmdir '$WATCHDOG_LOCK' 2>/dev/null; rm -f '$WATCHDOG_LOCK/pid'"
                    }
                    val result = shell.newJob().add(command).exec()
                    if (result.isSuccess) {
                        updateStatus(
                            appContext,
                            if (enabled) "Root 无后台守护已运行" else "监听已停止",
                        )
                        return@Thread
                    }
                    lastFailure = "Root 守护启动后验证失败"
                    if (attempt < 7) Thread.sleep(1_000L)
                }
                updateStatus(appContext, "$lastFailure，无法启动无障碍守护")
            } finally {
                watchdogConfigRunning = false
            }
        }, "NightAccessibilityWatchdogSetup").start()
    }

    fun startKeepAlive(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, CalculatorAccessibilityKeepAliveService::class.java),
            )
        }
    }

    fun applyRootBackgroundProtection(context: Context) {
        if (backgroundProtectionRunning) return
        backgroundProtectionRunning = true
        val appContext = context.applicationContext
        Thread({
            try {
                if (!rootAvailable()) return@Thread
                val shell = getRootShell()
                if (!shell.isRoot) return@Thread
                val packageName = appContext.packageName
                shell.newJob().add(
                    "user=\$(cmd activity get-current-user 2>/dev/null); " +
                        "case \"\$user\" in ''|*[!0-9]*) user=0;; esac; " +
                        "dumpsys deviceidle whitelist +$packageName >/dev/null 2>&1; " +
                        "cmd appops set --user \"\$user\" $packageName RUN_IN_BACKGROUND allow >/dev/null 2>&1; " +
                        "cmd appops set --user \"\$user\" $packageName RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1; " +
                        "cmd activity set-standby-bucket $packageName active >/dev/null 2>&1; true",
                ).exec()
            } finally {
                backgroundProtectionRunning = false
            }
        }, "NightAccessibilityProtection").start()
    }

    fun runtimeConfig(context: Context): RuntimeConfig {
        val fallback = RuntimeConfig(isEnabled(context), target(context), targetPackage(context))
        val lines = runCatching {
            File(context.filesDir, RUNTIME_CONFIG).readLines(Charsets.UTF_8)
        }.getOrNull() ?: return fallback
        val values = lines.mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
        }.toMap()
        return RuntimeConfig(
            enabled = values["enabled"]?.toBooleanStrictOrNull() ?: fallback.enabled,
            target = values["target"]?.toBigDecimalOrNull() ?: fallback.target,
            targetPackage = values["package"] ?: fallback.targetPackage,
        )
    }

    fun status(context: Context): String =
        prefs(context).getString(KEY_STATUS, "尚未连接无障碍服务").orEmpty()

    fun updateStatus(context: Context, status: String) {
        prefs(context).edit().putString(KEY_STATUS, status).apply()
    }

    fun shouldPromptAccessibility(context: Context): Boolean {
        if (isAccessibilityEnabled(context)) return false
        if (prefs(context).getBoolean(KEY_PROMPTED, false)) return false
        prefs(context).edit().putBoolean(KEY_PROMPTED, true).apply()
        return true
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val component = ComponentName(context, CalculatorAccessibilityService::class.java)
        val expectedLong = component.flattenToString()
        val expectedShort = component.flattenToShortString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any {
            it.equals(expectedLong, ignoreCase = true) || it.equals(expectedShort, ignoreCase = true)
        }
    }

    fun matchesTarget(value: CharSequence?, target: BigDecimal): Boolean {
        val normalized = value?.toString()
            ?.replace('−', '-')
            ?.replace(",", "")
            ?.trim()
            .orEmpty()
        if (normalized.isBlank()) return false
        val candidates = Regex("[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)")
            .findAll(normalized)
            .mapNotNull { runCatching { BigDecimal(it.value) }.getOrNull() }
            .toList()
        return candidates.any { it.compareTo(target) == 0 }
    }

    fun isLauncherVisible(context: Context): Boolean {
        val component = ComponentName(context.packageName, LAUNCHER_ALIAS)
        val disabledByPackageManager = context.packageManager.getComponentEnabledSetting(component) in setOf(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        )
        return !disabledByPackageManager && !prefs(context).getBoolean(KEY_LAUNCHER_HIDDEN, false)
    }

    fun isAutoHideLauncherEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_HIDE_LAUNCHER, false)

    fun setAutoHideLauncherEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_HIDE_LAUNCHER, enabled).apply()
        if (!enabled) {
            launcherVisibilityExecutor.execute {
                setLauncherVisible(context.applicationContext, true)
            }
        }
    }

    fun applyLauncherVisibilityForAppState(context: Context, appInForeground: Boolean) {
        if (!isAutoHideLauncherEnabled(context)) return
        launcherVisibilityExecutor.execute {
            setLauncherVisible(context.applicationContext, appInForeground)
        }
    }

    fun setLauncherVisible(context: Context, visible: Boolean): Boolean {
        val component = ComponentName(context.packageName, LAUNCHER_ALIAS)
        val shellComponent = "${context.packageName}/$LAUNCHER_ALIAS"
        // Change only the launcher alias. Hiding the complete package also
        // disables its accessibility service on Android/ColorOS.
        val pmAction = if (visible) "enable" else "disable"
        val rootConfirmed = runCatching { rootAvailable() }.getOrDefault(false)
        val rootShell = if (rootConfirmed) runCatching { getRootShell() }.getOrNull() else null
        val rootChanged = if (rootConfirmed && rootShell != null) {
            runCatching {
                val result = rootShell.newJob().add(
                    "user=\$(cmd activity get-current-user 2>/dev/null); " +
                        "case \"\$user\" in ''|*[!0-9]*) user=0;; esac; " +
                        "pm $pmAction --user \"\$user\" '$shellComponent' >/dev/null 2>&1 || " +
                        "cmd package $pmAction --user \"\$user\" '$shellComponent' >/dev/null 2>&1 || exit 1; " +
                        "sleep 1",
                ).exec()
                if (!result.isSuccess) return@runCatching false
                val state = context.packageManager.getComponentEnabledSetting(component)
                if (visible) {
                    state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                        state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                } else {
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                        state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                        state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
                }
            }.getOrDefault(false)
        } else {
            false
        }

        // Compatibility fallback for systems where the manager root channel is
        // temporarily unavailable.  Unlike the previous implementation, this
        // path is accepted only after reading the component state back.
        val localChanged = if (!rootChanged) runCatching {
            context.packageManager.setComponentEnabledSetting(
                component,
                if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            val state = context.packageManager.getComponentEnabledSetting(component)
            if (visible) {
                state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
        }.getOrDefault(false) else false

        val changed = rootChanged || localChanged
        if (changed) {
            prefs(context).edit().putBoolean(KEY_LAUNCHER_HIDDEN, !visible).apply()
        }
        updateStatus(
            context,
            when {
                rootChanged && visible -> "已通过 Root 恢复桌面入口"
                rootChanged -> "已通过 Root 隐藏桌面入口"
                localChanged && visible -> "已通过兼容方式恢复桌面入口"
                localChanged -> "已通过兼容方式隐藏桌面入口"
                else -> "桌面入口状态修改失败，请确认 Root 已授权"
            },
        )
        return changed
    }

    private fun writeRuntimeConfig(
        context: Context,
        enabled: Boolean,
        target: BigDecimal,
        targetPackage: String,
    ) {
        val file = File(context.filesDir, RUNTIME_CONFIG)
        val temp = File(context.filesDir, "$RUNTIME_CONFIG.tmp")
        val content = buildString {
            append("enabled=").append(enabled).append('\n')
            append("target=").append(target.stripTrailingZeros().toPlainString()).append('\n')
            append("package=").append(targetPackage.replace("\n", "")).append('\n')
        }
        runCatching {
            temp.writeText(content, Charsets.UTF_8)
            if (!temp.renameTo(file)) file.writeText(content, Charsets.UTF_8)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class CalculatorHideSettingsActivity : Activity() {
    private lateinit var enabledSwitch: Switch
    private lateinit var appSpinner: Spinner
    private lateinit var targetInput: EditText
    private lateinit var statusView: TextView
    private lateinit var launcherSwitch: Switch
    private var appPackages = emptyList<String>()
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusUpdater = object : Runnable {
        override fun run() {
            refreshStatus()
            statusHandler.postDelayed(this, 750L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        CalculatorHide.configureRootWatchdog(this, CalculatorHide.isEnabled(this))
        if (CalculatorHide.shouldPromptAccessibility(this)) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!CalculatorHide.isAccessibilityEnabled(this)) {
                    Toast.makeText(this, "请在列表中开启 Night 计算器监听", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }, 500L)
        }
    }

    override fun onResume() {
        super.onResume()
        statusHandler.removeCallbacks(statusUpdater)
        statusUpdater.run()
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusUpdater)
        super.onPause()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
            setBackgroundColor(Color.rgb(7, 11, 20))
        }
        root.addView(label("系统计算器无障碍监听", 27f, Color.WHITE, true))
        root.addView(label(
            "直接读取所选计算器的可见文字。输入或计算出的结果等于设定数字时打开 Night；不使用 Root 前台检测、截图或服务器 OCR。",
            15f,
            Color.rgb(185, 195, 214),
        ).apply { setPadding(0, dp(8), 0, dp(22)) })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.rgb(20, 29, 49), 24f, Color.rgb(73, 93, 130))
        }
        root.addView(panel, wide())

        enabledSwitch = Switch(this).apply {
            text = "启用计算器监听"
            textSize = 18f
            setTextColor(Color.WHITE)
            isChecked = CalculatorHide.isEnabled(this@CalculatorHideSettingsActivity)
        }
        panel.addView(enabledSwitch, wide())

        panel.addView(label("监听目标应用", 14f, Color.rgb(185, 195, 214)).apply {
            setPadding(0, dp(14), 0, dp(6))
        })
        val choices = loadAppChoices()
        appPackages = choices.map { it.second }
        appSpinner = Spinner(this).apply {
            setPopupBackgroundDrawable(rounded(Color.rgb(20, 29, 49), 12f, Color.rgb(89, 110, 151)))
            adapter = object : ArrayAdapter<String>(
                this@CalculatorHideSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                choices.map { it.first },
            ) {
                override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup) =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(Color.WHITE)
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }

                override fun getDropDownView(position: Int, convertView: android.view.View?, parent: ViewGroup) =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.rgb(20, 29, 49))
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                    }
            }
            background = rounded(Color.rgb(10, 16, 29), 18f, Color.rgb(89, 110, 151))
            val saved = CalculatorHide.targetPackage(this@CalculatorHideSettingsActivity)
            setSelection(appPackages.indexOf(saved).coerceAtLeast(0))
        }
        panel.addView(appSpinner, wide().apply { topMargin = dp(8) })

        panel.addView(label("触发数字", 14f, Color.rgb(185, 195, 214)).apply {
            setPadding(0, dp(14), 0, dp(6))
        })
        targetInput = EditText(this).apply {
            setText(CalculatorHide.targetText(this@CalculatorHideSettingsActivity))
            hint = "例如 100"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.rgb(10, 16, 29), 18f, Color.rgb(89, 110, 151))
        }
        panel.addView(targetInput, wide().apply { topMargin = dp(8) })

        statusView = label("", 14f, Color.rgb(111, 224, 174), true).apply {
            setPadding(0, dp(16), 0, 0)
        }
        panel.addView(statusView, wide())

        root.addView(button("保存并应用") { saveSettings() }, wide(dp(58)).apply { topMargin = dp(18) })
        root.addView(button("打开无障碍设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, wide(dp(58)).apply { topMargin = dp(10) })
        root.addView(button("停止监听") {
            enabledSwitch.isChecked = false
            CalculatorHide.setEnabled(this, false)
            getSystemService(NotificationManager::class.java).cancel(CalculatorAccessibilityService.NOTIFICATION_ID)
            refreshStatus()
        }, wide(dp(58)).apply { topMargin = dp(10) })

        root.addView(label("自动隐藏桌面图标", 20f, Color.WHITE, true).apply {
            setPadding(0, dp(28), 0, dp(8))
        })
        root.addView(label(
            "打开后，进入 Night 时临时恢复桌面入口，退出 Night 时自动通过 Root/ADB 隐藏。不会停用应用和无障碍监听。",
            13f,
            Color.rgb(145, 158, 183),
        ))
        launcherSwitch = Switch(this).apply {
            text = "退出 Night 后自动隐藏"
            textSize = 17f
            setTextColor(Color.WHITE)
            isChecked = CalculatorHide.isAutoHideLauncherEnabled(this@CalculatorHideSettingsActivity)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(17, 27, 48), 18f, Color.rgb(78, 101, 148))
            setOnCheckedChangeListener { _, enabled ->
                CalculatorHide.setAutoHideLauncherEnabled(this@CalculatorHideSettingsActivity, enabled)
                Toast.makeText(
                    this@CalculatorHideSettingsActivity,
                    if (enabled) "已开启，退出 Night 后自动隐藏" else "已关闭，正在恢复桌面入口",
                    Toast.LENGTH_SHORT,
                ).show()
                refreshStatus()
            }
        }
        root.addView(launcherSwitch, wide(dp(58)).apply { topMargin = dp(12) })
        refreshStatus()

        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 11, 20))
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun saveSettings() {
        val target = targetInput.text.toString().trim().toBigDecimalOrNull()
        if (target == null) {
            targetInput.error = "请输入有效数字"
            return
        }
        val selectedPackage = appPackages.getOrNull(appSpinner.selectedItemPosition).orEmpty()
        CalculatorHide.save(this, target, selectedPackage, enabledSwitch.isChecked)
        Toast.makeText(this, "设置已应用", Toast.LENGTH_SHORT).show()
        if (enabledSwitch.isChecked && !CalculatorHide.isAccessibilityEnabled(this)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val access = if (CalculatorHide.isAccessibilityEnabled(this)) "无障碍已授权" else "无障碍未授权"
        statusView.text = "$access\n${CalculatorHide.status(this)}"
        statusView.setTextColor(
            if (CalculatorHide.isAccessibilityEnabled(this)) Color.rgb(111, 224, 174)
            else Color.rgb(255, 194, 103),
        )
        if (::launcherSwitch.isInitialized) {
            val state = if (CalculatorHide.isLauncherVisible(this)) "当前可见" else "当前已隐藏"
            launcherSwitch.text = "退出 Night 后自动隐藏（$state）"
        }
    }

    private fun loadAppChoices(): List<Pair<String, String>> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val choices = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .map { resolve ->
                val label = resolve.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                ("$label  ·  ${resolve.activityInfo.packageName}") to resolve.activityInfo.packageName
            }
            .filter { (label, pkg) ->
                if (pkg == packageName) return@filter false
                val normalized = "$label $pkg".lowercase(Locale.ROOT)
                normalized.contains("计算器") || normalized.contains("计算机") ||
                    normalized.contains("calculator") || normalized.contains("calc")
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(Locale.ROOT) }
        return listOf("自动识别计算器" to "") + choices
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 16f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(83, 87, 190), 24f, Color.rgb(130, 155, 244))
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun wide(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

class CalculatorAccessibilityKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!CalculatorHide.runtimeConfig(this).enabled) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (CalculatorHide.runtimeConfig(this).enabled) {
            CalculatorHide.startKeepAlive(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Night 后台监听", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持已启用的计算器无障碍监听运行"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_logo_vector)
        .setContentTitle("Night 计算器监听")
        .setContentText("后台监听已运行")
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                10,
                Intent(this, CalculatorHideSettingsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setSilent(true)
        .build()

    companion object {
        private const val NOTIFICATION_ID = 4101
        private const val CHANNEL_ID = "night_calculator_keepalive"
    }
}

class CalculatorAccessibilityBootstrapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == CalculatorHide.ACTION_START_KEEPALIVE) {
            CalculatorHide.startKeepAlive(context)
        }
    }
}

class CalculatorAccessibilityService : AccessibilityService() {
    private var lastTriggerAt = 0L
    private var lastPackage = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        CalculatorHide.updateStatus(this, "无障碍服务已连接，等待计算器")
        CalculatorHide.applyRootBackgroundProtection(this)
        createNotificationChannel()
        if (CalculatorHide.runtimeConfig(this).enabled) showStatusNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val config = CalculatorHide.runtimeConfig(this)
        if (!config.enabled) return
        val packageName = event.packageName?.toString().orEmpty()
        if (!isTargetCalculator(packageName, config.targetPackage)) return
        if (lastPackage != packageName) {
            lastPackage = packageName
            CalculatorHide.updateStatus(this, "已识别计算器：$packageName")
        }

        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < 3000L) return
        val root = rootInActiveWindow ?: event.source ?: return
        val target = config.target
        if (!containsTarget(root, target)) return

        lastTriggerAt = now
        CalculatorHide.updateStatus(this, "已匹配 ${target.stripTrailingZeros().toPlainString()}，正在打开 Night")
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
    }

    override fun onInterrupt() {
        CalculatorHide.updateStatus(this, "无障碍服务被系统中断")
    }

    override fun onDestroy() {
        runCatching { stopForeground(true) }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun containsTarget(root: AccessibilityNodeInfo, target: BigDecimal): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val node = queue.removeFirst()
            if (CalculatorHide.matchesTarget(node.text, target) ||
                CalculatorHide.matchesTarget(node.contentDescription, target)
            ) return true
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return false
    }

    private fun isTargetCalculator(packageName: String, selectedPackage: String): Boolean {
        if (packageName.isBlank() || packageName == this.packageName) return false
        if (selectedPackage.isNotBlank()) return packageName == selectedPackage
        return runCatching {
            val label = packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0),
            ).toString().lowercase(Locale.ROOT)
            val normalized = "$label $packageName".lowercase(Locale.ROOT)
            normalized.contains("计算器") || normalized.contains("计算机") ||
                normalized.contains("calculator") || normalized.contains("calc")
        }.getOrDefault(false)
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Night 计算器监听", NotificationManager.IMPORTANCE_LOW).apply {
                description = "无障碍读取计算器结果与桌面图标恢复入口"
                setShowBadge(false)
            },
        )
    }

    private fun showStatusNotification() {
        val openSettings = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CalculatorHideSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, CalculatorAccessibilityControlReceiver::class.java)
                .setAction(CalculatorHide.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val restore = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, CalculatorAccessibilityControlReceiver::class.java)
                .setAction(CalculatorHide.ACTION_RESTORE_LAUNCHER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_vector)
            .setContentTitle("Night 计算器监听")
            .setContentText("等待计算器显示指定数字")
            .setContentIntent(openSettings)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "停止监听", stop)
            .addAction(0, "恢复图标", restore)
            .build()
        runCatching {
            startForeground(NOTIFICATION_ID, notification)
        }.onFailure {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 4102
        private const val CHANNEL_ID = "night_calculator_accessibility"
    }
}

class CalculatorAccessibilityControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            CalculatorHide.ACTION_STOP -> {
                CalculatorHide.setEnabled(context, false)
                context.getSystemService(NotificationManager::class.java)
                    .cancel(CalculatorAccessibilityService.NOTIFICATION_ID)
                Toast.makeText(context, "计算器监听已停止", Toast.LENGTH_SHORT).show()
            }
            CalculatorHide.ACTION_RESTORE_LAUNCHER -> {
                val restored = CalculatorHide.setLauncherVisible(context, true)
                Toast.makeText(
                    context,
                    if (restored) "Night 桌面图标已恢复" else "桌面图标恢复失败",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}
