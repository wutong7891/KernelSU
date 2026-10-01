package me.weishu.kernelsu.ui.calculator

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
            setBackgroundColor(Color.rgb(7, 11, 20))
        }

        root.addView(label("计算器隐藏", 28f, Color.WHITE, true))
        root.addView(label("启用后，打开 Night 会先显示计算器。只有通过加、减、乘、除计算出设定数字并按下等号，才会进入面具。", 15f, Color.rgb(185, 195, 214)))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.rgb(20, 29, 49), 24f, Color.rgb(73, 93, 130))
        }
        enabled = CheckBox(this).apply {
            text = "启用计算器隐藏"
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
        root.addView(panel, wide().apply { topMargin = dp(24) })

        val save = Button(this).apply {
            text = "保存设置"
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(83, 87, 190), 24f, Color.rgb(130, 155, 244))
            setOnClickListener {
                val value = target.text.toString().trim().toBigDecimalOrNull()
                if (value == null) {
                    target.error = "请输入有效数字"
                    return@setOnClickListener
                }
                CalculatorHide.save(this@CalculatorHideSettingsActivity, enabled.isChecked, value)
                Toast.makeText(this@CalculatorHideSettingsActivity, "已保存，下次启动 Night 时生效", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        root.addView(save, wide(dp(58)).apply { topMargin = dp(20) })
        root.addView(label("为了避免直接输入目标值解锁，必须至少进行一次运算并按下“＝”。请记住设置的数字。", 13f, Color.rgb(145, 158, 183)).apply {
            setPadding(0, dp(18), 0, 0)
        })
        return ScrollView(this).apply { addView(root) }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
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

@Composable
fun CalculatorHideScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val unlockTarget = remember { CalculatorHide.target(context) }
    var display by remember { mutableStateOf("0") }
    var accumulator by remember { mutableStateOf<BigDecimal?>(null) }
    var pendingOperation by remember { mutableStateOf<Char?>(null) }
    var resetInput by remember { mutableStateOf(false) }
    var performedOperation by remember { mutableStateOf(false) }

    fun format(value: BigDecimal): String {
        val normalized = value.stripTrailingZeros()
        val plain = normalized.toPlainString()
        return if (plain.length <= 18) plain else normalized.round(MathContext(12)).toEngineeringString()
    }

    fun calculate(left: BigDecimal, right: BigDecimal, operation: Char): BigDecimal? = runCatching {
        when (operation) {
            '+' -> left.add(right, MathContext.DECIMAL64)
            '-' -> left.subtract(right, MathContext.DECIMAL64)
            '*' -> left.multiply(right, MathContext.DECIMAL64)
            '/' -> if (right.compareTo(BigDecimal.ZERO) == 0) null else left.divide(right, 12, RoundingMode.HALF_UP)
            else -> right
        }
    }.getOrNull()

    fun clearError() {
        if (display == "错误") {
            display = "0"
            accumulator = null
            pendingOperation = null
            performedOperation = false
            resetInput = false
        }
    }

    fun input(value: String) {
        clearError()
        if (resetInput) {
            display = "0"
            resetInput = false
        }
        when (value) {
            "." -> if (!display.contains('.')) display += "."
            "00" -> if (display != "0" && display.length < 16) display += "00"
            else -> if (display.length < 16) display = if (display == "0") value else display + value
        }
    }

    fun chooseOperation(operation: Char) {
        clearError()
        val current = display.toBigDecimalOrNull() ?: return
        val resolved = if (accumulator != null && pendingOperation != null && !resetInput) {
            calculate(accumulator!!, current, pendingOperation!!)
        } else current
        if (resolved == null) {
            display = "错误"
            accumulator = null
            pendingOperation = null
            return
        }
        accumulator = resolved
        display = format(resolved)
        pendingOperation = operation
        resetInput = true
        performedOperation = true
    }

    fun equals() {
        val left = accumulator ?: return
        val operation = pendingOperation ?: return
        val right = display.toBigDecimalOrNull() ?: return
        val result = calculate(left, right, operation)
        accumulator = null
        pendingOperation = null
        resetInput = true
        if (result == null) {
            display = "错误"
            performedOperation = false
            return
        }
        display = format(result)
        val shouldUnlock = performedOperation && result.compareTo(unlockTarget) == 0
        performedOperation = false
        if (shouldUnlock) onUnlocked()
    }

    fun action(key: String) {
        when (key) {
            "AC" -> {
                display = "0"; accumulator = null; pendingOperation = null
                resetInput = false; performedOperation = false
            }
            "±" -> {
                clearError()
                display.toBigDecimalOrNull()?.let { display = format(it.negate()) }
            }
            "%" -> {
                clearError()
                display.toBigDecimalOrNull()?.let { display = format(it.divide(BigDecimal(100))) }
            }
            "+" -> chooseOperation('+')
            "−" -> chooseOperation('-')
            "×" -> chooseOperation('*')
            "÷" -> chooseOperation('/')
            "=" -> equals()
            else -> input(key)
        }
    }

    val rows = listOf(
        listOf("AC", "±", "%", "÷"),
        listOf("7", "8", "9", "×"),
        listOf("4", "5", "6", "−"),
        listOf("1", "2", "3", "+"),
        listOf("0", "00", ".", "=")
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(ComposeColor(0xFF070B14), ComposeColor(0xFF10182A), ComposeColor(0xFF070B14))
                )
            )
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 22.dp, vertical = 18.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text("NIGHT", color = ComposeColor(0xFF88C8FF), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("计算器", color = ComposeColor.White, fontSize = 31.sp, fontWeight = FontWeight.Bold)
            Text("", modifier = Modifier.weight(0.25f))
            Text(
                text = pendingOperation?.let { "$it" }.orEmpty(),
                modifier = Modifier.fillMaxWidth(),
                color = ComposeColor(0xFF93A4C4),
                fontSize = 20.sp,
                textAlign = TextAlign.End
            )
            Text(
                text = display,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                color = ComposeColor.White,
                fontSize = if (display.length > 12) 42.sp else 58.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.End,
                maxLines = 1
            )
            Spacer(Modifier.height(18.dp))
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { key ->
                        val accent = key in setOf("÷", "×", "−", "+", "=")
                        val utility = key in setOf("AC", "±", "%")
                        val fill = when {
                            key == "=" -> Brush.linearGradient(listOf(ComposeColor(0xFF5267D8), ComposeColor(0xFF8A55B5)))
                            accent -> Brush.linearGradient(listOf(ComposeColor(0xFF263A68), ComposeColor(0xFF443663)))
                            utility -> Brush.linearGradient(listOf(ComposeColor(0xFF283044), ComposeColor(0xFF32374A)))
                            else -> Brush.linearGradient(listOf(ComposeColor(0xFF151D2E), ComposeColor(0xFF1B263C)))
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .height(68.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(fill)
                                .clickable { action(key) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                key,
                                color = if (accent) ComposeColor(0xFFE5E9FF) else ComposeColor.White,
                                fontSize = if (key == "AC") 20.sp else 26.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Night Calculator",
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                textAlign = TextAlign.Center,
                fontSize = 12.sp
            )
        }
    }
}
