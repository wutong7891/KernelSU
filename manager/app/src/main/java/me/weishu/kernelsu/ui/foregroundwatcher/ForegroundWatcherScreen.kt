package me.weishu.kernelsu.ui.foregroundwatcher

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.getRootShell
import me.weishu.kernelsu.ui.navigation3.LocalNavigator

private data class SelectableApp(val packageName: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForegroundWatcherScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    var config by remember { mutableStateOf(ForegroundWatcherStore.read(context)) }
    var apps by remember { mutableStateOf(emptyList<SelectableApp>()) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showFilePicker by remember { mutableStateOf(false) }
    var browsePath by remember { mutableStateOf("/") }
    var browseEntries by remember { mutableStateOf(emptyList<Pair<String, Boolean>>()) }
    var status by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            context.packageManager.queryIntentActivities(intent, 0)
                .map {
                    SelectableApp(
                        packageName = it.activityInfo.packageName,
                        label = it.loadLabel(context.packageManager).toString(),
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
        val running = withContext(Dispatchers.IO) {
            if (config.enabled && !ForegroundWatcherDaemon.isRunning()) {
                ForegroundWatcherDaemon.start(context)
            }
            ForegroundWatcherDaemon.isRunning()
        }
        if (config.enabled != running) {
            config = config.copy(enabled = running)
            ForegroundWatcherStore.write(context, config)
        }
        logs = withContext(Dispatchers.IO) { ForegroundWatcherDaemon.readLog() }
    }

    // ---------- 从根目录开始的文件浏览 ----------
    fun loadDirectory(path: String) {
        browsePath = path
        scope.launch {
            val out = ArrayList<String>()
            withContext(Dispatchers.IO) {
                getRootShell().newJob()
                    .add("ls -1Ap " + "'" + path.replace("'", "'\\''") + "' 2>/dev/null")
                    .to(out, null).exec()
            }
            browseEntries = out.map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .map { name ->
                    if (name.endsWith("/")) (name.dropLast(1) to true) else (name to false)
                }
                .sortedWith(compareByDescending<Pair<String, Boolean>> { it.second }.thenBy { it.first.lowercase() })
        }
    }

    fun pickScriptByName(path: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val staged = java.io.File(context.cacheDir, "picked_script.sh")
                    val src = "/system/bin/cat " + "'" + path.replace("'", "'\\''") + "'"
                    getRootShell().newJob()
                        .add("$src > " + "'" + staged.absolutePath + "' && chmod 0600 " + "'" + staged.absolutePath + "'")
                        .exec()
                    require(staged.length() > 0) { "读取脚本失败" }
                    ForegroundWatcherStore.importScript(context, Uri.fromFile(staged), staged.name)
                }
            }
            result.onSuccess {
                config = ForegroundWatcherStore.read(context)
                status = context.getString(R.string.foreground_watcher_script_imported)
            }.onFailure {
                status = it.message.orEmpty()
            }
        }
    }

    val scriptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val displayName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: "selected_script.sh"
            runCatching {
                ForegroundWatcherStore.importScript(context, uri, displayName)
                config = ForegroundWatcherStore.read(context)
                status = context.getString(R.string.foreground_watcher_script_imported)
            }.onFailure { status = it.message.orEmpty() }
        }
    }

    fun persist(updated: ForegroundWatcherConfig) {
        config = updated
        ForegroundWatcherStore.write(context, updated)
    }

    fun announce(message: String) {
        status = message
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun setListening(enabled: Boolean) {
        if (enabled && config.packageName.isBlank()) {
            announce(context.getString(R.string.foreground_watcher_pick_app_first))
            return
        }
        if (enabled && !ForegroundWatcherStore.scriptFile(context).isFile) {
            announce(context.getString(R.string.foreground_watcher_pick_script_first))
            return
        }
        status = context.getString(R.string.foreground_watcher_working)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (enabled) ForegroundWatcherDaemon.start(context) else ForegroundWatcherDaemon.stop()
            }
            if (result.isSuccess) {
                persist(config.copy(enabled = enabled))
                announce(
                    context.getString(
                        if (enabled) R.string.foreground_watcher_started else R.string.foreground_watcher_stopped
                    )
                )
                logs = withContext(Dispatchers.IO) { ForegroundWatcherDaemon.readLog() }
            } else {
                announce(result.exceptionOrNull()?.message.orEmpty().ifBlank { context.getString(R.string.foreground_watcher_start_failed) })
            }
        }
    }

    if (showFilePicker) {
        AlertDialog(
            onDismissRequest = { showFilePicker = false },
            title = { Text(stringResource(R.string.foreground_watcher_choose_script)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = browsePath,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    if (browsePath != "/") {
                        TextButton(
                            onClick = {
                                val parent = browsePath.trimEnd('/').substringBeforeLast('/', "")
                                loadDirectory(if (parent.isEmpty()) "/" else parent)
                            }
                        ) { Text(".. 上一级") }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(browseEntries) { entry ->
                            val (name, isDir) = entry
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isDir) {
                                            val base = browsePath.trimEnd('/')
                                            loadDirectory("$base/$name")
                                        } else {
                                            pickScriptByName(browsePath.trimEnd('/') + "/" + name)
                                            showFilePicker = false
                                        }
                                    }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(
                                    text = if (isDir) "📁  $name" else "📄  $name",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilePicker = false }) { Text("取消") }
            }
        )
    }

    if (showAppPicker) {
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            title = { Text(stringResource(R.string.foreground_watcher_choose_app)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(apps, key = { it.packageName }) { app ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    persist(config.copy(packageName = app.packageName, appLabel = app.label))
                                    showAppPicker = false
                                }
                                .padding(vertical = 12.dp)
                        ) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { showAppPicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.foreground_watcher_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.foreground_watcher_enable), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.foreground_watcher_enable_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    stringResource(R.string.foreground_watcher_developer_notice),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Switch(checked = config.enabled, onCheckedChange = { setListening(it) })
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.foreground_watcher_target), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (config.packageName.isBlank()) stringResource(R.string.foreground_watcher_not_selected)
                            else "${config.appLabel}\n${config.packageName}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { showAppPicker = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Apps, null)
                            Text(stringResource(R.string.foreground_watcher_choose_app), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.foreground_watcher_script), style = MaterialTheme.typography.titleMedium)
                        Text(
                            config.scriptName.ifBlank { stringResource(R.string.foreground_watcher_not_selected) },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = {
                                loadDirectory("/")
                                showFilePicker = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Description, null)
                            Text(stringResource(R.string.foreground_watcher_choose_script), Modifier.padding(start = 8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.foreground_watcher_use_root))
                                Text(
                                    stringResource(R.string.foreground_watcher_use_root_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = config.useRoot,
                                onCheckedChange = { persist(config.copy(useRoot = it)) },
                            )
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.foreground_watcher_preinput), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.foreground_watcher_preinput_summary), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = config.preInput,
                            onValueChange = { persist(config.copy(preInput = it)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            minLines = 5,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            placeholder = { Text("1\nconfirm\ntext") },
                        )
                    }
                }
            }
            if (status.isNotBlank()) {
                item { Text(status, color = MaterialTheme.colorScheme.primary) }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { scope.launch { logs = withContext(Dispatchers.IO) { ForegroundWatcherDaemon.readLog() } } },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.foreground_watcher_refresh_log)) }
                    Button(
                        onClick = { setListening(!config.enabled) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Text(
                            stringResource(
                                if (config.enabled) R.string.foreground_watcher_stop else R.string.foreground_watcher_start
                            ),
                            Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.foreground_watcher_log), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            logs.ifBlank { stringResource(R.string.foreground_watcher_no_log) },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
