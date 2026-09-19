package me.weishu.kernelsu.ui.screen.terminal

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.createRootShell

private const val PWD_MARKER = "__NIGHT_PWD__"

@Composable
fun TerminalPager(bottomPadding: Dp) {
    val context = LocalContext.current
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("night_background_uri", uri.toString())
            .apply()
    }
    var command by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("/") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runCommand() {
        val requested = command.trim()
        if (requested.isEmpty() || running) return
        command = ""
        output += "\nroot@night:$cwd # $requested\n"
        running = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val stdout = ArrayList<String>()
                val stderr = ArrayList<String>()
                val script = "cd ${shellQuote(cwd)} && { $requested; }; printf '\\n$PWD_MARKER'; pwd"
                val code = createRootShell(globalMnt = true).newJob()
                    .add(script)
                    .to(stdout, stderr)
                    .exec()
                    .code
                Triple(stdout, stderr, code)
            }
            val stdoutText = result.first.joinToString("\n")
            val markerAt = stdoutText.lastIndexOf(PWD_MARKER)
            val visible = if (markerAt >= 0) stdoutText.substring(0, markerAt).trimEnd() else stdoutText
            if (markerAt >= 0) {
                stdoutText.substring(markerAt + PWD_MARKER.length).trim().lineSequence().firstOrNull()
                    ?.takeIf { it.startsWith('/') }
                    ?.let { cwd = it }
            }
            output += buildString {
                if (visible.isNotBlank()) append(visible).append('\n')
                if (result.second.isNotEmpty()) append(result.second.joinToString("\n")).append('\n')
                if (result.third != 0) append("[exit ${result.third}]\n")
            }
            running = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = bottomPadding + 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.terminal_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.terminal_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { backgroundPicker.launch(arrayOf("image/*")) }) {
            Text("${stringResource(R.string.night_background)} · ${stringResource(R.string.night_background_choose)}")
        }
        Card(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.76f)
            ),
        ) {
            Text(
                text = output.ifBlank { stringResource(R.string.terminal_welcome) },
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                color = Color(0xFFD8FFE1),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("root@night:$cwd") },
            placeholder = { Text(stringResource(R.string.terminal_hint)) },
            singleLine = false,
            maxLines = 3,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { output = "" }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.terminal_clear))
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = ::runCommand, enabled = command.isNotBlank() && !running, modifier = Modifier.weight(1f)) {
                Text(if (running) "…" else stringResource(R.string.terminal_run))
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
