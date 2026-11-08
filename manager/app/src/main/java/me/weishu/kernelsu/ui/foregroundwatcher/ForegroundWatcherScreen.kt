package me.weishu.kernelsu.ui.foregroundwatcher

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.getRootShell

private data class AppEntry(val packageName: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForegroundWatcherScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(ForegroundWatcherStore.read(context)) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }

    var showAppPicker by remember { mutableStateOf(false) }
    var showFilePicker by remember { mutableStateOf(false) }
    var browsePath by remember { mutableStateOf("/") }
    var browseEntries by remember { mutableStateOf(emptyList<Pair<String, Boolean>>()) }

    fun announce(message: String) {
        status = message
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun refreshRunning() {
        scope.launch {
            running = withContext(Dispatchers.IO) { runCatching { ForegroundWatcherDaemon.isRunning() }.getOrDefault(false) }
        }
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            context.packageManager.queryIntentActivities(intent, 0)
                .map {
                    AppEntry(
                        packageName = it.activityInfo.packageName,
                        label = it.loadLabel(context.packageManager).toString(),
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
        refreshRunning()
    }

    fun loadDirectory(path: String) {
        browsePath = path
        scope.launch {
            val out = ArrayList<String>()
            withContext(Dispatchers.IO) {
                getRootShell().newJob()
                    .add("ls -1Ap " + shQuote(path) + " 2>/dev/null")
                    .to(out, null).exec()
            }
            browseEntries = out.map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .map { name -> if (name.endsWith("/")) name.dropLast(1) to true else name to false }
                .sortedWith(compareByDescending<Pair<String, Boolean>> { it.second }.thenBy { it.first.lowercase() })
        }
    }

    fun pickScript(path: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val staged = java.io.File(context.cacheDir, "picked_script.sh")
                    getRootShell().newJob()
                        .add("cat " + shQuote(path) + " > " + shQuote(staged.absolutePath) + " 2>/dev/null")
                        .exec()
                    require(staged.length() > 0) { "读取脚本失败" }
                    ForegroundWatcherStore.importScript(context, Uri.fromFile(staged), path.substringAfterLast('/'))
                    staged.delete()
                }
            }
            result.onSuccess {
                config = ForegroundWatcherStore.read(context)
                announce(context.getString(R.string.foreground_watcher_script_imported))
            }.onFailure { announce(it.message.orEmpty()) }
        }
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
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (enabled) ForegroundWatcherDaemon.start(context) else ForegroundWatcherDaemon.stop()
            }
            result.onSuccess {
                config = config.copy(enabled = enabled)
                ForegroundWatcherStore.write(context, config)
                announce(
                    context.getString(
                        if (enabled) R.string.foreground_watcher_started else R.string.foreground_watcher_stopped
                    )
                )
                refreshRunning()
            }.onFailure { announce(it.message.orEmpty()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.foreground_watcher_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.foreground_watcher_enable), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.foreground_watcher_enable_summary),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(checked = running, onCheckedChange = { setListening(it) })
                    }
                    Text(
                        text = if (running) "运行中" else "未运行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (status.isNotBlank()) {
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            stringResource(R.string.foreground_watcher_developer_notice),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.foreground_watcher_target), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (config.packageName.isBlank()) stringResource(R.string.foreground_watcher_not_selected)
                        else "${config.appLabel}  ${config.packageName}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    OutlinedButton(onClick = { showAppPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.foreground_watcher_choose_app))
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.foreground_watcher_script), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = config.scriptName.ifBlank { stringResource(R.string.foreground_watcher_not_selected) },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = {
                            loadDirectory("/")
                            showFilePicker = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.foreground_watcher_choose_script)) }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.foreground_watcher_preinput), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.foreground_watcher_preinput_summary),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = config.preInput,
                        onValueChange = {
                            config = config.copy(preInput = it)
                            ForegroundWatcherStore.write(context, config)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        placeholder = { Text("y\ny\n") }
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val log = withContext(Dispatchers.IO) { ForegroundWatcherDaemon.readLog() }
                        announce(log.ifBlank { context.getString(R.string.foreground_watcher_no_log) })
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.foreground_watcher_refresh_log)) }
        }
    }

    if (showAppPicker) {
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            title = { Text(stringResource(R.string.foreground_watcher_choose_app)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(apps) { app ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    config = config.copy(packageName = app.packageName, appLabel = app.label)
                                    ForegroundWatcherStore.write(context, config)
                                    showAppPicker = false
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) { Text("取消") }
            }
        )
    }

    if (showFilePicker) {
        AlertDialog(
            onDismissRequest = { showFilePicker = false },
            title = { Text(stringResource(R.string.foreground_watcher_choose_script)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(browsePath, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    if (browsePath != "/") {
                        TextButton(onClick = {
                            val parent = browsePath.trimEnd('/').substringBeforeLast('/', "")
                            loadDirectory(if (parent.isEmpty()) "/" else parent)
                        }) { Text(".. 上一级") }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(browseEntries) { entry ->
                            val (name, isDir) = entry
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isDir) {
                                            loadDirectory(browsePath.trimEnd('/') + "/" + name)
                                        } else {
                                            pickScript(browsePath.trimEnd('/') + "/" + name)
                                            showFilePicker = false
                                        }
                                    }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(
                                    if (isDir) "📁  $name" else "📄  $name",
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
}

private fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
