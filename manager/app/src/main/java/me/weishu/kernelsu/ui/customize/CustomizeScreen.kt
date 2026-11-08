package me.weishu.kernelsu.ui.customize

import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator

private data class InstalledApp(val packageName: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var guarding by remember { mutableStateOf(false) }
    var targetPkg by remember { mutableStateOf("") }
    var targetLabel by remember { mutableStateOf("") }
    var present by remember { mutableStateOf(true) }
    var visible by remember { mutableStateOf(true) }
    var log by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(emptyList<InstalledApp>()) }

    fun announce(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun refresh() {
        scope.launch {
            val state = withContext(Dispatchers.IO) {
                Triple(
                    CustomizeController.isGuarding(),
                    CustomizeController.targetPackage(),
                    CustomizeController.isManagerVisible(context),
                )
            }
            guarding = state.first
            targetPkg = state.second
            visible = state.third
            present = withContext(Dispatchers.IO) { CustomizeController.isManagerPresent(context) }
            targetLabel = if (targetPkg.isBlank()) "" else runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(targetPkg, 0)).toString()
            }.getOrDefault(targetPkg)
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        scope.launch {
            val s = withContext(Dispatchers.IO) { CustomizeController.status(context) }
            log = s.getOrDefault("")
        }
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun debugAction(action: String) {
        if (busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { CustomizeController.run(context, action) }
            busy = false
            result.onSuccess { log = it }
                .onFailure { log = it.message.orEmpty() }
            refresh()
        }
    }

    fun toggle(enabled: Boolean) {
        if (busy) return
        if (enabled && targetPkg.isBlank()) {
            announce(context.getString(R.string.customize_need_app))
            return
        }
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                if (enabled) CustomizeController.arm(context, targetPkg)
                else CustomizeController.disarm(context)
            }
            busy = false
            result.onSuccess { text ->
                log = text
                announce(
                    context.getString(
                        if (enabled) R.string.customize_armed else R.string.customize_disarmed
                    )
                )
                refresh()
            }.onFailure {
                log = it.message.orEmpty()
                announce(it.message ?: context.getString(R.string.customize_failed))
                refresh()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.customize_title)) },
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
            // ---------- 总开关 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.customize_switch),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.customize_switch_summary),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = guarding,
                            onCheckedChange = { toggle(it) },
                            enabled = !busy,
                        )
                    }
                    Text(
                        text = when {
                            busy -> stringResource(R.string.customize_working)
                            guarding -> stringResource(R.string.customize_state_on)
                            else -> stringResource(R.string.customize_state_off)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (guarding) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------- 配置：指定应用 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.customize_config),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.customize_config_summary),
                        style = MaterialTheme.typography.bodySmall
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (targetPkg.isBlank()) stringResource(R.string.customize_not_selected)
                                else targetLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (targetPkg.isNotBlank()) {
                                Text(
                                    targetPkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        OutlinedButton(onClick = { showPicker = true }) {
                            Text(stringResource(R.string.customize_choose_app))
                        }
                    }
                }
            }

            // ---------- 安装包状态 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.customize_apk_state),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = when {
                                !present -> stringResource(R.string.customize_apk_missing)
                                visible -> stringResource(R.string.customize_state_visible)
                                else -> stringResource(R.string.customize_state_hidden)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                !present -> MaterialTheme.colorScheme.error
                                visible -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.tertiary
                            }
                        )
                    }
                    Text(
                        text = when {
                            !present -> stringResource(R.string.customize_state_missing_hint)
                            visible -> stringResource(R.string.customize_state_visible_hint)
                            else -> stringResource(R.string.customize_state_hidden_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---------- 诊断 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.customize_debug),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        stringResource(R.string.customize_debug_summary),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { debugAction("probe") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.customize_debug_probe)) }
                    OutlinedButton(
                        onClick = { debugAction("revive") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.customize_debug_revive)) }
                    OutlinedButton(
                        onClick = { debugAction("status") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.customize_debug_status)) }
                }
            }

            // ---------- 输出 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.customize_output),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = log.ifBlank { stringResource(R.string.customize_no_output) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp)
                            .padding(top = 2.dp)
                    )
                }
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.customize_choose_app)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    items(apps) { app ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    targetPkg = app.packageName
                                    targetLabel = app.label
                                    showPicker = false
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            }
        )
    }
}
