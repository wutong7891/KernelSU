package com.jinfuwei.luoyu.patcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
    private lateinit var kmiSpinner: Spinner
    private lateinit var allowShell: CheckBox
    private lateinit var enableAdb: CheckBox
    private lateinit var patchButton: Button
    private lateinit var logView: TextView
    private var sourceUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        content.addView(TextView(this).apply {
            text = "YipaSU 脱机镜像工坊"
            textSize = 26f
        })
        content.addView(TextView(this).apply {
            text = "完全脱机修补 boot / init_boot；本 APK 不申请网络权限。"
            setPadding(0, dp(8), 0, dp(18))
        })

        sourceLabel = TextView(this).apply { text = "尚未选择原始镜像" }
        content.addView(sourceLabel)
        content.addView(Button(this).apply {
            text = "选择 boot.img / init_boot.img"
            setOnClickListener { chooseSource() }
        })

        content.addView(TextView(this).apply {
            text = "选择准确的 KMI 版本"
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
        content.addView(allowShell)
        content.addView(enableAdb)

        patchButton = Button(this).apply {
            text = "选择输出位置并开始修补"
            setOnClickListener { chooseOutput() }
        }
        content.addView(patchButton)

        logView = TextView(this).apply {
            text = "就绪。修补前请备份原始镜像。"
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

    private fun chooseOutput() {
        if (sourceUri == null) {
            Toast.makeText(this, "请先选择原始镜像", Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "YipaSU_patched.img")
        }, REQUEST_OUTPUT)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        if (requestCode == REQUEST_SOURCE) {
            sourceUri = data.data
            sourceLabel.text = "原始镜像：${data.data}"
        } else if (requestCode == REQUEST_OUTPUT) {
            patch(data.data!!)
        }
    }

    private fun patch(outputUri: Uri) {
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

    companion object {
        private const val REQUEST_SOURCE = 1001
        private const val REQUEST_OUTPUT = 1002
    }
}
