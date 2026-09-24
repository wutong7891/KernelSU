package me.weishu.kernelsu.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import me.weishu.kernelsu.ui.util.FlashResult
import me.weishu.kernelsu.ui.util.configureNightAttestation
import me.weishu.kernelsu.ui.util.currentBootSlot
import me.weishu.kernelsu.ui.util.flashRawBootImage
import me.weishu.kernelsu.ui.util.installBundledModule
import me.weishu.kernelsu.ui.util.rootAvailable

class NightToolsActivity : Activity() {
    private lateinit var slotStatus: TextView
    private lateinit var imageStatus: TextView
    private lateinit var partitionSpinner: Spinner
    private lateinit var slotSpinner: Spinner
    private lateinit var pathMaskSpinner: Spinner
    private lateinit var logView: TextView
    private val actionButtons = mutableListOf<Button>()
    private var imageUri: Uri? = null

    private val pathMaskLabels = listOf(
        "Android 12 / 5.10",
        "Android 13 / 5.10",
        "Android 13 / 5.15",
        "Android 14 / 6.1",
        "Android 15 / 6.6",
    )
    private val pathMaskAssets = listOf(
        "android12-5.10_pathmask-ksu.zip",
        "android13-5.10_pathmask-ksu.zip",
        "android13-5.15_pathmask-ksu.zip",
        "android14-6.1_pathmask-ksu.zip",
        "android15-6.6_pathmask-ksu.zip",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshSlot()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        imageUri = uri
        imageStatus.text = "已选择：${displayName(uri)}"
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 44, 32, 64)
            setBackgroundColor(Color.rgb(7, 11, 20))
        }
        root.addView(text("Night 部署与分区工具", 27f, Color.WHITE))
        root.addView(text("刷写前会检查目标分区和镜像大小；模块统一调用 KernelSU 安装逻辑。", 14f, Color.rgb(172, 184, 211)))

        root.addView(section("BOOT / INIT_BOOT 刷写"))
        slotStatus = text("当前活动槽位：检测中…", 15f, Color.rgb(129, 212, 250))
        root.addView(slotStatus)
        root.addView(text("目标分区", 14f, Color.LTGRAY))
        partitionSpinner = spinner(listOf("boot", "init_boot"))
        root.addView(partitionSpinner, wide())
        root.addView(text("目标槽位（可与当前槽位不同）", 14f, Color.LTGRAY))
        slotSpinner = spinner(listOf("A 槽位", "B 槽位"))
        root.addView(slotSpinner, wide())
        imageStatus = text("尚未选择 .img 镜像", 14f, Color.rgb(200, 200, 210))
        root.addView(imageStatus)
        root.addView(actionButton("选择 boot / init_boot 镜像") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, REQUEST_IMAGE)
        })
        root.addView(actionButton("确认并刷写所选分区") { confirmFlash() })

        root.addView(section("TEE / PathMask 一键配置"))
        root.addView(text("选择与你的 Android 与内核版本完全一致的 PathMask。", 14f, Color.rgb(255, 194, 103)))
        pathMaskSpinner = spinner(pathMaskLabels)
        root.addView(pathMaskSpinner, wide())
        root.addView(actionButton("一键安装并配置 TEE + PathMask") {
            confirm("一键配置", "将安装 TEESimulator-RS 和所选 PathMask，设置全局路径遮罩、全部应用目标，并替换 keybox.xml。继续吗？") {
                runOperation("开始一键配置") { out, err ->
                    configureNightAttestation(selectedPathMask(), out, err)
                }
            }
        })
        root.addView(actionButton("仅部署所选路径遮罩") {
            confirm("部署 PathMask", "确认安装 ${pathMaskLabels[pathMaskSpinner.selectedItemPosition]}？") {
                runOperation("开始部署 PathMask") { out, err ->
                    installBundledModule(selectedPathMask(), out, err)
                }
            }
        })

        root.addView(section("Sokey"))
        root.addView(text("使用 ksud module install 安装内置 sokey.zip。", 14f, Color.LTGRAY))
        root.addView(actionButton("部署 Sokey") {
            confirm("部署 Sokey", "确认通过 KernelSU 模块安装逻辑部署 sokey.zip？") {
                runOperation("开始部署 Sokey") { out, err ->
                    installBundledModule("sokey.zip", out, err)
                }
            }
        })

        root.addView(section("执行日志"))
        logView = text("等待操作…", 13f, Color.rgb(204, 229, 255)).apply {
            setTextIsSelectable(true)
            setPadding(22, 22, 22, 22)
            setBackgroundColor(Color.rgb(16, 24, 42))
            minHeight = 320
        }
        root.addView(logView, wide())
        root.addView(actionButton("清空日志") { logView.text = "" })
        return ScrollView(this).apply { addView(root) }
    }

    private fun confirmFlash() {
        val uri = imageUri
        if (uri == null) {
            Toast.makeText(this, "请先选择镜像", Toast.LENGTH_SHORT).show()
            return
        }
        val partition = partitionSpinner.selectedItem.toString()
        val slot = if (slotSpinner.selectedItemPosition == 0) "a" else "b"
        confirm(
            "高风险操作：刷写 ${partition}_${slot}",
            "即将把 ${displayName(uri)} 写入 ${partition}_${slot}。镜像或槽位选择错误可能导致设备无法启动。确认继续？",
        ) {
            runOperation("准备刷写 ${partition}_${slot}") { out, err ->
                flashRawBootImage(uri, partition, slot, out, err)
            }
        }
    }

    private fun refreshSlot() {
        Thread {
            val result = runCatching {
                if (!rootAvailable()) error("KernelSU root 不可用")
                currentBootSlot().ifBlank { "未知" }
            }
            runOnUiThread {
                slotStatus.text = result.fold(
                    onSuccess = { "当前活动槽位：${it.uppercase()}" },
                    onFailure = { "当前活动槽位：检测失败（${it.message}）" },
                )
            }
        }.start()
    }

    private fun runOperation(
        startMessage: String,
        operation: ((String) -> Unit, (String) -> Unit) -> FlashResult,
    ) {
        setBusy(true)
        logView.text = startMessage
        Thread {
            val result = runCatching {
                operation(
                    { line -> appendLog(line) },
                    { line -> appendLog("[错误] $line") },
                )
            }
            runOnUiThread {
                setBusy(false)
                result.fold(
                    onSuccess = {
                        appendLog("退出码：${it.code}")
                        if (it.err.isNotBlank()) appendLog(it.err)
                        Toast.makeText(this, if (it.code == 0) "操作完成，请按提示重启" else "操作失败", Toast.LENGTH_LONG).show()
                    },
                    onFailure = {
                        appendLog("异常：${it.message ?: it.javaClass.simpleName}")
                        Toast.makeText(this, "操作失败", Toast.LENGTH_LONG).show()
                    },
                )
            }
        }.start()
    }

    private fun appendLog(line: String) = runOnUiThread {
        logView.append("\n$line")
    }

    private fun setBusy(busy: Boolean) {
        actionButtons.forEach { it.isEnabled = !busy }
    }

    private fun selectedPathMask() = pathMaskAssets[pathMaskSpinner.selectedItemPosition]

    private fun displayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ -> action() }
            .show()
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@NightToolsActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setBackgroundColor(Color.rgb(31, 43, 68))
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(56, 93, 207))
        setOnClickListener { action() }
        actionButtons += this
    }

    private fun section(value: String) = text(value, 19f, Color.WHITE).apply {
        setPadding(0, 36, 0, 12)
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, 10, 0, 10)
        gravity = Gravity.START
    }

    private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(0, 6, 0, 10)
    }

    companion object {
        private const val REQUEST_IMAGE = 4201
    }
}
