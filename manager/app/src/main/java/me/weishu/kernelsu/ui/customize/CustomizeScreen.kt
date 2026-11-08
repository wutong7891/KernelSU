package me.weishu.kernelsu.ui.customize

import android.widget.Toast
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var apkReady by remember { mutableStateOf(false) }
    var apkSize by remember { mutableStateOf(0L) }

    fun announce(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun refreshApk() {
        scope.launch {
            val (ready, size) = withContext(Dispatchers.IO) { CustomizeController.apkInfo() }
            apkReady = ready
            apkSize = size
        }
    }

    fun execute(action: String, doneText: String) {
        if (busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { CustomizeController.run(context, action) }
            busy = false
            result.onSuccess { text ->
                output = text
                announce(doneText)
                refreshApk()
            }.onFailure { error ->
                output = error.message.orEmpty()
                announce(error.message ?: context.getString(R.string.customize_failed))
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshApk()
        scope.launch {
            val status = withContext(Dispatchers.IO) { CustomizeController.run(context, "status") }
            output = status.getOrDefault("")
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
                    Text(
                        stringResource(R.string.customize_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.customize_summary),
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedButton(
                        onClick = { execute("apply", context.getString(R.string.customize_done_apply)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.customize_action_apply)) }

                    OutlinedButton(
                        onClick = { execute("apk", context.getString(R.string.customize_done_apk)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.customize_action_apk)) }

                    OutlinedButton(
                        onClick = { execute("status", context.getString(R.string.customize_done_status)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.customize_action_status)) }

                    OutlinedButton(
                        onClick = { execute("restore", context.getString(R.string.customize_done_restore)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.customize_action_restore)) }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.customize_apk_state),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            if (apkReady) {
                                val kb = apkSize / 1024
                                stringResource(R.string.customize_apk_ready) + "  ${kb}KB"
                            } else {
                                stringResource(R.string.customize_apk_missing)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (apkReady) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        CustomizeController.apkPath(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.customize_output),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = output.ifBlank { stringResource(R.string.customize_no_output) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(12.dp)
                    )
                }
            }
        }
    }
}
