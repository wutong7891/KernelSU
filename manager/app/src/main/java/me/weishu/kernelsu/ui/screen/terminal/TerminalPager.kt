package me.weishu.kernelsu.ui.screen.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.createRootShell

private const val PWD_MARKER = "__NIGHT_PWD__"

private data class RootEntry(val name: String, val directory: Boolean)
private data class RootResult(val code: Int, val stdout: String, val stderr: String)

@Composable
fun TerminalPager(bottomPadding: Dp) {
    var cwd by remember { mutableStateOf("/data/adb") }
    var entries by remember { mutableStateOf(emptyList<RootEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var pendingScript by remember { mutableStateOf<String?>(null) }
    var terminalVisible by remember { mutableStateOf(false) }
    var terminalOutput by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        error = ""
        runCatching { listRootDirectory(cwd) }
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "无法读取目录" }
        loading = false
    }

    fun run(commandText: String) {
        if (running || commandText.isBlank()) return
        terminalVisible = true
        running = true
        terminalOutput += "\nroot@night:$cwd # $commandText\n"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runRoot("cd ${shellQuote(cwd)} && { $commandText; }; printf '\n$PWD_MARKER'; pwd")
            }
            val markerAt = result.stdout.lastIndexOf(PWD_MARKER)
            val visible = if (markerAt >= 0) result.stdout.substring(0, markerAt).trimEnd() else result.stdout
            if (markerAt >= 0) {
                result.stdout.substring(markerAt + PWD_MARKER.length).trim().lineSequence().firstOrNull()
                    ?.takeIf { it.startsWith('/') }?.let { cwd = it }
            }
            terminalOutput += buildString {
                if (visible.isNotBlank()) append(visible).append('\n')
                if (result.stderr.isNotBlank()) append(result.stderr).append('\n')
                if (result.code != 0) append("[exit ${result.code}]\n")
            }
            running = false
        }
    }

    LaunchedEffect(cwd, refreshKey, terminalVisible) {
        if (!terminalVisible) reload()
    }

    pendingScript?.let { script ->
        AlertDialog(
            onDismissRequest = { pendingScript = null },
            title = { Text("执行脚本") },
            text = { Text("是否执行此脚本？\n$script") },
            dismissButton = { TextButton(onClick = { pendingScript = null }) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    pendingScript = null
                    run("sh ${shellQuote(script)}")
                }) { Text("确定") }
            },
        )
    }

    if (terminalVisible) {
        Column(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPadding + 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { terminalVisible = false; refreshKey++ }) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
                Text("Night Root Terminal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                text = terminalOutput.ifBlank { "root@night:$cwd #" },
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(10.dp))
                    .padding(10.dp).verticalScroll(rememberScrollState()),
                color = Color(0xFFE8E8E8),
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("root@night:$cwd") },
                singleLine = true,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedButton(onClick = { terminalOutput = "" }, modifier = Modifier.weight(1f)) { Text("清屏") }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { val value = command; command = ""; run(value) },
                    enabled = command.isNotBlank() && !running,
                    modifier = Modifier.weight(1f),
                ) { Text(if (running) "执行中…" else "执行") }
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPadding + 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("执行", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = { refreshKey++ }) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新") }
        }
        Row(
            Modifier.fillMaxWidth().clickable {
                if (cwd != "/") cwd = cwd.substringBeforeLast('/').ifBlank { "/" }
            }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "上级目录")
            Spacer(Modifier.width(8.dp))
            Text(cwd, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
        }
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            error.isNotBlank() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error, color = MaterialTheme.colorScheme.error)
                Text("请确认已安装 v2 签名的 Night 管理器且内核已授权。")
                Button(onClick = { refreshKey++ }) { Text("重试") }
            }
            else -> LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.name }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val full = if (cwd == "/") "/${entry.name}" else "$cwd/${entry.name}"
                            if (entry.directory) cwd = full else pendingScript = full
                        }.padding(horizontal = 6.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (entry.directory) Icons.Rounded.Folder else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = if (entry.directory) Color(0xFFFFC857) else Color(0xFF4EA1FF),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Button(onClick = { terminalVisible = true }, modifier = Modifier.fillMaxWidth()) { Text("打开交互终端") }
    }
}

private suspend fun listRootDirectory(path: String): List<RootEntry> = withContext(Dispatchers.IO) {
    val quoted = shellQuote(path)
    val command = """
        test "${'$'}(id -u)" = 0 || { echo 'Night 未获得 Root 管理器授权' >&2; exit 126; }
        cd $quoted || exit 1
        for item in .[!.]* ..?* *; do
          [ -e "${'$'}item" ] || continue
          if [ -d "${'$'}item" ]; then printf 'D\t%s\n' "${'$'}item"; else printf 'F\t%s\n' "${'$'}item"; fi
        done
    """.trimIndent()
    val result = runRoot(command)
    if (result.code != 0) error(result.stderr.ifBlank { "Root 目录读取失败（${result.code}）" })
    result.stdout.lineSequence().mapNotNull { line ->
        val type = line.substringBefore('\t', "")
        val name = line.substringAfter('\t', "")
        if (name.isBlank() || name == "." || name == "..") null else RootEntry(name, type == "D")
    }.sortedWith(compareBy<RootEntry> { !it.directory }.thenBy { it.name.lowercase() }).toList()
}

private fun runRoot(command: String): RootResult {
    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val result = createRootShell(globalMnt = true).use { shell ->
        shell.newJob().add(command).to(stdout, stderr).exec()
    }
    return RootResult(result.code, stdout.joinToString("\n"), stderr.joinToString("\n"))
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
