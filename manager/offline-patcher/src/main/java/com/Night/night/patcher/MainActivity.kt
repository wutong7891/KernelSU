package com.Night.night.patcher

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val kmis = listOf(
        "android12-5.10", "android13-5.10", "android13-5.15", "android14-5.15",
        "android14-6.1", "android15-6.6", "android16-6.12", "android17-6.18",
    )
    private lateinit var sourceLabel: TextView
    private lateinit var outputLabel: TextView
    private lateinit var kmiSpinner: Spinner
    private lateinit var patchButton: Button
    private lateinit var restoreButton: Button
    private lateinit var logView: TextView
    private var sourceUri: Uri? = null
    private var outputTreeUri: Uri? = null
    private var sourceName = "boot.img"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 11, 20)
        window.navigationBarColor = Color.rgb(7, 11, 20)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(Color.rgb(7, 11, 20))
        }
        content.addView(TextView(this).apply {
            text = "NIGHT / MANUAL OUTPUT V1.3"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(153, 211, 255))
        })
        content.addView(TextView(this).apply {
            text = "LKM RAMDISK 修补 boot / init_boot · 手动选择输出目录"
            setTextColor(Color.rgb(152, 166, 194))
            setPadding(0, dp(8), 0, dp(20))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(190, 24, 33, 51))
                setStroke(dp(1), Color.argb(130, 132, 196, 255))
            }
        }
        sourceLabel = label("尚未选择原始镜像", Color.WHITE)
        card.addView(sourceLabel)
        card.addView(actionButton("选择 boot.img / init_boot.img", Color.rgb(35, 93, 126)) { chooseSource() })
        outputLabel = label("输出目录：尚未选择", Color.rgb(141, 238, 213)).apply {
            setPadding(0, dp(10), 0, dp(12))
        }
        card.addView(outputLabel)
        card.addView(actionButton("手动选择输出目录", Color.rgb(38, 111, 91)) { chooseOutputDirectory() })
        card.addView(label("Night 专属 KMI", Color.WHITE))
        kmiSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, kmis)
            setSelection(4)
        }
        card.addView(kmiSpinner)
        patchButton = actionButton("开始脱机修补", Color.rgb(65, 105, 225)) { patch(false) }
        restoreButton = actionButton("移除 KernelSU / 恢复", Color.rgb(64, 75, 96)) { patch(true) }
        card.addView(patchButton)
        card.addView(restoreButton)
        content.addView(card)

        logView = TextView(this).apply {
            text = "V1.3 已就绪。请分别选择原始镜像和输出目录。"
            setTextColor(Color.rgb(199, 222, 247))
            setTextIsSelectable(true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(14, 21, 34))
            }
        }
        content.addView(logView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply {
            topMargin = dp(16)
        })
        content.addView(label("仅生成新镜像；不会覆盖原文件。", Color.rgb(132, 147, 173)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun label(value: String, color: Int) = TextView(this).apply {
        text = value
        setTextColor(color)
        textSize = 14f
    }

    private fun actionButton(value: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = value
        backgroundTintList = ColorStateList.valueOf(color)
        setTextColor(Color.WHITE)
        setOnClickListener { action() }
    }

    private fun chooseSource() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
        }, REQUEST_SOURCE)
    }

    private fun chooseOutputDirectory() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }, REQUEST_OUTPUT_DIRECTORY)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQUEST_SOURCE -> {
                sourceUri = data.data
                runCatching {
                    contentResolver.takePersistableUriPermission(data.data!!, data.flags and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                }
                sourceName = queryDisplayName(data.data!!) ?: "boot.img"
                sourceLabel.text = "原始镜像：$sourceName"
                refreshOutputLabel()
            }
            REQUEST_OUTPUT_DIRECTORY -> {
                outputTreeUri = data.data
                runCatching {
                    contentResolver.takePersistableUriPermission(data.data!!, data.flags and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                }
                refreshOutputLabel()
            }
        }
    }

    private fun refreshOutputLabel() {
        val tree = outputTreeUri
        outputLabel.text = if (tree == null) {
            "输出目录：尚未选择"
        } else {
            val location = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrDefault(tree.toString())
            "输出目录：$location\n文件名：${outputName(false)}"
        }
    }

    private fun patch(restore: Boolean) {
        val inputUri = sourceUri ?: run {
            Toast.makeText(this, "请先选择原始镜像", Toast.LENGTH_SHORT).show(); return
        }
        val outputTree = outputTreeUri ?: run {
            Toast.makeText(this, "请手动选择输出目录", Toast.LENGTH_SHORT).show(); return
        }
        patchButton.isEnabled = false
        restoreButton.isEnabled = false
        logView.text = "正在准备脱机资源…"
        Thread {
            try {
                val inputFile = File(cacheDir, "source.img")
                val moduleFile = File(cacheDir, "kernelsu.ko")
                val outputFile = File(cacheDir, if (restore) "Night_restored.img" else "Night_patched.img")
                contentResolver.openInputStream(inputUri)!!.use { input -> inputFile.outputStream().use(input::copyTo) }
                if (!restore) assets.open("kmi/${kmis[kmiSpinner.selectedItemPosition]}_kernelsu.ko").use { input ->
                    moduleFile.outputStream().use(input::copyTo)
                }
                outputFile.delete()
                val engine = File(applicationInfo.nativeLibraryDir, "libksud.so")
                val selectedKmi = kmis[kmiSpinner.selectedItemPosition]
                val args = if (restore) {
                    mutableListOf(engine.absolutePath, "boot-restore", "--boot", inputFile.absolutePath,
                        "--out", cacheDir.absolutePath, "--out-name", outputFile.name)
                } else {
                    // init_boot intentionally has no kernel. Use KernelSU's LKM ramdisk
                    // patcher for both boot and init_boot instead of the kernel-injection
                    // boot-patch-v2 command, which can only operate on boot images that
                    // contain a kernel block.
                    mutableListOf(engine.absolutePath, "boot-patch", "--boot", inputFile.absolutePath,
                        "--module", moduleFile.absolutePath, "--kmi", selectedKmi,
                        "--out", cacheDir.absolutePath, "--out-name", outputFile.name)
                }
                val process = ProcessBuilder(args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                check(exitCode == 0 && outputFile.isFile) { "修补失败，退出代码 $exitCode\n$output" }
                val outputUri = createManualOutput(outputTree, outputName(restore))
                contentResolver.openOutputStream(outputUri, "w")!!.use { target -> outputFile.inputStream().use { it.copyTo(target) } }
                val digest = sha256(outputFile)
                runOnUiThread {
                    outputLabel.text = "已输出：${outputName(restore)}\n位置：$outputUri"
                    logView.text = "模式：LKM RAMDISK（支持 init_boot）\n$output\n完成：$outputUri\nSHA-256：$digest"
                    Toast.makeText(this, "脱机处理完成", Toast.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                runOnUiThread { logView.text = "错误：${error.message}"; Toast.makeText(this, "处理失败", Toast.LENGTH_LONG).show() }
            } finally {
                runOnUiThread { patchButton.isEnabled = true; restoreButton.isEnabled = true }
            }
        }.start()
    }

    private fun outputName(restore: Boolean): String {
        val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { "boot" }
        return "${base}_Night_${if (restore) "restored" else "patched"}.img"
    }

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
    )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun createManualOutput(tree: Uri, name: String): Uri {
        check(DocumentsContract.isTreeUri(tree)) { "请选择有效的输出目录。" }
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
        return checkNotNull(DocumentsContract.createDocument(contentResolver, parent, "application/octet-stream", name)) {
            "无法在所选目录创建输出文件。"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val REQUEST_SOURCE = 1001
        private const val REQUEST_OUTPUT_DIRECTORY = 1002
    }
}
