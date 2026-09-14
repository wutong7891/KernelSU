package me.weishu.kernelsu.license

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@Composable
fun ActivationScreen(androidId: String, onActivate: (String) -> Boolean) {
    var activationCode by remember { mutableStateOf("") }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("YipaSU 激活", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text("请复制下面的 Android ID，在 YipaSU 签发器中生成激活码。")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = androidId,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Android ID") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    clipboard.setText(AnnotatedString(androidId))
                    Toast.makeText(context, "Android ID 已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制 Android ID") }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = activationCode,
                    onValueChange = { activationCode = it },
                    label = { Text("激活码") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    if (!onActivate(activationCode)) {
                        Toast.makeText(context, "激活码无效或不属于此设备", Toast.LENGTH_LONG).show()
                    }
                }) { Text("激活并进入 YipaSU") }
            }
        }
    }
}
