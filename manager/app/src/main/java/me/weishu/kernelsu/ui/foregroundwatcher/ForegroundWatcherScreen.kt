package me.weishu.kernelsu.ui.foregroundwatcher

import android.content.Intent
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

private data class SelectableApp(val packageName: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForegroundWatcherScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    var config by remember { mutableStateOf(ForegroundWatcherStore.read(context)) }
    var apps by remember { mutableStateOf(emptyList<SelectableApp>()) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showRootPicker by remember { mutableStateOf(false) }
    var rootPath by remember { mutableStateOf("/") }
    var rootEntries by remember { mutableStateOf(emptyList<ForegroundWatcherDaemon.RootEntry>()) }
    var rootError by remember { mutableStateOf("") }
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

    fun persist(updated: ForegroundWatcherConfig) {
        config = updated
        ForegroundWatcherStore.write(context, updated)
    }

    fun setListening(enabled: Boolean) {
        if (enabled && (config.packageName.isBlank() || !ForegroundWatcherStore.scriptFile(context).isFile)) {
            status = context.getString(R.string.foreground_watcher_incomplete)
            return
        }
        status = context.getString(R.string.foreground_watcher_working)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (enabled) ForegroundWatcherDaemon.start(context) else ForegroundWatcherDaemon.stop()
            }
            if (result.isSuccess) {
                persist(config.copy(enabled = enabled))
                status = context.getString(
                    if (enabled) R.string.foreground_watcher_started else R.string.foreground_watcher_stopped
                )
                logs = withContext(Dispatchers.IO) { ForegroundWatcherDaemon.readLog() }
            } else {
                status = result.exceptionOrNull()?.message.orEmpty()
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
                if (config.enabled) setListening(true)
            }.onFailure { status = it.message.orEmpty() }
        }
    }

    fun testSelectedScript() {
        status = context.getString(R.string.foreground_watcher_testing)
        scope.launch {
            val result = withContext(Dispatchers.IO) { ForegroundWatcherDaemon.testScript(context) }
            status = if (result.isSuccess) {
                context.getString(R.string.foreground_watcher_test_success)
            } else {
                result.exceptionOrNull()?.message.orEmpty()
            }
            logs = withContext(Dispatchers.IO) { ForegroundWatcherDaemon.readLog() }
        }
    }

    fun loadRootDirectory(path: String) {
        rootPath = path
        rootError = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) { ForegroundWatcherDaemon.listDirectory(path) }
            result.onSuccess { rootEntries = it }
                .onFailure {
                    rootEntries = emptyList()
                    rootError = it.message.orEmpty()
                }
        }
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
                                    if (config.enabled) setListening(true)
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

    if (showRootPicker) {
        AlertDialog(
            onDismissRequest = { showRootPicker = false },
            title = { Text(stringResource(R.string.foreground_watcher_root_browser)) },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = rootPath,
                            onValueChange = { rootPath = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                        Button(onClick = { loadRootDirectory(rootPath) }) {
                            Text(stringResource(R.string.foreground_watcher_open_path))
                        }
                    }
                    if (rootError.isNotBlank()) {
                        Text(rootError, color = MaterialTheme.colorScheme.error)
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                        if (rootPath != "/") {
                            item {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { loadRootDirectory(parentRootPath(rootPath)) }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Folder, null)
                                    Text("..", Modifier.padding(start = 10.dp))
                                }
                                HorizontalDivider()
                            }
                        }
                        items(rootEntries, key = { it.path }) { entry ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (entry.directory) {
                                            loadRootDirectory(entry.path)
                                        } else {
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    ForegroundWatcherDaemon.importRootScript(context, entry.path)
                                                }
                                                result.onSuccess {
                                                    config = ForegroundWatcherStore.read(context)
                                                    status = context.getString(R.string.foreground_watcher_script_imported)
                                                    showRootPicker = false
                                                    if (config.enabled) setListening(true)
                                                }.onFailure { rootError = it.message.orEmpty() }
                                            }
                                        }
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (entry.directory) Icons.Filled.Folder else Icons.Filled.Description, null)
                                Text(entry.path.substringAfterLast('/'), Modifier.padding(start = 10.dp))
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { showRootPicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    MiuixScaffold(
        topBar = {
            MiuixTopAppBar(
                title = stringResource(R.string.foreground_watcher_title),
                navigationIcon = {
                    MiuixIconButton(onClick = { navigator.pop() }) {
                        MiuixIcon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
        popupHost = { },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MiuixCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    SwitchPreference(
                        title = stringResource(R.string.foreground_watcher_enable),
                        summary = stringResource(R.string.foreground_watcher_enable_summary) + "\n" +
                            stringResource(R.string.foreground_watcher_developer_notice),
                        checked = config.enabled,
                        onCheckedChange = { setListening(it) },
                    )
                }
            }
            item {
                MiuixCard(Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.foreground_watcher_target),
                        summary = if (config.packageName.isBlank()) stringResource(R.string.foreground_watcher_not_selected)
                            else "${config.appLabel}\n${config.packageName}",
                        startAction = {
                            MiuixIcon(
                                imageVector = Icons.Filled.Apps,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        },
                        onClick = { showAppPicker = true },
                    )
                }
            }
            item {
                MiuixCard(Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.foreground_watcher_choose_script),
                        summary = config.scriptName.ifBlank { stringResource(R.string.foreground_watcher_not_selected) },
                        startAction = {
                            MiuixIcon(
                                imageVector = Icons.Filled.Description,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        },
                        onClick = { scriptPicker.launch(arrayOf("text/*", "application/x-sh")) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.foreground_watcher_browse_root),
                        summary = stringResource(R.string.foreground_watcher_root_browser),
                        startAction = {
                            MiuixIcon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        },
                        onClick = {
                            showRootPicker = true
                            loadRootDirectory("/")
                        },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.foreground_watcher_use_root),
                        summary = stringResource(R.string.foreground_watcher_use_root_summary),
                        checked = config.useRoot,
                        onCheckedChange = { persist(config.copy(useRoot = it)) },
                    )
                }
            }
            item {
                MiuixCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        BasicComponent(
                            title = stringResource(R.string.foreground_watcher_preinput),
                            summary = stringResource(R.string.foreground_watcher_preinput_summary),
                        )
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
                    MiuixTextButton(
                        text = stringResource(R.string.foreground_watcher_test_script),
                        onClick = { testSelectedScript() },
                        modifier = Modifier.weight(1f),
                    )
                    MiuixTextButton(
                        text = stringResource(R.string.foreground_watcher_refresh_log),
                        onClick = { scope.launch { logs = withContext(Dispatchers.IO) { ForegroundWatcherDaemon.readLog() } } },
                        modifier = Modifier.weight(1f),
                    )
                    MiuixTextButton(
                        text = stringResource(
                            if (config.enabled) R.string.foreground_watcher_stop else R.string.foreground_watcher_start
                        ),
                        onClick = { setListening(!config.enabled) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                MiuixCard(Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = stringResource(R.string.foreground_watcher_log),
                        summary = logs.ifBlank { stringResource(R.string.foreground_watcher_no_log) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun parentRootPath(path: String): String {
    val normalized = path.trimEnd('/')
    if (normalized.isEmpty()) return "/"
    return normalized.substringBeforeLast('/', "").ifEmpty { "/" }
}
