package me.weishu.kernelsu.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import me.weishu.kernelsu.ui.util.getModuleStoragePath
import me.weishu.kernelsu.ui.util.getNightRuntimeStatus
import me.weishu.kernelsu.ui.util.rootAvailable
import me.weishu.kernelsu.ui.util.setModuleStoragePath

class ModuleStorageSettingsActivity : Activity() {
    private lateinit var pathInput: EditText
    private lateinit var statusView: TextView
    private lateinit var runtimeStatusView: TextView
    private lateinit var saveButton: Button
    private lateinit var defaultButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refresh()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 44, 32, 64)
            setBackgroundColor(Color.rgb(7, 11, 20))
        }
        root.addView(text("Night 独家运行层", 27f, Color.WHITE))
        root.addView(text("Night 独家衍生版，个人爱好开发，基于 KernelSU。透明展示当前启动阶段、Boot ID 与模块物理目录；不会隐藏或伪装 Root 状态。", 14f, Color.rgb(172, 184, 211)))
        runtimeStatusView = text("正在读取 Night 运行状态…", 14f, Color.rgb(159, 217, 255)).apply {
            setTextIsSelectable(true)
            setPadding(20, 24, 20, 24)
            setBackgroundColor(Color.rgb(13, 28, 49))
        }
        root.addView(runtimeStatusView, wide())
        root.addView(text("独家模块存储目录", 22f, Color.WHITE))
        root.addView(text("模块仍通过 /data/adb/modules 兼容路径运行，但实际文件会在重启时迁移到你选择的目录。", 14f, Color.rgb(172, 184, 211)))
        root.addView(text("仅允许 /data 下的绝对路径。新目录必须为空；保存后必须重启。不要选择 /data/adb/ksu、/data/adb/modules 或其子目录。", 14f, Color.rgb(255, 194, 103)))

        pathInput = EditText(this).apply {
            hint = "/data/adb/night/modules"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
            setBackgroundColor(Color.rgb(20, 31, 52))
            setPadding(20, 18, 20, 18)
        }
        root.addView(pathInput, wide())
        saveButton = button("保存自定义目录") { confirmAndSave(pathInput.text.toString()) }
        defaultButton = button("恢复原版目录") { confirmAndSave("default") }
        root.addView(saveButton, wide())
        root.addView(defaultButton, wide())
        statusView = text("正在读取当前配置…", 14f, Color.rgb(204, 229, 255)).apply {
            setTextIsSelectable(true)
            setPadding(20, 24, 20, 24)
            setBackgroundColor(Color.rgb(16, 24, 42))
        }
        root.addView(statusView, wide())
        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        setBusy(true)
        Thread {
            val result = runCatching {
                check(rootAvailable()) { "KernelSU Root 不可用" }
                Pair(
                    getModuleStoragePath().ifBlank { "/data/adb/modules" },
                    getNightRuntimeStatus().ifBlank { "Night Runtime 状态暂不可用" },
                )
            }
            runOnUiThread {
                setBusy(false)
                result.fold(
                    onSuccess = { (path, runtime) ->
                        pathInput.setText(path)
                        statusView.text = "当前配置：$path"
                        runtimeStatusView.text = runtime
                    },
                    onFailure = {
                        statusView.text = "读取失败：${it.message}"
                        runtimeStatusView.text = "Night Runtime 状态读取失败"
                    },
                )
            }
        }.start()
    }

    private fun confirmAndSave(rawPath: String) {
        val path = rawPath.trim()
        if (path.isEmpty()) {
            Toast.makeText(this, "请输入目录，或点击恢复原版目录", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("确认更改模块存储目录")
            .setMessage("目标：$path\n\n重启时将迁移模块文件。迁移期间请勿强制关机；配置错误时 KernelSU 会停止加载模块，避免使用错误目录继续启动。")
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> save(path) }
            .show()
    }

    private fun save(path: String) {
        setBusy(true)
        statusView.text = "正在验证并保存…"
        Thread {
            val result = runCatching { setModuleStoragePath(path) }
            runOnUiThread {
                setBusy(false)
                result.fold(
                    onSuccess = {
                        statusView.text = "$it\n请重启手机完成迁移。"
                        Toast.makeText(this, "已保存，请重启", Toast.LENGTH_LONG).show()
                        refresh()
                    },
                    onFailure = {
                        statusView.text = "保存失败：${it.message}"
                        Toast.makeText(this, "保存失败", Toast.LENGTH_LONG).show()
                    },
                )
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        if (::saveButton.isInitialized) saveButton.isEnabled = !busy
        if (::defaultButton.isInitialized) defaultButton.isEnabled = !busy
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(56, 93, 207))
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, 10, 0, 10)
        gravity = Gravity.START
    }

    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(0, 8, 0, 12) }
}
