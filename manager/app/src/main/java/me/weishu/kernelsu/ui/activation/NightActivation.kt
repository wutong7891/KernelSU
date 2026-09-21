package me.weishu.kernelsu.ui.activation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale

object NightActivation {
    private const val PREFS = "night_activation"
    private const val KEY_CODE = "activation_code"
    private const val CODE_PREFIX = "N1."
    private const val PUBLIC_KEY_BASE64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxPm4IldYf9tF/Y0UWLi+2EbCMqSexmTpOHitEFtkCzLydcBguhgXg1qjapu1SqkmF2HEkD7xKl9zDRqu0b9ExK2YwSwmuPJIOjli+5il0Vc9/2CYKcLU3htMd8juCT7e6mVz31mJ6llf42yM+iCCPQ+JvQer5uCACyLGy8A1ArF9IKt8IZFlsb9r09/WZcdbLv1p0ASFRBLzVwv3JgT13oSQp0x1I63pZ/eeJzcjCzmmrDPsgsIXBXsKJxLyJFAzTWL7Xj0fZS8TkI1awyIUTMgNi+XO3Tn3y9cWxu1JG5niwAQVp1bjM9olG9tYDEvNAO5WXRGsRHI3keJWGs/xfQIDAQAB"

    fun androidId(context: Context): String = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID,
    ).orEmpty().trim().lowercase(Locale.ROOT)

    private fun payload(androidId: String) = "Night|1|${androidId.trim().lowercase(Locale.ROOT)}"

    fun verify(androidId: String, code: String): Boolean = runCatching {
        val normalized = code.trim().replace("\n", "").replace("\r", "")
        require(normalized.startsWith(CODE_PREFIX))
        val signatureBytes = Base64.getUrlDecoder().decode(normalized.removePrefix(CODE_PREFIX))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_BASE64)),
        )
        Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update(payload(androidId).toByteArray(Charsets.UTF_8))
            verify(signatureBytes)
        }
    }.getOrDefault(false)

    fun activate(context: Context, code: String): Boolean {
        val id = androidId(context)
        if (!verify(id, code)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CODE, code.trim()).apply()
        return true
    }

    fun isActivated(context: Context): Boolean {
        val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CODE, null) ?: return false
        return verify(androidId(context), code)
    }
}

@Composable
fun NightActivationScreen(onActivated: () -> Unit) {
    val context = LocalContext.current
    val androidId = remember { NightActivation.androidId(context) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NIGHT", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Color(0xFF99D3FF))
        Text("设备激活", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(20.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC182133)),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Android ID", color = Color(0xFF98A6C2))
                Text(androidId, color = Color.White, fontFamily = FontFamily.Monospace)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Night Android ID", androidId))
                        Toast.makeText(context, "Android ID 已复制", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("复制设备 ID") }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("激活码") },
                    minLines = 3,
                    isError = error,
                    supportingText = { if (error) Text("激活码与本机 Android ID 不匹配") },
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = code.isNotBlank(),
                    onClick = {
                        if (NightActivation.activate(context, code)) onActivated() else error = true
                    },
                ) { Text("激活并进入 Night") }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("激活在本机离线验证，不会上传 Android ID。", color = Color(0xFF8190AA))
    }
}
