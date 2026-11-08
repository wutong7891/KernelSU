package me.weishu.kernelsu.ui.customize

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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator

private data class AppEntry(val packageName: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var rules by remember { mutableStateOf(emptyList<Rule>()) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var editing by remember { mutableStateOf<Int>(-1) }
    var showPicker by remember { mutableStateOf(false) }
    var confPreview by remember { mutableStateOf("") }

    fun labelOf(pkg: String): String {
        if (pkg.isBlank()) return ""
        return apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
    }

    fun reload() {
        scope.launch {
            rules = withContext(Dispatchers.IO) { RulesStore.load(context) }
            confPreview = withContext(Dispatchers.IO) { RulesStore.readConfLines() }
        }
    }

    LaunchedEffect(Unit) {
        reload()
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun persist(list: List<Rule>) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { RulesStore.save(context, list) }
            r.onSuccess {
                rules = list
                confPreview = withContext(Dispatchers.IO) { RulesStore.readConfLines() }
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "保存失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title)) },
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
                    Text(stringResource(R.string.rules_summary), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = {
                            val next = rules + Rule()
                            persist(next)
                            editing = next.lastIndex
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.rules_add)) }
                }
            }

            if (rules.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.rules_empty),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            rules.forEachIndexed { index, rule ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    labelOf(rule.pkg).ifBlank { stringResource(R.string.rules_no_app) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    buildString {
                                        append(
                                            when {
                                                rule.key.isBlank() -> "进入即触发"
                                                rule.key == "volup" -> "音量+"
                                                else -> "音量−"
                                            },
                                        )
                                        if (rule.key.isNotBlank()) {
                                            append(" · ")
                                            append(if (rule.mode == "long") "长按${rule.longMs}ms" else "连点${rule.count}次")
                                        }
                                        append(" · ")
                                        append(if (rule.action == "script") "脚本" else "拉起面具")
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { on ->
                                    persist(rules.toMutableList().also { it[index] = rule.copy(enabled = on) })
                                }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editing = index }) {
                                Text(stringResource(R.string.rules_edit))
                            }
                            TextButton(onClick = {
                                persist(rules.toMutableList().also { it.removeAt(index) })
                            }) { Text(stringResource(R.string.rules_delete)) }
                        }
                    }
                }
            }

            // 规则文件预览
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.rules_conf), style = MaterialTheme.typography.titleSmall)
                    Text(
                        confPreview.ifBlank { stringResource(R.string.rules_conf_empty) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                    )
                }
            }
        }
    }

    // ---------- 编辑对话框 ----------
    if (editing >= 0 && editing < rules.size) {
        val rule = rules[editing]
        fun update(new: Rule) {
            rules = rules.toMutableList().also { it[editing] = new }
        }

        AlertDialog(
            onDismissRequest = { editing = -1 },
            title = { Text(stringResource(R.string.rules_edit)) },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.rules_app), style = MaterialTheme.typography.labelMedium)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            labelOf(rule.pkg).ifBlank { stringResource(R.string.rules_no_app) },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { showPicker = true }) {
                            Text(stringResource(R.string.rules_pick))
                        }
                    }

                    Text(stringResource(R.string.rules_key), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = rule.key.isBlank(),
                            onClick = { update(rule.copy(key = "")) },
                            label = { Text(stringResource(R.string.rules_key_enter)) }
                        )
                        FilterChip(
                            selected = rule.key == "voldown",
                            onClick = { update(rule.copy(key = "voldown")) },
                            label = { Text(stringResource(R.string.customize_key_down)) }
                        )
                        FilterChip(
                            selected = rule.key == "volup",
                            onClick = { update(rule.copy(key = "volup")) },
                            label = { Text(stringResource(R.string.customize_key_up)) }
                        )
                    }

                    if (rule.key.isNotBlank()) {
                        Text(stringResource(R.string.rules_mode), style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = rule.mode == "long",
                                onClick = { update(rule.copy(mode = "long")) },
                                label = { Text(stringResource(R.string.customize_mode_long)) }
                            )
                            FilterChip(
                                selected = rule.mode == "click",
                                onClick = { update(rule.copy(mode = "click")) },
                                label = { Text(stringResource(R.string.customize_mode_click)) }
                            )
                        }

                        if (rule.mode == "long") {
                            Text(
                                stringResource(R.string.customize_long_ms) + "  " + rule.longMs + "ms",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(500, 800, 1200, 1500, 2000, 3000).forEach { ms ->
                                    FilterChip(
                                        selected = rule.longMs == ms,
                                        onClick = { update(rule.copy(longMs = ms)) },
                                        label = { Text(ms.toString()) }
                                    )
                                }
                            }
                        } else {
                            Text(
                                stringResource(R.string.customize_count) + "  " + rule.count,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(2, 3, 4, 5).forEach { n ->
                                    FilterChip(
                                        selected = rule.count == n,
                                        onClick = { update(rule.copy(count = n)) },
                                        label = { Text(n.toString()) }
                                    )
                                }
                            }
                        }
                    }

                    Text(stringResource(R.string.rules_action), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = rule.action == "launch",
                            onClick = { update(rule.copy(action = "launch")) },
                            label = { Text(stringResource(R.string.rules_action_launch)) }
                        )
                        FilterChip(
                            selected = rule.action == "script",
                            onClick = { update(rule.copy(action = "script")) },
                            label = { Text(stringResource(R.string.rules_action_script)) }
                        )
                    }

                    if (rule.action == "script") {
                        OutlinedTextField(
                            value = rule.script,
                            onValueChange = { update(rule.copy(script = it)) },
                            label = { Text(stringResource(R.string.rules_script_path)) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                        OutlinedTextField(
                            value = rule.inputB64,
                            onValueChange = { update(rule.copy(inputB64 = it)) },
                            label = { Text(stringResource(R.string.rules_input)) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            minLines = 2
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    persist(rules)
                    editing = -1
                }) { Text(stringResource(R.string.rules_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = -1 }) { Text("取消") }
            }
        )
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.rules_pick)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(apps) { app ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (editing >= 0 && editing < rules.size) {
                                        rules = rules.toMutableList().also {
                                            it[editing] = it[editing].copy(pkg = app.packageName)
                                        }
                                    }
                                    showPicker = false
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
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
