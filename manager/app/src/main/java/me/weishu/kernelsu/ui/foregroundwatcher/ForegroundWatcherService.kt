package me.weishu.kernelsu.ui.foregroundwatcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.MainActivity
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ForegroundWatcherService : Service() {
    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startVisible()
        if (running.compareAndSet(false, true)) worker.execute(::watchLoop)
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startVisible() {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.foreground_watcher_notification_title))
            .setContentText(getString(R.string.foreground_watcher_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.foreground_watcher_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.foreground_watcher_channel_summary) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun watchLoop() {
        var targetWasForeground = false
        while (running.get()) {
            val config = ForegroundWatcherStore.read(this)
            if (!config.enabled || config.packageName.isBlank()) {
                stopSelf()
                return
            }
            val foreground = readForegroundPackage()
            val targetIsForeground = foreground == config.packageName
            if (targetIsForeground && !targetWasForeground) executeSelectedScript(config)
            targetWasForeground = targetIsForeground
            try {
                TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun readForegroundPackage(): String {
        val output = ArrayList<String>()
        val result = getRootShell().newJob().add(FOREGROUND_PACKAGE_COMMAND).to(output, null).exec()
        return if (result.isSuccess) output.firstOrNull().orEmpty().trim() else ""
    }

    private fun executeSelectedScript(config: ForegroundWatcherConfig) {
        val script = ForegroundWatcherStore.scriptFile(this)
        if (!script.isFile) {
            appendLog("Skipped: selected script is missing")
            return
        }
        val input = File(script.parentFile, "pre_input.txt")
        input.writeText(config.preInput)
        input.setReadable(true, true)
        val command = "cd / && /system/bin/sh ${shellQuote(script.absolutePath)} < ${shellQuote(input.absolutePath)}"
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val exitCode = if (config.useRoot) {
            getRootShell().newJob().add(command).to(stdout, stderr).exec().code
        } else {
            runWithoutRoot(script, config.preInput, stdout, stderr)
        }
        input.writeText("")
        appendLog(
            buildString {
                append("Triggered ").append(config.packageName).append(", exit=").append(exitCode)
                if (stdout.isNotEmpty()) append("\n").append(stdout.joinToString("\n"))
                if (stderr.isNotEmpty()) append("\n").append(stderr.joinToString("\n"))
            }
        )
    }

    private fun runWithoutRoot(
        script: File,
        preInput: String,
        stdout: MutableList<String>,
        stderr: MutableList<String>,
    ): Int = runCatching {
        val process = ProcessBuilder("/system/bin/sh", script.absolutePath)
            .directory(File("/"))
            .start()
        process.outputStream.bufferedWriter().use { it.write(preInput) }
        stdout += process.inputStream.bufferedReader().readLines()
        stderr += process.errorStream.bufferedReader().readLines()
        process.waitFor()
    }.getOrElse {
        stderr += it.message.orEmpty()
        -1
    }

    private fun appendLog(message: String) {
        runCatching {
            val file = ForegroundWatcherStore.logFile(this)
            file.parentFile?.mkdirs()
            file.appendText("[${LocalDateTime.now().format(LOG_TIME)}] $message\n")
            if (file.length() > MAX_LOG_BYTES) {
                val lines = file.readLines().takeLast(200)
                file.writeText(lines.joinToString("\n", postfix = "\n"))
            }
        }.onFailure { Log.w(TAG, "Cannot write watcher log", it) }
    }

    companion object {
        private const val TAG = "ForegroundWatcher"
        private const val CHANNEL_ID = "foreground_watcher"
        private const val NOTIFICATION_ID = 4601
        private const val POLL_INTERVAL_MS = 1500L
        private const val MAX_LOG_BYTES = 256 * 1024L
        private val LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private const val FOREGROUND_PACKAGE_COMMAND =
            "dumpsys activity activities 2>/dev/null | sed -n 's/.*mResumedActivity:.* u[0-9]* \\([^/ ]*\\)\/.*/\\1/p' | head -n 1"

        fun setEnabled(context: Context, enabled: Boolean) {
            val current = ForegroundWatcherStore.read(context)
            ForegroundWatcherStore.write(context, current.copy(enabled = enabled))
            val intent = Intent(context, ForegroundWatcherService::class.java)
            if (enabled) context.startForegroundService(intent) else context.stopService(intent)
        }

        private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
    }
}
