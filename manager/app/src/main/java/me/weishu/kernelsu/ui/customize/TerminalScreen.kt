package me.weishu.kernelsu.ui.customize

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator

/**
 * 简易 root 终端。
 *
 * 不做 PTY 双向流（那需要 InteractiveShell，改动面太大），
 * 而是维护一个「当前目录」状态，每条命令前拼上 cd，
 * 效果上等同于在同一个 shell 里连续执行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var cwd by remember { mutableStateOf("/") }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun append(text: String) {
        output = if (output.isBlank()) text else output + "\n" + text
    }

    fun run(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || busy) return

        scope.launch {
            busy = true
            append("$cwd # $cmd")

            val result = withContext(Dispatchers.IO) { Terminal.run(cwd, cmd) }
            result.newDir?.let { cwd = it }
            if (result.text.isNotBlank()) append(result.text)
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.terminal_title)) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = cwd,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "pwd" to "pwd",
                            "ls" to "ls -la",
                            "up" to "cd ..",
                            "who" to "id",
                        ).forEach { (label, cmd) ->
                            FilterChip(
                                selected = false,
                                onClick = { run(cmd) },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                        TextButton(onClick = { output = "" }) {
                            Text(stringResource(R.string.terminal_clear), fontSize = 11.sp)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.terminal_input)) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        run(input)
                        input = ""
                    },
                    enabled = !busy
                ) { Text(stringResource(R.string.terminal_run)) }
                TextButton(onClick = { input = "ls -la" }) {
                    Text(stringResource(R.string.terminal_list), fontSize = 12.sp)
                }
            }

            Card(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = output.ifBlank { stringResource(R.string.terminal_hint) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

/** 终端执行器。 */
object Terminal {

    data class Result(val text: String, val newDir: String?)

    fun run(cwd: String, command: String): Result {
        val trimmed = command.trim()

        // cd 单独处理：不执行 shell，只改状态
        if (trimmed == "cd" || trimmed.startsWith("cd ")) {
            val arg = trimmed.removePrefix("cd").trim().ifBlank { "/" }
            val target = if (arg.startsWith("/")) arg else "$cwd/$arg"
            val resolved = resolve(target)
            return if (resolved != null) {
                Result("", resolved)
            } else {
                Result("cd: no such directory: $arg", null)
            }
        }

        val full = "cd " + q(cwd) + " && " + trimmed + " 2>&1"
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val ok = me.weishu.kernelsu.ui.util.getRootShell()
            .newJob()
            .add(full)
            .to(out, err)
            .exec()
            .isSuccess

        val text = (out + err).joinToString("\n").trim()
        return Result(text.ifBlank { if (ok) "" else "（命令失败）" }, null)
    }

    private fun resolve(path: String): String? {
        val out = ArrayList<String>()
        me.weishu.kernelsu.ui.util.getRootShell()
            .newJob()
            .add("cd " + q(path) + " 2>/dev/null && pwd")
            .to(out, null)
            .exec()
        return out.firstOrNull { it.startsWith("/") }?.trim()
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
