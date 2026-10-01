package me.weishu.kernelsu.ui.calculator

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import me.weishu.kernelsu.ui.MainActivity
import java.math.BigDecimal
import java.util.Locale

object CalculatorHide {
    private const val PREFS = "night_calculator_hide"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGET = "target"
    private const val DEFAULT_TARGET = "100"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun target(context: Context): BigDecimal = runCatching {
        BigDecimal(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TARGET, DEFAULT_TARGET) ?: DEFAULT_TARGET)
    }.getOrDefault(BigDecimal(DEFAULT_TARGET))

    fun targetText(context: Context): String = target(context).stripTrailingZeros().toPlainString()

    fun save(context: Context, enabled: Boolean, target: BigDecimal) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_TARGET, target.stripTrailingZeros().toPlainString())
            .apply()
    }
}

class CalculatorHideSettingsActivity : Activity() {
    private lateinit var enabled: CheckBox
    private lateinit var target: EditText
    private lateinit var serviceStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        if (::serviceStatus.isInitialized) updateServiceStatus()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
            setBackgroundColor(Color.rgb(7, 11, 20))
        }
        root.addView(label("系统计算器隐藏入口", 27f, Color.WHITE, true))
        root.addView(label("在系统自带计算器里完成加、减、乘、除运算。当结果等于你设置的数字时，自动打开 Night 面具。直接输入目标数字不会触发。", 15f, Color.rgb(185, 195, 214)))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.rgb(20, 29, 49), 24f, Color.rgb(73, 93, 130))
        }
        enabled = CheckBox(this).apply {
            text = "启用系统计算器唤醒"
            textSize = 17f
            setTextColor(Color.WHITE)
            isChecked = CalculatorHide.isEnabled(this@CalculatorHideSettingsActivity)
        }
        panel.addView(enabled, wide())
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
        serviceStatus = label("", 14f, Color.rgb(255, 194, 103), true).apply {
            setPadding(0, dp(16), 0, 0)
        }
        panel.addView(serviceStatus, wide())
        root.addView(panel, wide().apply { topMargin = dp(24) })

        root.addView(button("保存设置") {
            val value = target.text.toString().trim().toBigDecimalOrNull()
            if (value == null) {
                target.error = "请输入有效数字"
                return@button
            }
            CalculatorHide.save(this, enabled.isChecked, value)
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        }, wide(dp(58)).apply { topMargin = dp(18) })

        root.addView(button("打开系统辅助功能设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, wide(dp(58)).apply { topMargin = dp(12) })

        root.addView(label("首次使用：保存设置后，点击上方按钮，在系统辅助功能中启用“Night 系统计算器唤醒”。系统不允许应用替你自动打开这个权限。", 13f, Color.rgb(145, 158, 183)).apply {
            setPadding(0, dp(18), 0, 0)
        })
        updateServiceStatus()
        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 11, 20))
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun updateServiceStatus() {
        val component = ComponentName(this, CalculatorWakeAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val active = enabledServices.split(':').any { ComponentName.unflattenFromString(it) == component }
        serviceStatus.text = if (active) "辅助功能：已启用" else "辅助功能：未启用（必须手动开启）"
        serviceStatus.setTextColor(if (active) Color.rgb(111, 224, 174) else Color.rgb(255, 194, 103))
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

class CalculatorWakeAccessibilityService : AccessibilityService() {
    private var activePackage = ""
    private var sawOperation = false
    private var sawEquals = false
    private var lastLaunchAt = 0L
    private val calculatorPackageCache = mutableMapOf<String, Boolean>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !CalculatorHide.isEnabled(this)) return
        val packageName = event.packageName?.toString().orEmpty()
        if (!isCalculatorPackage(packageName)) return

        if (activePackage != packageName) {
            activePackage = packageName
            resetSequence()
        }

        val eventText = buildList {
            event.text.mapNotNullTo(this) { it?.toString() }
            event.contentDescription?.toString()?.let(::add)
            event.source?.let { collectNodeText(it, this) }
            rootInActiveWindow?.let { collectNodeText(it, this) }
        }.map { it.trim() }.filter { it.isNotEmpty() }

        val joined = eventText.joinToString(" ")
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            when {
                eventText.any(::isClearKey) -> resetSequence()
                eventText.any(::isOperatorKey) -> sawOperation = true
                eventText.any(::isEqualsKey) -> sawEquals = true
            }
        }
        if (containsExpression(joined)) sawOperation = true
        if (!sawOperation) return

        val target = CalculatorHide.target(this)
        val resultMatched = eventText.asSequence()
            .flatMap { numericCandidates(it).asSequence() }
            .any { it.compareTo(target) == 0 }
        if (!resultMatched) return

        // 部分系统计算器没有等号（输入后实时出结果），因此检测到完整运算表达式也允许触发。
        if (!sawEquals && !containsExpression(joined)) return
        val now = System.currentTimeMillis()
        if (now - lastLaunchAt < 2500L) return
        lastLaunchAt = now
        resetSequence()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    override fun onInterrupt() = Unit

    private fun resetSequence() {
        sawOperation = false
        sawEquals = false
    }

    private fun collectNodeText(node: AccessibilityNodeInfo, output: MutableList<String>) {
        node.text?.toString()?.let(output::add)
        node.contentDescription?.toString()?.let(output::add)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectNodeText(it, output) }
        }
    }

    private fun isCalculatorPackage(packageName: String): Boolean {
        if (packageName.isBlank() || packageName == applicationContext.packageName) return false
        return calculatorPackageCache.getOrPut(packageName) {
            val known = packageName.lowercase(Locale.ROOT).let {
                it.contains("calculator") || it.contains("calc") || it.contains("jisuanqi")
            }
            if (known) true else runCatching {
                val info = packageManager.getApplicationInfo(packageName, 0)
                val label = packageManager.getApplicationLabel(info).toString().lowercase(Locale.ROOT)
                label.contains("计算器") || label.contains("calculator")
            }.getOrDefault(false)
        }
    }

    private fun isOperatorKey(value: String): Boolean {
        val key = value.trim().lowercase(Locale.ROOT)
        return key in setOf("+", "−", "-", "×", "*", "÷", "/", "加", "减", "乘", "除", "plus", "minus", "multiply", "divide")
    }

    private fun isEqualsKey(value: String): Boolean {
        val key = value.trim().lowercase(Locale.ROOT)
        return key in setOf("=", "＝", "等于", "equals")
    }

    private fun isClearKey(value: String): Boolean {
        val key = value.trim().lowercase(Locale.ROOT)
        return key in setOf("ac", "c", "ce", "清除", "归零", "clear")
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
}
