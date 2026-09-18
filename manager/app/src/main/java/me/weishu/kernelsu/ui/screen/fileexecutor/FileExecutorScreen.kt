package me.weishu.kernelsu.ui.screen.fileexecutor

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

private const val MAX_VISIBLE_ENTRIES = 500
private const val MAX_TERMINAL_CHARS = 200_000
private val TechBackground = Color(0xFF050811)
private val TechSurface = Color(0xFF0B1220)
private val TechCyan = Color(0xFF37E6FF)
private val TechGreen = Color(0xFF7DFFB2)
private val TechText = Color(0xFFE6F7FF)
private val TechMuted = Color(0xFF86A1B5)

private data class RootFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
)

@Composable
fun TerminalPager(bottomInnerPadding: Dp) {
    FileExecutorContent(bottomInnerPadding = bottomInnerPadding, onNavigateBack = null)
}

@Composable
fun FileExecutorScreen() {
    val navigator = LocalNavigator.current
    FileExecutorContent(bottomInnerPadding = 0.dp, onNavigateBack = navigator::pop)
}

@Composable
private fun FileExecutorContent(
    bottomInnerPadding: Dp,
    onNavigateBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val terminalFocusRequester = remember { FocusRequester() }
    val terminalScrollState = rememberScrollState()
    var currentPath by remember { mutableStateOf("/") }
    var pathInput by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf(emptyList<RootFileEntry>()) }
    var selectedFile by remember { mutableStateOf<RootFileEntry?>(null) }
    var showSelectedActions by remember { mutableStateOf(false) }
    var terminalInput by remember { mutableStateOf("") }
    var terminalOutput by remember { mutableStateOf("") }
    var directoryError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var terminalFullscreen by remember { mutableStateOf(true) }
    var commandHistory by remember { mutableStateOf(emptyList<String>()) }
    var historyIndex by remember { mutableStateOf(0) }
    var confirmExecution by remember { mutableStateOf(false) }
    var confirmMoveToAdb by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var terminalProcess by remember { mutableStateOf<Process?>(null) }
    var terminalWriter by remember { mutableStateOf<OutputStreamWriter?>(null) }

    fun appendTerminal(text: String) {
        terminalOutput = (terminalOutput + text).takeLast(MAX_TERMINAL_CHARS)
    }

    fun closeTerminal() {
        runCatching { terminalWriter?.close() }
        runCatching { terminalProcess?.destroy() }
        terminalWriter = null
        terminalProcess = null
        running = false
    }

    fun startRootSession(initialCommand: String? = null) {
        terminalFullscreen = true
        scope.launch {
            try {
                closeTerminal()
                terminalOutput = "YipaSU ROOT TERMINAL\nMT-style interactive session\n\nroot@yipasu:/ # "
                val process = withContext(Dispatchers.IO) { startRootTerminal(context) }
                val writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
                terminalProcess = process
                terminalWriter = writer
                running = true
                scope.launch(Dispatchers.IO) {
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            withContext(Dispatchers.Main) {
                                if (line.startsWith("__YIPASU_EXIT__:")) {
                                    appendTerminal("\n[exit ${line.removePrefix("__YIPASU_EXIT__:")}]\nroot@yipasu:/ # ")
                                } else {
                                    appendTerminal(line + "\n")
                                }
                            }
                        }
                    }
                    val exitCode = process.waitFor()
                    withContext(Dispatchers.Main) {
                        appendTerminal("\n[Root shell exited: $exitCode]\n")
                        terminalWriter = null
                        terminalProcess = null
                        running = false
                    }
                }
                if (initialCommand != null) {
                    withContext(Dispatchers.IO) {
                        writer.write(initialCommand)
                        writer.write("\n")
                        writer.flush()
                    }
                }
            } catch (error: Throwable) {
                appendTerminal("\n${error.message ?: error.javaClass.simpleName}\n")
                closeTerminal()
            }
        }
    }

    fun sendRawTerminalInput(value: String) {
        val writer = terminalWriter ?: return
        scope.launch(Dispatchers.IO) {
            try {
                writer.write(value)
                writer.flush()
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    appendTerminal("\n${error.message ?: "stdin closed"}\n")
                    closeTerminal()
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { closeTerminal() }
    }

    LaunchedEffect(Unit) {
        startRootSession()
    }

    LaunchedEffect(terminalOutput, terminalFullscreen) {
        if (terminalFullscreen) terminalScrollState.animateScrollTo(terminalScrollState.maxValue)
    }

    LaunchedEffect(currentPath, refreshKey) {
        loading = true
        directoryError = null
        selectedFile = null
        showSelectedActions = false
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
        statusMessage = null
    }

    fun executeSelected() {
        val file = selectedFile ?: return
        confirmExecution = false
        showSelectedActions = false
        terminalFullscreen = true
        val command = buildExecutionCommand(file.path)
        val writer = terminalWriter
        if (writer == null || !running) {
            startRootSession(command)
        } else {
            appendTerminal("${file.path}\n")
            scope.launch(Dispatchers.IO) {
                writer.write(command)
                writer.write("\n")
                writer.flush()
            }
        }
    }

    fun sendTerminalInput() {
        val writer = terminalWriter ?: return
        val input = terminalInput
        if (input.isBlank()) return
        terminalInput = ""
        commandHistory = (commandHistory + input).takeLast(100)
        historyIndex = commandHistory.size + 1
        appendTerminal(input + "\n")
        scope.launch(Dispatchers.IO) {
            try {
                writer.write(input)
                writer.write("\n")
                writer.flush()
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    appendTerminal("\n${error.message ?: "stdin closed"}\n")
                    closeTerminal()
                }
            }
        }
    }

    fun browseHistory(delta: Int) {
        if (commandHistory.isEmpty()) return
        historyIndex = (historyIndex + delta).coerceIn(0, commandHistory.lastIndex)
        terminalInput = commandHistory[historyIndex]
        terminalFocusRequester.requestFocus()
        keyboardController?.show()
    }

    fun moveSelectedToAdb() {
        val file = selectedFile ?: return
        confirmMoveToAdb = false
        showSelectedActions = false
        scope.launch {
            val result = withContext(Dispatchers.IO) { moveRootFileToAdb(file.path) }
            statusMessage = result.output.trim()
            if (result.success) {
                navigate("/data/adb")
                refreshKey++
            }
        }
    }

    Scaffold(
        containerColor = TechBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TechBackground,
                    titleContentColor = TechText,
                    navigationIconContentColor = TechCyan,
                    actionIconContentColor = TechCyan,
                ),
                title = {
                    Column {
                        Text(
                            text = if (terminalFullscreen) "YipaSU · ${stringResource(R.string.terminal)}"
                            else stringResource(R.string.file_executor_files),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (terminalFullscreen) "ROOT // LIVE SESSION" else "ROOT FILE MATRIX",
                            color = TechMuted,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                },
                navigationIcon = {
                    when {
                        !terminalFullscreen -> IconButton(onClick = { terminalFullscreen = true }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.file_executor_terminal_back),
                            )
                        }
                        onNavigateBack != null -> IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        }
                    }
                },
                actions = {
                    if (terminalFullscreen) {
                        IconButton(onClick = { terminalFullscreen = false }) {
                            Icon(Icons.Rounded.Folder, contentDescription = stringResource(R.string.file_executor_files))
                        }
                        IconButton(onClick = { startRootSession() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.file_executor_new_session))
                        }
                    } else {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.file_executor_more))
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.file_executor_adb_shortcut)) },
                                    leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        navigate("/data/adb")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.file_executor_refresh)) },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        refreshKey++
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (terminalFullscreen) {
            FullscreenTerminal(
                output = terminalOutput,
                input = terminalInput,
                running = running,
                scrollState = terminalScrollState,
                focusRequester = terminalFocusRequester,
                onInputChanged = { terminalInput = it },
                onSend = ::sendTerminalInput,
                onHistoryPrevious = { browseHistory(-1) },
                onHistoryNext = { browseHistory(1) },
                onTab = { sendRawTerminalInput("\t") },
                onInterrupt = { sendRawTerminalInput("\u0003") },
                onClear = { terminalOutput = "root@yipasu:/ # " },
                onFocusRequest = {
                    terminalFocusRequester.requestFocus()
                    keyboardController?.show()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(bottom = bottomInnerPadding),
            )
        } else {
            FileBrowser(
                currentPath = currentPath,
                pathInput = pathInput,
                entries = entries,
                loading = loading,
                directoryError = directoryError,
                statusMessage = statusMessage,
                selectedPath = selectedFile?.path,
                onPathInputChanged = { pathInput = it },
                onNavigate = ::navigate,
                onSelect = { entry ->
                    if (entry.isDirectory) navigate(entry.path) else {
                        selectedFile = entry
                        showSelectedActions = true
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(bottom = bottomInnerPadding),
            )
        }
    }

    if (showSelectedActions && selectedFile != null) {
        AlertDialog(
            onDismissRequest = { showSelectedActions = false },
            icon = { Icon(Icons.Rounded.InsertDriveFile, contentDescription = null) },
            title = { Text(stringResource(R.string.file_executor_selected_title)) },
            text = { Text(selectedFile?.path.orEmpty(), fontFamily = FontFamily.Monospace) },
            confirmButton = {
                Button(onClick = {
                    showSelectedActions = false
                    confirmExecution = true
                }) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Text(stringResource(R.string.file_executor_execute))
                }
            },
            dismissButton = {
                FilledTonalButton(onClick = {
                    showSelectedActions = false
                    confirmMoveToAdb = true
                }) {
                    Text(stringResource(R.string.file_executor_move_adb))
                }
            },
        )
    }

    if (confirmExecution) {
        AlertDialog(
            onDismissRequest = { confirmExecution = false },
            icon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
            title = { Text(stringResource(R.string.file_executor_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.file_executor_confirm_message))
                    Text(selectedFile?.path.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(onClick = ::executeSelected) { Text(stringResource(R.string.file_executor_execute)) } },
            dismissButton = {
                TextButton(onClick = { confirmExecution = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    if (confirmMoveToAdb) {
        AlertDialog(
            onDismissRequest = { confirmMoveToAdb = false },
            icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
            title = { Text(stringResource(R.string.file_executor_move_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.file_executor_move_confirm_message))
                    Text(selectedFile?.path.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(onClick = ::moveSelectedToAdb) { Text(stringResource(R.string.file_executor_move_adb)) } },
            dismissButton = {
                TextButton(onClick = { confirmMoveToAdb = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun FileBrowser(
    currentPath: String,
    pathInput: String,
    entries: List<RootFileEntry>,
    loading: Boolean,
    directoryError: String?,
    statusMessage: String?,
    selectedPath: String?,
    onPathInputChanged: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onSelect: (RootFileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Brush.verticalGradient(listOf(TechBackground, Color(0xFF071322), TechBackground)))
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = pathInput,
            onValueChange = onPathInputChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.file_executor_path)) },
            leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { onNavigate(pathInput) }) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.file_executor_go))
                }
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TechText,
                unfocusedTextColor = TechText,
                focusedBorderColor = TechCyan,
                unfocusedBorderColor = TechMuted,
                focusedLabelColor = TechCyan,
                unfocusedLabelColor = TechMuted,
                focusedLeadingIconColor = TechCyan,
                unfocusedLeadingIconColor = TechMuted,
                focusedTrailingIconColor = TechCyan,
                unfocusedTrailingIconColor = TechMuted,
                cursorColor = TechCyan,
            ),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { onNavigate(parentPath(currentPath)) }) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null)
                Text(stringResource(R.string.file_executor_up))
            }
            Text(
                text = currentPath,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                color = TechCyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        statusMessage?.let {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = TechGreen.copy(alpha = 0.12f)) {
                Text(
                    text = it,
                    modifier = Modifier.padding(10.dp),
                    color = TechGreen,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            color = TechSurface.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, TechCyan.copy(alpha = 0.24f)),
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TechCyan)
                }
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = directoryError ?: stringResource(R.string.file_executor_empty),
                        modifier = Modifier.padding(24.dp),
                        color = TechMuted,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        RootFileRow(entry = entry, selected = selectedPath == entry.path, onClick = { onSelect(entry) })
                        HorizontalDivider(modifier = Modifier.padding(start = 58.dp), color = TechCyan.copy(alpha = 0.10f))
                    }
                    if (entries.size >= MAX_VISIBLE_ENTRIES) {
                        item {
                            Text(
                                text = stringResource(R.string.file_executor_limited),
                                modifier = Modifier.padding(16.dp),
                                color = TechMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenTerminal(
    output: String,
    input: String,
    running: Boolean,
    scrollState: ScrollState,
    focusRequester: FocusRequester,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onHistoryPrevious: () -> Unit,
    onHistoryNext: () -> Unit,
    onTab: () -> Unit,
    onInterrupt: () -> Unit,
    onClear: () -> Unit,
    onFocusRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Brush.radialGradient(colors = listOf(Color(0xFF0A2630), TechBackground)))
            .clickable(onClick = onFocusRequest)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).background(if (running) TechGreen else TechMuted, RoundedCornerShape(50)),
            )
            Text(
                text = if (running) " ROOT LINK ONLINE" else " ROOT LINK CLOSED",
                color = if (running) TechGreen else TechMuted,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xF2070B10),
            border = BorderStroke(1.dp, TechGreen.copy(alpha = 0.30f)),
        ) {
            Text(
                text = output.ifEmpty { "YipaSU ROOT CONSOLE\n# " },
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(14.dp),
                color = TechGreen,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(onClick = onTab, enabled = running, modifier = Modifier.weight(1f)) {
                Text("TAB", fontFamily = FontFamily.Monospace)
            }
            TextButton(onClick = onHistoryPrevious, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.file_executor_history_previous))
            }
            TextButton(onClick = onHistoryNext, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.file_executor_history_next))
            }
            TextButton(onClick = onInterrupt, enabled = running, modifier = Modifier.weight(1f)) {
                Text("CTRL+C", fontFamily = FontFamily.Monospace)
            }
            TextButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                Text("CLS", fontFamily = FontFamily.Monospace)
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChanged,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            enabled = running,
            singleLine = true,
            leadingIcon = { Text("#", color = TechGreen, fontFamily = FontFamily.Monospace) },
            placeholder = { Text(stringResource(R.string.file_executor_terminal_input)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TechText,
                unfocusedTextColor = TechText,
                disabledTextColor = TechMuted,
                focusedBorderColor = TechGreen,
                unfocusedBorderColor = TechMuted,
                disabledBorderColor = TechMuted.copy(alpha = 0.4f),
                cursorColor = TechGreen,
                focusedPlaceholderColor = TechMuted,
                unfocusedPlaceholderColor = TechMuted,
            ),
        )
    }
}

@Composable
private fun RootFileRow(entry: RootFileEntry, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) TechCyan.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory) TechCyan else TechMuted,
        )
        Column(Modifier.padding(start = 16.dp)) {
            Text(
                text = entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = TechText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = entry.path,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = TechMuted,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private data class MoveResult(val success: Boolean, val output: String)

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

private fun startRootTerminal(context: Context): Process {
    val engine = context.applicationInfo.nativeLibraryDir + "/libksud.so"
    return ProcessBuilder(engine, "debug", "su").redirectErrorStream(true).start()
}

private fun buildExecutionCommand(path: String): String = buildString {
    append("target=")
    append(shellQuote(path))
    append("; if [ -x \"\$target\" ]; then \"\$target\"; else /system/bin/sh \"\$target\"; fi")
    append("; yipasu_code=\$?; echo __YIPASU_EXIT__:\$yipasu_code")
}

private fun moveRootFileToAdb(path: String): MoveResult {
    val stdout = arrayListOf<String>()
    val stderr = arrayListOf<String>()
    val destination = "/data/adb/${displayName(path)}"
    val command = "mkdir -p /data/adb; " +
        "if [ -e ${shellQuote(destination)} ]; then " +
        "echo ${shellQuote("Target already exists: $destination")}; exit 17; " +
        "fi; mv ${shellQuote(path)} /data/adb/"
    val result = getRootShell().newJob().add(command).to(stdout, stderr).exec()
    val combined = buildString {
        if (stdout.isNotEmpty()) append(stdout.joinToString("\n"))
        if (stderr.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append(stderr.joinToString("\n"))
        }
    }
    return MoveResult(
        success = result.isSuccess,
        output = if (result.isSuccess) "Moved to $destination"
        else combined.ifBlank { "Move failed with code ${result.code}" },
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
