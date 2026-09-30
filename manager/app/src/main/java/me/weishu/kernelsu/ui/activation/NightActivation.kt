package me.weishu.kernelsu.ui.activation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

object NightActivation {
    private const val PREFS = "night_activation"
    private const val KEY_CODE = "night_license_code"
    private val ENDPOINTS = listOf(
        "https://wtlyf-license-center.wtlyf-night.workers.dev/api/v1/night",
        "https://wtlyf-night-license.pages.dev/api/v1/night",
    )
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    data class Result(val ok: Boolean, val message: String)

    fun androidId(context: Context): String = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID,
    ).orEmpty().trim().lowercase(Locale.ROOT)

    fun deviceHash(context: Context): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("night-device-v1:${androidId(context)}".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun savedCode(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_CODE, "")
        .orEmpty()

    private suspend fun request(context: Context, action: String, code: String): Result =
        withContext(Dispatchers.IO) {
            val normalized = code.trim().replace(Regex("\\s+"), "")
            val json = JSONObject()
                .put("code", normalized)
                .put("deviceHash", deviceHash(context))
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            coroutineScope {
                val results = Channel<Result?>(ENDPOINTS.size)
                val jobs = ENDPOINTS.map { endpoint ->
                    launch {
                        val attempt = runCatching {
                    val request = Request.Builder()
                        .url("$endpoint/$action")
                        .post(body)
                        .build()
                    client.newCall(request).execute().use { response ->
                        val raw = response.body?.string().orEmpty()
                        val data = JSONObject(raw.ifBlank { "{}" })
                        if (response.code >= 500) error("Night server returned ${response.code}")
                        val message = data.optString("message", if (response.isSuccessful) "验证成功" else "验证失败")
                        if (response.isSuccessful && data.optBoolean("ok")) {
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .edit().putString(KEY_CODE, normalized).apply()
                            Result(true, message)
                        } else {
                            // Keep the locally saved code on transient edge/server failures so the
                            // user can retry without having to enter the license again. Only an
                            // explicitly expired or disabled license should clear local activation.
                            val error = data.optString("error")
                            if (response.code == 403 && error == "expired_code") {
                                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                    .edit().remove(KEY_CODE).apply()
                            }
                            Result(false, message)
                        }
                        }
                        results.send(attempt.getOrNull())
                    }
                }
                repeat(ENDPOINTS.size) {
                    results.receive()?.let { result ->
                        jobs.forEach { job -> job.cancel() }
                        results.close()
                        return@coroutineScope result
                    }
                }
                results.close()
                Result(false, "无法连接 Night 卡密服务器，请切换网络后重试")
            }
        }

    suspend fun activate(context: Context, code: String): Result = request(context, "activate", code)

    suspend fun isActivated(context: Context): Boolean {
        val code = savedCode(context)
        if (code.isBlank()) return false
        return request(context, "check", code).ok
    }
}

@Composable
fun NightActivationScreen(onActivated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fingerprint = remember { NightActivation.deviceHash(context) }
    var code by remember { mutableStateOf(NightActivation.savedCode(context)) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NIGHT", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Color(0xFF99D3FF))
        Text("独立卡密验证", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(20.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC182133)),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Night 设备指纹", color = Color(0xFF98A6C2))
                Text(fingerprint.take(16) + "…", color = Color.White, fontFamily = FontFamily.Monospace)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Night device fingerprint", fingerprint))
                        Toast.makeText(context, "设备指纹已复制", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("复制设备指纹") }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = "" },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Night 卡密") },
                    placeholder = { Text("NIGHT-XXXXX-XXXXX-XXXXX-XXXXX") },
                    isError = error.isNotBlank(),
                    supportingText = { if (error.isNotBlank()) Text(error) },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = code.isNotBlank() && !loading,
                    onClick = {
                        loading = true
                        error = ""
                        scope.launch {
                            val result = NightActivation.activate(context, code)
                            loading = false
                            if (result.ok) onActivated() else error = result.message
                        }
                    },
                ) { Text(if (loading) "正在验证…" else "激活并进入 Night") }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("一台设备只能绑定一张 Night 卡密；管理员可在独立后台解绑。", color = Color(0xFF8190AA))
    }
}
