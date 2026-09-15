package me.weishu.kernelsu.ui.screen.fileexecutor

import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.getRootShell

private const val MAX_VISIBLE_ENTRIES = 500
private const val MAX_TERMINAL_CHARS = 200_000

private data class RootFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
)

@Composable
fun FileExecutorScreen() {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf("/") }
    var pathInput by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf(emptyList<RootFileEntry>()) }
    var selectedFile by remember { mutableStateOf<RootFileEntry?>(null) }
    var arguments by remember { mutableStateOf("") }
    var terminalOutput by remember { mutableStateOf("") }
    var directoryError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var confirmExecution by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(currentPath, refreshKey) {
        loading = true
        directoryError = null
        selectedFile = null
        val result = withContext(Dispatchers.IO) { listRootDirectory(currentPath) }
        entries = result.getOrElse {
            directoryError = it.message ?: it.javaClass.simpleName
            emptyList()
        }
        loading = false
    }

    fun navigate(path: String) {
        val normalized = normalizePath(path)
        pathInput = normalized
        currentPath = normalized
    }

    fun executeSelected() {
        val file = selectedFile ?: return
        confirmExecution = false
        running = true
        terminalOutput = "\$ ${file.path}${if (arguments.isBlank()) "" else " $arguments"}\n\n"
        scope.launch {
            val execution = withContext(Dispatchers.IO) { executeRootFile(file.path, arguments) }
            terminalOutput += execution.output.takeLast(MAX_TERMINAL_CHARS)
            terminalOutput += "\n\n" + execution.status
            running = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "YipaSU · " + androidx.compose.ui.res.stringResource(R.string.file_executor)) },
                navigationIcon = {
                    IconButton(onClick = navigator::pop) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }, enabled = !loading) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.tertiaryContainer,
                            )
                        )
                    )
                    .padding(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.file_executor),
                            modifier = Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.file_executor_select_tip),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.file_executor_path)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                FilledTonalButton(onClick = { navigate(parentPath(currentPath)) }) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null)
                    Text(androidx.compose.ui.res.stringResource(R.string.file_executor_up))
                }
                Button(onClick = { navigate(pathInput) }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.file_executor_go))
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = directoryError
                                ?: androidx.compose.ui.res.stringResource(R.string.file_executor_empty),
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(entries, key = { it.path }) { entry ->
                            RootFileRow(
                                entry = entry,
                                selected = selectedFile?.path == entry.path,
                                onClick = {
                                    if (entry.isDirectory) navigate(entry.path) else selectedFile = entry
                                },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                        if (entries.size >= MAX_VISIBLE_ENTRIES) {
                            item {
                                Text(
                                    text = androidx.compose.ui.res.stringResource(R.string.file_executor_limited),
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = selectedFile != null || terminalOutput.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selectedFile?.let { file ->
                        Text(
                            text = file.path,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = arguments,
                                onValueChange = { arguments = it },
                                modifier = Modifier.weight(1f),
                                enabled = !running,
                                singleLine = true,
                                label = { Text(androidx.compose.ui.res.stringResource(R.string.file_executor_arguments)) },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            )
                            Button(
                                onClick = { confirmExecution = true },
                                enabled = !running,
                            ) {
                                if (running) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                }
                                Text(
                                    text = androidx.compose.ui.res.stringResource(
                                        if (running) R.string.file_executor_running else R.string.file_executor_execute
                                    )
                                )
                            }
                        }
                    }

                    if (terminalOutput.isNotEmpty()) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.file_executor_output),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(156.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = androidx.compose.ui.graphics.Color(0xFF101318),
                        ) {
                            LazyColumn(modifier = Modifier.padding(14.dp)) {
                                item {
                                    Text(
                                        text = terminalOutput,
                                        color = androidx.compose.ui.graphics.Color(0xFFD8F8D0),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (confirmExecution) {
        AlertDialog(
            onDismissRequest = { confirmExecution = false },
            icon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.file_executor_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(androidx.compose.ui.res.stringResource(R.string.file_executor_confirm_message))
                    Text(
                        text = selectedFile?.path.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = ::executeSelected) {
                    Text(androidx.compose.ui.res.stringResource(R.string.file_executor_execute))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExecution = false }) {
                    Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RootFileRow(
    entry: RootFileEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.padding(start = 16.dp)) {
            Text(
                text = entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = entry.path,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private data class ExecutionResult(val output: String, val status: String)

private fun listRootDirectory(path: String): Result<List<RootFileEntry>> = runCatching {
    val quotedPath = shellQuote(path)
    val directories = runPathQuery("find $quotedPath -mindepth 1 -maxdepth 1 -type d -print0 2>/dev/null | base64")
    val files = runPathQuery("find $quotedPath -mindepth 1 -maxdepth 1 ! -type d -print0 2>/dev/null | base64")
    (directories.map { RootFileEntry(it, displayName(it), true) } +
        files.map { RootFileEntry(it, displayName(it), false) })
        .sortedWith(compareBy<RootFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
        .take(MAX_VISIBLE_ENTRIES)
}

private fun runPathQuery(command: String): List<String> {
    val stdout = arrayListOf<String>()
    val stderr = arrayListOf<String>()
    val result = getRootShell().newJob().add(command).to(stdout, stderr).exec()
    if (!result.isSuccess && stdout.isEmpty()) {
        error(stderr.joinToString("\n").ifBlank { "Root shell returned ${result.code}" })
    }
    val encoded = stdout.joinToString("").trim()
    if (encoded.isEmpty()) return emptyList()
    return Base64.decode(encoded, Base64.DEFAULT)
        .toString(Charsets.UTF_8)
        .split('\u0000')
        .filter { it.isNotBlank() }
}

private fun executeRootFile(path: String, arguments: String): ExecutionResult {
    val stdout = arrayListOf<String>()
    val stderr = arrayListOf<String>()
    val rawArguments = arguments.trim()
    val command = buildString {
        append("target=")
        append(shellQuote(path))
        append("; if [ -x \"\$target\" ]; then \"\$target\"")
        if (rawArguments.isNotEmpty()) append(" ").append(rawArguments)
        append("; else /system/bin/sh \"\$target\"")
        if (rawArguments.isNotEmpty()) append(" ").append(rawArguments)
        append("; fi")
    }
    val result = getRootShell().newJob().add(command).to(stdout, stderr).exec()
    val combined = buildString {
        if (stdout.isNotEmpty()) append(stdout.joinToString("\n"))
        if (stderr.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append(stderr.joinToString("\n"))
        }
    }
    return ExecutionResult(
        output = combined.ifBlank { "(no output)" },
        status = "Process exited with code ${result.code}",
    )
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun normalizePath(value: String): String {
    val trimmed = value.trim().ifEmpty { "/" }
    val absolute = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    return if (absolute == "/") absolute else absolute.trimEnd('/')
}

private fun parentPath(path: String): String {
    if (path == "/") return "/"
    return path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
}

private fun displayName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
