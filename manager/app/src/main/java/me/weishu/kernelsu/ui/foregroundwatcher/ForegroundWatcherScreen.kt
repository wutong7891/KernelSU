package me.weishu.kernelsu.ui.foregroundwatcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
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
    var status by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf("") }

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
        logs = ForegroundWatcherStore.logFile(context).takeIf { it.isFile }?.readText().orEmpty()
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
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

    fun setListening(enabled: Boolean) {
        if (enabled && (config.packageName.isBlank() || !ForegroundWatcherStore.scriptFile(context).isFile)) {
            status = context.getString(R.string.foreground_watcher_incomplete)
            return
        }
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        persist(config.copy(enabled = enabled))
        ForegroundWatcherService.setEnabled(context, enabled)
        status = context.getString(if (enabled) R.string.foreground_watcher_started else R.string.foreground_watcher_stopped)
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
                            onClick = { scriptPicker.launch(arrayOf("text/*", "application/x-sh")) },
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
                        onClick = {
                            logs = ForegroundWatcherStore.logFile(context).takeIf { it.isFile }?.readText().orEmpty()
                        },
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
