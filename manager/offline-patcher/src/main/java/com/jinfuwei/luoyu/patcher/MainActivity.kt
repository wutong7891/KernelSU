package com.jinfuwei.luoyu.patcher

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private val kmis = listOf(
        "android12-5.10", "android13-5.10", "android13-5.15", "android14-5.15",
        "android14-6.1", "android15-6.6", "android16-6.12"
    )
    private lateinit var sourceLabel: TextView
    private lateinit var outputLabel: TextView
    private lateinit var kmiSpinner: Spinner
    private lateinit var allowShell: CheckBox
    private lateinit var enableAdb: CheckBox
    private lateinit var patchButton: Button
    private lateinit var logView: TextView
    private var sourceUri: Uri? = null
    private var sourceName: String = "boot.img"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 8, 17)
        window.navigationBarColor = Color.rgb(5, 8, 17)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(5, 8, 17))
        }
        content.addView(TextView(this).apply {
            text = "YipaSU 脱机镜像工坊"
            textSize = 26f
            setTextColor(Color.rgb(55, 230, 255))
        })
        content.addView(TextView(this).apply {
            text = "完全脱机修补 boot / init_boot；本 APK 不申请网络权限。"
            setTextColor(Color.rgb(134, 161, 181))
            setPadding(0, dp(8), 0, dp(18))
        })

        sourceLabel = TextView(this).apply {
            text = "尚未选择原始镜像"
            setTextColor(Color.WHITE)
        }
        content.addView(sourceLabel)
        content.addView(Button(this).apply {
            text = "选择 boot.img / init_boot.img"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(25, 92, 112))
            setOnClickListener { chooseSource() }
        })

        outputLabel = TextView(this).apply {
            text = "输出：选择镜像后自动保存到原目录，后缀为 .img"
            setTextColor(Color.rgb(125, 255, 178))
            setPadding(0, dp(10), 0, 0)
        }
        content.addView(outputLabel)

        content.addView(TextView(this).apply {
            text = "选择准确的 KMI 版本"
            setTextColor(Color.WHITE)
            setPadding(0, dp(16), 0, dp(4))
        })
        kmiSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                kmis
            )
            setSelection(4)
        }
        content.addView(kmiSpinner)

        allowShell = CheckBox(this).apply { text = "允许 shell 获取 Root" }
        enableAdb = CheckBox(this).apply { text = "启用调试 ADB" }
        allowShell.setTextColor(Color.WHITE)
        enableAdb.setTextColor(Color.WHITE)
        content.addView(allowShell)
        content.addView(enableAdb)

        patchButton = Button(this).apply {
            text = "开始脱机修补"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(43, 105, 215))
            setOnClickListener { patch() }
        }
        content.addView(patchButton)

        logView = TextView(this).apply {
            text = "就绪。修补前请备份原始镜像。"
            setTextColor(Color.rgb(125, 255, 178))
            setBackgroundColor(Color.rgb(9, 16, 27))
            setTextIsSelectable(true)
            setPadding(0, dp(18), 0, 0)
        }
        content.addView(logView)

        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun chooseSource() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
        }, REQUEST_SOURCE)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        if (requestCode == REQUEST_SOURCE) {
            sourceUri = data.data
            runCatching {
                contentResolver.takePersistableUriPermission(
                    data.data!!,
                    data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
                )
            }
            sourceName = queryDisplayName(data.data!!) ?: "boot.img"
            sourceLabel.text = "原始镜像：$sourceName"
            outputLabel.text = "自动输出：${outputName()}（原目录）"
        }
    }

    private fun patch() {
        val inputUri = sourceUri ?: return
        patchButton.isEnabled = false
        logView.text = "正在准备脱机资源…"
        Thread {
            try {
                val inputFile = File(cacheDir, "source.img")
                val moduleFile = File(cacheDir, "kernelsu.ko")
                val outputFile = File(cacheDir, "YipaSU_patched.img")
                contentResolver.openInputStream(inputUri)!!.use { input ->
                    inputFile.outputStream().use(input::copyTo)
                }
                val kmi = kmis[kmiSpinner.selectedItemPosition]
                assets.open("kmi/${kmi}_kernelsu.ko").use { input ->
                    moduleFile.outputStream().use(input::copyTo)
                }
                outputFile.delete()

                val engine = File(applicationInfo.nativeLibraryDir, "libksud.so")
                val args = mutableListOf(
                    engine.absolutePath, "boot-patch",
                    "--boot", inputFile.absolutePath,
                    "--module", moduleFile.absolutePath,
                    "--out", cacheDir.absolutePath,
                    "--out-name", outputFile.name
                )
                if (allowShell.isChecked) args += "--allow-shell"
                if (enableAdb.isChecked) args += "--enable-adbd"

                val process = ProcessBuilder(args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                check(exitCode == 0 && outputFile.isFile) {
                    "修补失败，退出代码 $exitCode\n$output"
                }
                val outputUri = createSiblingOutput(inputUri, outputName())
                contentResolver.openOutputStream(outputUri, "w")!!.use { target ->
                    outputFile.inputStream().use { it.copyTo(target) }
                }
                runOnUiThread {
                    logView.text = "$output\n完成：$outputUri"
                    Toast.makeText(this, "脱机修补完成", Toast.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    logView.text = "错误：${error.message}"
                    Toast.makeText(this, "修补失败", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { patchButton.isEnabled = true }
            }
        }.start()
    }

    private fun outputName(): String {
        val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { "boot" }
        return "${base}_YipaSU_patched.img"
    }

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun createSiblingOutput(source: Uri, name: String): Uri {
        check(DocumentsContract.isDocumentUri(this, source)) {
            "当前文件提供器无法自动写入原目录，请使用系统文件管理器选择镜像。"
        }
        val authority = checkNotNull(source.authority)
        val documentId = DocumentsContract.getDocumentId(source)
        val slash = documentId.lastIndexOf('/')
        val parentId = if (slash >= 0) {
            documentId.substring(0, slash)
        } else {
            val colon = documentId.indexOf(':')
            check(colon >= 0) { "无法识别所选镜像的原目录。" }
            documentId.substring(0, colon + 1)
        }
        val parent = DocumentsContract.buildDocumentUri(authority, parentId)
        return checkNotNull(
            DocumentsContract.createDocument(contentResolver, parent, "application/octet-stream", name),
        ) { "无法在原镜像目录创建输出文件，请确认目录允许写入。" }
    }

    companion object {
        private const val REQUEST_SOURCE = 1001
    }
}
