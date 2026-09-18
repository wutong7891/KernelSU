package com.jinfuwei.luoyu.signer

import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var androidId by remember { mutableStateOf("") }
            var activationCode by remember { mutableStateOf("") }
            val clipboard = LocalClipboardManager.current
            val context = LocalContext.current

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF37E6FF),
                    secondary = Color(0xFF7DFFB2),
                    background = Color(0xFF050811),
                    surface = Color(0xFF0B1220),
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "YipaSU // 激活码签发器",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("输入目标 YipaSU 管理器显示的 Android ID。私钥内置在此签发器中，请勿公开分发。")
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = androidId,
                            onValueChange = { androidId = it.trim() },
                            label = { Text("目标 Android ID") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(onClick = {
                            activationCode = if (androidId.isBlank()) "" else sign(androidId)
                            if (activationCode.isBlank()) {
                                Toast.makeText(context, "请输入 Android ID", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("签发激活码") }
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = activationCode,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("激活码") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = activationCode.isNotBlank(),
                            onClick = {
                                clipboard.setText(AnnotatedString(activationCode))
                                Toast.makeText(context, "激活码已复制", Toast.LENGTH_SHORT).show()
                            }
                        ) { Text("复制激活码") }
                    }
                }
            }
        }
    }

    private fun sign(androidId: String): String {
        check(BuildConfig.LICENSE_PRIVATE_KEY.isNotBlank()) {
            "YIPASU_LICENSE_PRIVATE_KEY is not configured"
        }
        val keyBytes = Base64.decode(BuildConfig.LICENSE_PRIVATE_KEY, Base64.DEFAULT)
        val privateKey = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update("YipaSU|1|$androidId".toByteArray(StandardCharsets.UTF_8))
            sign()
        }
        return "YIPASU1." + Base64.encodeToString(
            signature,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}
