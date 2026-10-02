package me.weishu.kernelsu.ui.calculator

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Html
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.MainActivity
import me.weishu.kernelsu.ui.util.getRootShell
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object CalculatorHide {
    private const val PREFS = "night_calculator_hide"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGET = "target"
    private const val KEY_STATUS = "status"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_NO_BACKGROUND = "no_background"
    private const val DEFAULT_TARGET = "100"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun target(context: Context): BigDecimal = runCatching {
        BigDecimal(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TARGET, DEFAULT_TARGET) ?: DEFAULT_TARGET)
    }.getOrDefault(BigDecimal(DEFAULT_TARGET))

    fun targetText(context: Context): String = target(context).stripTrailingZeros().toPlainString()

    fun save(context: Context, enabled: Boolean, target: BigDecimal, targetPackage: String, noBackground: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_TARGET, target.stripTrailingZeros().toPlainString())
            .putString(KEY_TARGET_PACKAGE, targetPackage)
            .putBoolean(KEY_NO_BACKGROUND, noBackground)
            .apply()
    }

    fun targetPackage(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_TARGET_PACKAGE, "").orEmpty()

    fun isNoBackground(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NO_BACKGROUND, false)

    fun status(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_STATUS, "尚未开始检测") ?: "尚未开始检测"

    fun updateStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATUS, status).apply()
    }

    fun applyServiceState(context: Context) {
        val intent = Intent(context, CalculatorRootMonitorService::class.java)
        context.stopService(intent)
        stopRootWatcher()
        if (!isEnabled(context)) return
        if (isNoBackground(context) && targetPackage(context).isNotBlank()) {
            startRootWatcher(context)
        } else {
            context.startForegroundService(intent)
        }
    }

    private fun stopRootWatcher() {
        getRootShell().newJob().add(
            "pidfile=/data/local/tmp/night_calculator_watcher.pid; " +
                "if [ -f \"\$pidfile\" ]; then kill \"\$(cat \"\$pidfile\")\" 2>/dev/null; fi; rm -f \"\$pidfile\""
        ).exec()
    }

    private fun startRootWatcher(context: Context) {
        val packageName = targetPackage(context)
        val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
        val component = "${BuildConfig.APPLICATION_ID}/${CalculatorRootMonitorService::class.java.name}"
        val script = context.filesDir.resolve("night_calculator_watcher.sh")
        script.writeText(
            """#!/system/bin/sh
while true; do
  state="${'$'}(/system/bin/cmd activity get-uid-state $uid 2>/dev/null | /system/bin/toybox grep -oE '[0-9]+' | /system/bin/toybox head -n 1)"
  if [ "${'$'}state" = "2" ] || [ "${'$'}state" = "1" ]; then
    /system/bin/am start-foreground-service --user current -n '$component' --ez root_wakeup true >/dev/null 2>&1
    sleep 2
  else
    sleep 0.4
  fi
done
""".trimIndent()
        )
        val path = script.absolutePath.replace("'", "'\\''")
        getRootShell().newJob().add(
            "chmod 0700 '$path'; nohup /system/bin/sh '$path' >/dev/null 2>&1 & " +
                "echo \$! > /data/local/tmp/night_calculator_watcher.pid"
        ).exec()
    }
}

class CalculatorHideSettingsActivity : Activity() {
    private lateinit var enabled: CheckBox
    private lateinit var noBackground: CheckBox
    private lateinit var target: EditText
    private lateinit var monitorStatus: TextView
    private lateinit var appSpinner: Spinner
    private var appPackages = emptyList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        if (::monitorStatus.isInitialized) updateMonitorStatus()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
            setBackgroundColor(Color.rgb(7, 11, 20))
        }
        root.addView(label("系统计算器隐藏入口", 27f, Color.WHITE, true))
        root.addView(label("通过 Root 读取系统计算器界面。完成加、减、乘、除运算，结果等于设定数字时自动打开 Night；不使用无障碍权限。", 15f, Color.rgb(185, 195, 214)))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.rgb(20, 29, 49), 24f, Color.rgb(73, 93, 130))
        }
        enabled = CheckBox(this).apply {
            text = "启用 Root 计算器监听"
            textSize = 17f
            setTextColor(Color.WHITE)
            isChecked = CalculatorHide.isEnabled(this@CalculatorHideSettingsActivity)
        }
        panel.addView(enabled, wide())
        noBackground = CheckBox(this).apply {
            text = "无后台模式（Root 守护）"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = CalculatorHide.isNoBackground(this@CalculatorHideSettingsActivity)
        }
        panel.addView(noBackground, wide())
        panel.addView(label("监听目标应用", 14f, Color.rgb(185, 195, 214)).apply {
            setPadding(0, dp(10), 0, 0)
        })
        val appChoices = loadAppChoices()
        appPackages = appChoices.map { it.second }
        appSpinner = Spinner(this).apply {
            setPopupBackgroundDrawable(rounded(Color.rgb(20, 29, 49), 12f, Color.rgb(89, 110, 151)))
            adapter = object : ArrayAdapter<String>(
                this@CalculatorHideSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                appChoices.map { it.first }
            ) {
                override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                    return (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(Color.WHITE)
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }
                }

                override fun getDropDownView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                    return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.rgb(20, 29, 49))
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                    }
                }
            }
            background = rounded(Color.rgb(10, 16, 29), 18f, Color.rgb(89, 110, 151))
            val savedPackage = CalculatorHide.targetPackage(this@CalculatorHideSettingsActivity)
            setSelection(appPackages.indexOf(savedPackage).coerceAtLeast(0))
        }
        panel.addView(appSpinner, wide().apply { topMargin = dp(8) })
        panel.addView(label("解锁结果", 14f, Color.rgb(185, 195, 214)))
        target = EditText(this).apply {
            setText(CalculatorHide.targetText(this@CalculatorHideSettingsActivity))
            hint = "例如 100"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.rgb(10, 16, 29), 18f, Color.rgb(89, 110, 151))
        }
        panel.addView(target, wide().apply { topMargin = dp(8) })
        monitorStatus = label(
            if (CalculatorHide.isEnabled(this)) CalculatorHide.status(this) else "Root 监听：未启用",
            14f,
            if (CalculatorHide.isEnabled(this)) Color.rgb(111, 224, 174) else Color.rgb(255, 194, 103),
            true
        ).apply { setPadding(0, dp(16), 0, 0) }
        panel.addView(monitorStatus, wide())
        root.addView(panel, wide().apply { topMargin = dp(24) })

        root.addView(button("保存并应用") {
            val value = target.text.toString().trim().toBigDecimalOrNull()
            if (value == null) {
                target.error = "请输入有效数字"
                return@button
            }
            val selectedPackage = appPackages.getOrNull(appSpinner.selectedItemPosition).orEmpty()
            if (noBackground.isChecked && selectedPackage.isBlank()) {
                Toast.makeText(this, "无后台模式需要选择一个目标计算器应用", Toast.LENGTH_LONG).show()
                return@button
            }
            CalculatorHide.save(this, enabled.isChecked, value, selectedPackage, noBackground.isChecked)
            runCatching { CalculatorHide.applyServiceState(this) }
                .onSuccess {
                    val targetText = if (selectedPackage.isBlank()) "自动识别计算器" else selectedPackage
                    val modeText = if (noBackground.isChecked) "无后台 Root 守护" else "后台监听"
                    CalculatorHide.updateStatus(this, if (enabled.isChecked) "$modeText 已启动，目标：$targetText" else "Root 监听：已关闭")
                    updateMonitorStatus()
                    monitorStatus.setTextColor(if (enabled.isChecked) Color.rgb(111, 224, 174) else Color.rgb(255, 194, 103))
                    Toast.makeText(this, "设置已应用", Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(this, "启动监听失败：${it.message}", Toast.LENGTH_LONG).show() }
        }, wide(dp(58)).apply { topMargin = dp(18) })

        root.addView(button("刷新检测状态") {
            updateMonitorStatus()
            Toast.makeText(this, CalculatorHide.status(this), Toast.LENGTH_LONG).show()
        }, wide(dp(58)).apply { topMargin = dp(12) })

        root.addView(label("建议直接选择你的系统计算器；“自动识别”才会按应用名称判断。为保证后台运行，系统会显示一条 Night 监听通知。", 13f, Color.rgb(145, 158, 183)).apply {
            setPadding(0, dp(18), 0, 0)
        })
        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 11, 20))
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun loadAppChoices(): List<Pair<String, String>> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val choices = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { resolve ->
                val label = resolve.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                val packageName = resolve.activityInfo.packageName
                ("$label  ·  $packageName") to packageName
            }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(Locale.ROOT) }
        return listOf("自动识别计算器" to "") + choices
    }

    private fun updateMonitorStatus() {
        val active = CalculatorHide.isEnabled(this)
        monitorStatus.text = if (active) CalculatorHide.status(this) else "Root 监听：未启用"
        monitorStatus.setTextColor(if (active) Color.rgb(111, 224, 174) else Color.rgb(255, 194, 103))
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

class CalculatorRootMonitorService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var activePackage = ""
    private var sawOperation = false
    private var lastLaunchAt = 0L
    private var lastOcrAt = 0L
    private var lastStatus = ""
    private var currentUiXml = ""
    private val calculatorPackageCache = mutableMapOf<String, Boolean>()
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        reportStatus("Root 监听已启动，等待打开计算器")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!CalculatorHide.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.compareAndSet(false, true)) {
            worker = Thread(::monitorLoop, "NightCalculatorRootMonitor").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        worker = null
        runCatching { textRecognizer.close() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun monitorLoop() {
        while (running.get() && CalculatorHide.isEnabled(this)) {
            try {
                currentUiXml = ""
                val topPackage = currentForegroundPackage()
                if (topPackage.isBlank()) {
                    reportStatus("Root 已运行，但暂时无法读取前台应用")
                    if (CalculatorHide.isNoBackground(this)) {
                        stopSelf()
                        break
                    }
                    Thread.sleep(1200L)
                    continue
                }
                if (!isCalculatorPackage(topPackage)) {
                    activePackage = ""
                    resetSequence()
                    if (CalculatorHide.isNoBackground(this)) {
                        stopSelf()
                        break
                    }
                    Thread.sleep(1100L)
                    continue
                }
                if (activePackage != topPackage) {
                    activePackage = topPackage
                    resetSequence()
                    reportStatus("已识别计算器：$topPackage")
                }
                currentUiXml = captureCurrentUi()
                val matchedFromUi = inspectCalculatorUi()
                if (!matchedFromUi && System.currentTimeMillis() - lastOcrAt >= 1500L) {
                    lastOcrAt = System.currentTimeMillis()
                    inspectCalculatorScreenshot()
                }
                Thread.sleep(300L)
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
                runCatching { Thread.sleep(1300L) }
            }
        }
        running.set(false)
    }

    private fun currentForegroundPackage(): String {
        val selectedPackage = CalculatorHide.targetPackage(this)
        if (selectedPackage.isNotBlank() && isTargetUidInForeground(selectedPackage)) {
            return selectedPackage
        }

        val probeCommands = listOf(
            "/system/bin/dumpsys activity top",
            "/system/bin/dumpsys activity activities",
            "/system/bin/dumpsys window windows",
            "/system/bin/dumpsys window"
        )
        val patterns = listOf(
            Regex("(?m)^\\s*ACTIVITY\\s+([A-Za-z0-9_.]+)/"),
            Regex("mResumedActivity[:=].*?\\s([A-Za-z0-9_.]+)/"),
            Regex("topResumedActivity[:=].*?\\s([A-Za-z0-9_.]+)/"),
            Regex("mCurrentFocus[:=].*?\\s([A-Za-z0-9_.]+)/"),
            Regex("mFocusedApp[:=].*?\\s([A-Za-z0-9_.]+)/"),
            Regex("\\bu\\d+\\s+([A-Za-z0-9_.]+)(?:/|\\})")
        )
        probeCommands.forEach { command ->
            val output = rootCommand(command)
            if (selectedPackage.isNotBlank() && (
                    output.contains("$selectedPackage/") ||
                        output.contains(" $selectedPackage ") ||
                        output.contains(" $selectedPackage}")
                    )
            ) return selectedPackage
            patterns.forEach { pattern ->
                pattern.find(output)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return ""
    }

    private fun captureCurrentUi(): String = rootCommand(
        "/system/bin/uiautomator dump /data/local/tmp/night_calculator_ui.xml >/dev/null 2>&1; " +
            "/system/bin/cat /data/local/tmp/night_calculator_ui.xml 2>/dev/null"
    )

    private fun isTargetUidInForeground(packageName: String): Boolean = runCatching {
        val uid = packageManager.getApplicationInfo(packageName, 0).uid
        val output = rootCommand(
            "/system/bin/cmd activity get-uid-state $uid 2>/dev/null || " +
                "/system/bin/am get-uid-state $uid 2>/dev/null"
        )
        val state = Regex("(?:^|\\s)(\\d+)(?:\\s|$)").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        state != null && state <= 2
    }.getOrDefault(false)

    private fun inspectCalculatorUi(): Boolean {
        val output = currentUiXml.ifBlank(::captureCurrentUi)
        if (!output.contains("<hierarchy")) {
            reportStatus("已识别计算器，界面文本不可读，正在使用截图识别")
            return false
        }
        val values = Regex("(?:text|content-desc)=\"([^\"]*)\"")
            .findAll(output)
            .map { decodeXml(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .toList()
        reportStatus("已识别计算器，正在读取界面结果")
        return processRecognizedText(values, "界面")
    }

    private fun inspectCalculatorScreenshot(): Boolean {
        val screenshot = cacheDir.resolve("night_calculator_screen.png")
        runCatching {
            screenshot.delete()
            screenshot.parentFile?.mkdirs()
            screenshot.createNewFile()
        }.onFailure {
            reportStatus("截图识别失败：无法创建缓存文件")
            return false
        }
        rootCommand("/system/bin/screencap -p > '${screenshot.absolutePath}'")
        val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath) ?: run {
            reportStatus("截图识别失败：无法读取屏幕图像")
            return false
        }
        val recognitionBitmap = if (bitmap.width > 1080) {
            val height = (bitmap.height * (1080f / bitmap.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, 1080, height, true)
        } else {
            bitmap
        }
        return try {
            val result = Tasks.await(
                textRecognizer.process(InputImage.fromBitmap(recognitionBitmap, 0)),
                20,
                TimeUnit.SECONDS
            )
            val values = result.textBlocks.flatMap { block ->
                block.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
            }
            reportStatus("已识别计算器，正在使用截图 OCR")
            processRecognizedText(values, "OCR")
        } catch (error: Throwable) {
            val detail = error.message?.take(80)?.takeIf { it.isNotBlank() }
            reportStatus("截图 OCR 失败：${error.javaClass.simpleName}${detail?.let { "：$it" }.orEmpty()}")
            false
        } finally {
            if (recognitionBitmap !== bitmap) recognitionBitmap.recycle()
            bitmap.recycle()
        }
    }

    private fun processRecognizedText(values: List<String>, source: String): Boolean {
        if (values.any(::containsExpression)) sawOperation = true
        if (!sawOperation) return false

        val target = CalculatorHide.target(this)
        val matched = values.asSequence()
            .flatMap { value ->
                when {
                    !containsOperator(value) -> numericCandidates(value).asSequence()
                    value.contains('=') || value.contains('＝') -> numericCandidates(value).takeLast(1).asSequence()
                    else -> emptySequence()
                }
            }
            .any { it.compareTo(target) == 0 }
        if (!matched) return false

        val now = System.currentTimeMillis()
        if (now - lastLaunchAt < 3000L) return true
        lastLaunchAt = now
        resetSequence()
        reportStatus("$source 已匹配结果 ${target.stripTrailingZeros().toPlainString()}，正在打开 Night")
        rootCommand("am start --user current -n ${BuildConfig.APPLICATION_ID}/${MainActivity::class.java.name} >/dev/null 2>&1")
        return true
    }

    private fun rootCommand(command: String): String {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        getRootShell().newJob().add(command).to(stdout, stderr).exec()
        return stdout.joinToString("\n")
    }

    private fun isCalculatorPackage(packageName: String): Boolean {
        if (packageName.isBlank() || packageName == BuildConfig.APPLICATION_ID) return false
        val selectedPackage = CalculatorHide.targetPackage(this)
        if (selectedPackage.isNotBlank()) return packageName == selectedPackage
        return calculatorPackageCache.getOrPut(packageName) {
            val normalizedPackage = packageName.lowercase(Locale.ROOT)
            val knownPackage = normalizedPackage.contains("calculator") || normalizedPackage.contains("calc") ||
                normalizedPackage.contains("jisuanqi")
            if (knownPackage) true else runCatching {
                val info = packageManager.getApplicationInfo(packageName, 0)
                val label = packageManager.getApplicationLabel(info).toString().trim().lowercase(Locale.ROOT)
                label.contains("计算器") || label.contains("计算机") || label == "计算" || label.contains("calculator")
            }.getOrDefault(false)
        }
    }

    private fun decodeXml(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()

    private fun resetSequence() {
        sawOperation = false
    }

    private fun containsOperator(value: String): Boolean {
        val text = value.trim()
        return text.drop(1).any { it in "+−×÷*/=＝" }
    }

    private fun containsExpression(value: String): Boolean =
        Regex("[-+]?\\d+(?:[.,]\\d+)?\\s*[+−×÷*/-]\\s*[-+]?\\d+(?:[.,]\\d+)?").containsMatchIn(value)

    private fun numericCandidates(value: String): List<BigDecimal> {
        val normalized = value.replace(',', '.').replace('−', '-')
        return Regex("(?<![\\d.])[-+]?\\d+(?:\\.\\d+)?(?![\\d.])")
            .findAll(normalized)
            .mapNotNull { it.value.toBigDecimalOrNull() }
            .toList()
    }

    private fun reportStatus(status: String) {
        if (lastStatus == status) return
        lastStatus = status
        CalculatorHide.updateStatus(this, status)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Night 计算器监听", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Root 监测系统计算器的运算结果"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_logo_vector)
        .setContentTitle("Night 计算器隐藏入口")
        .setContentText("正在等待系统计算器的指定结果")
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, CalculatorHideSettingsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "night_calculator_root_monitor"
        private const val NOTIFICATION_ID = 4102
    }
}

class CalculatorMonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && CalculatorHide.isEnabled(context)) {
            runCatching { CalculatorHide.applyServiceState(context) }
        }
    }
}
