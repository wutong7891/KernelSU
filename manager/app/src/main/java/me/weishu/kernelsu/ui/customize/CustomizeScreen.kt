package me.weishu.kernelsu.ui.customize

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var watching by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(true) }
    var mirrorBytes by remember { mutableStateOf(0L) }
    var log by remember { mutableStateOf("") }

    var target by remember { mutableStateOf("") }
    var targetLabel by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("voldown") }
    var mode by remember { mutableStateOf("long") }
    var count by remember { mutableStateOf(3) }
    var longMs by remember { mutableStateOf(1500) }

    var deviceCode by remember { mutableStateOf("") }
    var launchTargetLabels by remember { mutableStateOf(emptyList<String>()) }
    var contact by remember { mutableStateOf("") }
    var contactState by remember { mutableStateOf("") }

    fun announce(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun refresh() {
        scope.launch {
            val cfg = withContext(Dispatchers.IO) { CustomizeController.readConfig() }
            // 目标应用统一从规则推导，不在定制页单独选
            val fromRules = withContext(Dispatchers.IO) { CustomizeController.resolveTarget(context) }
            target = if (fromRules.isNotBlank()) fromRules else cfg.target
            key = cfg.key
            mode = cfg.mode
            count = cfg.count
            longMs = cfg.longMs
            if (contact.isBlank()) contact = cfg.contact
            targetLabel = if (target.isBlank()) "" else runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(target, 0)).toString()
            }.getOrDefault(target)

            watching = withContext(Dispatchers.IO) { CustomizeController.isWatching() }
            installed = withContext(Dispatchers.IO) { CustomizeController.isManagerInstalled(context) }
            mirrorBytes = withContext(Dispatchers.IO) { CustomizeController.mirrorSize() }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        log = withContext(Dispatchers.IO) { CustomizeController.readLog() }
        deviceCode = withContext(Dispatchers.IO) { DeviceCode.compute() }
        launchTargetLabels = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            CustomizeController.launchTargets(context).map { pkg ->
                runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
            }
        }
    }

    fun toggle(enabled: Boolean) {
        if (busy) return
        if (enabled && target.isBlank()) {
            announce(context.getString(R.string.customize_need_app))
            return
        }
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                if (enabled) CustomizeController.arm(context, target, key, mode, count, longMs, contact)
                else CustomizeController.disarm()
            }
            busy = false
            result.onSuccess {
                log = it
                announce(context.getString(if (enabled) R.string.customize_armed else R.string.customize_disarmed))
                refresh()
            }.onFailure {
                log = it.message.orEmpty()
                announce(it.message ?: context.getString(R.string.customize_failed))
                refresh()
            }
        }
    }

    fun saveConfig() {
        if (busy) return
        // 不再要求目标应用非空：配一半也要能存，否则用户填了手势和通讯录
        // 却没选应用时点保存会直接被弹回，等于白填。
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                CustomizeController.saveConfig(context, target, key, mode, count, longMs, contact)
            }
            busy = false
            result.onSuccess {
                log = it
                announce(it)
            }.onFailure {
                log = it.message.orEmpty()
                announce(it.message ?: context.getString(R.string.customize_failed))
            }
            refresh()
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
                        Switch(checked = watching, onCheckedChange = { toggle(it) }, enabled = !busy)
                    }
                    Text(
                        text = when {
                            busy -> stringResource(R.string.customize_working)
                            watching -> stringResource(R.string.customize_state_on)
                            !installed -> stringResource(R.string.customize_state_uninstalled)
                            else -> stringResource(R.string.customize_state_off)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (watching) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { saveConfig() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.customize_save)) }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { navigator.push(Route.XhovoRules) },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.customize_rules_entry)) }
                        OutlinedButton(
                            onClick = { navigator.push(Route.XhovoTerminal) },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.customize_terminal_entry)) }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.customize_config), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.customize_target_from_rules), style = MaterialTheme.typography.bodySmall)

                    if (target.isBlank()) {
                        Text(
                            stringResource(R.string.customize_no_rule_target),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(
                            onClick = { navigator.push(Route.XhovoRules) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.customize_go_rules)) }
                    } else {
                        Text(
                            targetLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            target,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        if (launchTargetLabels.size > 1) {
                            Text(
                                stringResource(R.string.customize_more_targets) + " " + launchTargetLabels.drop(1).joinToString("、"),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.customize_contact), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.customize_contact_summary), style = MaterialTheme.typography.bodySmall)

                    OutlinedTextField(
                        value = contact,
                        onValueChange = { contact = it; contactState = "" },
                        label = { Text(stringResource(R.string.customize_contact_name)) },
                        placeholder = { Text(stringResource(R.string.customize_contact_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (contact.isBlank()) {
                                    contactState = ""
                                    return@OutlinedButton
                                }
                                scope.launch {
                                    val found = withContext(Dispatchers.IO) {
                                        CustomizeController.contactExists(contact)
                                    }
                                    contactState = if (found) {
                                        context.getString(R.string.customize_contact_found)
                                    } else {
                                        context.getString(R.string.customize_contact_missing)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.customize_contact_check)) }
                    }

                    if (contactState.isNotBlank()) {
                        Text(
                            contactState,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (contactState == context.getString(R.string.customize_contact_found))
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }

                    if (contact.isBlank()) {
                        Text(
                            stringResource(R.string.customize_contact_off),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.customize_gesture), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.customize_gesture_summary), style = MaterialTheme.typography.bodySmall)

                    Text(stringResource(R.string.customize_key), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = key == "voldown",
                            onClick = { key = "voldown" },
                            label = { Text(stringResource(R.string.customize_key_down)) }
                        )
                        FilterChip(
                            selected = key == "volup",
                            onClick = { key = "volup" },
                            label = { Text(stringResource(R.string.customize_key_up)) }
                        )
                    }

                    Text(stringResource(R.string.customize_mode), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mode == "long",
                            onClick = { mode = "long" },
                            label = { Text(stringResource(R.string.customize_mode_long)) }
                        )
                        FilterChip(
                            selected = mode == "click",
                            onClick = { mode = "click" },
                            label = { Text(stringResource(R.string.customize_mode_click)) }
                        )
                    }

                    if (mode == "long") {
                        Text(
                            stringResource(R.string.customize_long_ms) + "  " + longMs + "ms",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(800, 1200, 1500, 2000, 3000).forEach { ms ->
                                FilterChip(
                                    selected = longMs == ms,
                                    onClick = { longMs = ms },
                                    label = { Text(ms.toString()) }
                                )
                            }
                        }
                    } else {
                        Text(
                            stringResource(R.string.customize_count) + "  " + count,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(2, 3, 4, 5).forEach { n ->
                                FilterChip(
                                    selected = count == n,
                                    onClick = { count = n },
                                    label = { Text(n.toString()) }
                                )
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.customize_apk_state), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (mirrorBytes > 0) (mirrorBytes / 1024).toString() + " KB"
                            else stringResource(R.string.customize_apk_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mirrorBytes > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        CustomizeController.apkPath(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.customize_device_code), style = MaterialTheme.typography.titleSmall)
                    Text(
                        deviceCode.ifBlank { "…" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.customize_output), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = {
                            scope.launch {
                                log = withContext(Dispatchers.IO) { CustomizeController.readLog() }
                                refresh()
                            }
                        }) { Text(stringResource(R.string.customize_refresh)) }
                    }
                    Text(
                        text = log.ifBlank { stringResource(R.string.customize_no_output) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)
                    )
                }
            }
        }
    }
}
