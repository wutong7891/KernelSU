package me.weishu.kernelsu.ui.activation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.util.Base64
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
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Night licence verification.
 *
 * Licences are self-contained ECDSA P-256 tokens signed offline by the issuer
 * tooling. Verification runs entirely on the device: the Manager never opens a
 * socket, and there is no licence server to reach, leak or take down.
 *
 * Token layout (76 bytes, Crockford Base32 encoded, `NIGHT-` prefixed):
 *
 * | offset | size | field                                                   |
 * |--------|------|---------------------------------------------------------|
 * | 0      | 1    | format version, currently 0x02                          |
 * | 1      | 1    | flags: bit0 device bound, bit1 never expires            |
 * | 2      | 3    | serial, big endian                                      |
 * | 5      | 4    | not-after, big endian unix seconds (0 when permanent)   |
 * | 9      | 3    | device tag: first 3 bytes of the device fingerprint     |
 * | 12     | 64   | signature: SHA-256 ECDSA over bytes 0..11, r || s       |
 *
 * The signature covers the payload only, so a licence can be shortened, padded
 * or re-grouped with separators without invalidating it.
 */
object NightActivation {
    private const val PREFS = "night_activation"
    private const val KEY_CODE = "night_license_code_v2"
    private const val KEY_LAST_SEEN = "night_license_last_seen_v2"

    private const val PREFIX = "NIGHT"
    private const val VERSION = 0x02
    private const val PAYLOAD_SIZE = 12
    private const val SIGNATURE_SIZE = 64
    private const val TOKEN_SIZE = PAYLOAD_SIZE + SIGNATURE_SIZE

    private const val FLAG_DEVICE_BOUND = 0x01
    private const val FLAG_PERMANENT = 0x02

    /** Tolerated backwards clock drift before the licence is treated as tampered. */
    private const val CLOCK_SKEW_SECONDS = 2L * 24 * 60 * 60

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    data class Result(
        val ok: Boolean,
        val message: String,
        val expiresLabel: String? = null,
    )

    private data class Claims(
        val serial: Int,
        val permanent: Boolean,
        val deviceBound: Boolean,
        val deviceTag: String,
        val notAfter: Long,
    )

    private val publicKey by lazy {
        val der = Base64.decode(NightLicenseKeys.PUBLIC_KEY_DER_BASE64, Base64.DEFAULT)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }

    fun androidId(context: Context): String = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID,
    ).orEmpty().trim().lowercase(Locale.ROOT)

    fun deviceHash(context: Context): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("night-device-v1:${androidId(context)}".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** First three bytes of the device fingerprint, the value baked into bound licences. */
    fun deviceTag(context: Context): String = deviceHash(context).take(6)

    fun savedCode(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_CODE, "")
        .orEmpty()

    /**
     * Short fingerprint of the embedded verification key. The issuer shows the
     * same value, so a mismatch is visible before any licence is generated.
     */
    fun keyFingerprint(): String = runCatching {
        val der = Base64.decode(NightLicenseKeys.PUBLIC_KEY_DER_BASE64, Base64.DEFAULT)
        MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)
            .uppercase(Locale.ROOT)
    }.getOrDefault("unknown")

    suspend fun activate(context: Context, code: String): Result =
        withContext(Dispatchers.Default) { evaluate(context, code, persist = true) }

    suspend fun isActivated(context: Context): Boolean =
        withContext(Dispatchers.Default) {
            val code = savedCode(context)
            if (code.isBlank()) return@withContext false
            evaluate(context, code, persist = false).ok
        }

    private fun evaluate(context: Context, input: String, persist: Boolean): Result {
        val normalized = normalize(input)
        if (normalized.isEmpty()) return Result(false, "请输入 Night 卡密")

        val token = decodeBase32(normalized)
            ?: return Result(false, "卡密格式无效，请确认复制完整")
        if (token.size != TOKEN_SIZE) {
            return Result(false, "卡密长度无效（需要 ${TOKEN_SIZE} 字节，实际 ${token.size} 字节）")
        }

        val payload = token.copyOfRange(0, PAYLOAD_SIZE)
        val signature = token.copyOfRange(PAYLOAD_SIZE, TOKEN_SIZE)
        if ((payload[0].toInt() and 0xFF) != VERSION) {
            return Result(false, "卡密版本不受支持，请使用新格式卡密")
        }
        if (!verifySignature(payload, signature)) {
            return Result(false, "卡密签名无效，可能被修改或伪造")
        }

        val claims = readClaims(payload)
        val now = System.currentTimeMillis() / 1000

        if (claims.deviceBound && claims.deviceTag != deviceTag(context)) {
            return Result(
                false,
                "该卡密已绑定设备 ${claims.deviceTag.uppercase(Locale.ROOT)}，当前设备为 ${deviceTag(context).uppercase(Locale.ROOT)}",
            )
        }

        if (!claims.permanent && claims.notAfter <= now) {
            return Result(false, "卡密已于 ${formatTime(claims.notAfter)} 过期")
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L)
        if (lastSeen > 0 && now + CLOCK_SKEW_SECONDS < lastSeen) {
            return Result(false, "系统时间早于上次校验时间，请校准时间后重试")
        }

        val expiresLabel = if (claims.permanent) "永久有效" else formatTime(claims.notAfter)
        if (persist) {
            prefs.edit()
                .putString(KEY_CODE, input.trim())
                .putLong(KEY_LAST_SEEN, now)
                .apply()
        } else if (now > lastSeen) {
            prefs.edit().putLong(KEY_LAST_SEEN, now).apply()
        }

        return Result(
            ok = true,
            message = "Night 卡密验证通过（剩余 ${if (claims.permanent) "永久" else formatRemaining(claims.notAfter - now)}）",
            expiresLabel = expiresLabel,
        )
    }

    private fun readClaims(payload: ByteArray): Claims {
        val serial = ((payload[2].toInt() and 0xFF) shl 16) or
            ((payload[3].toInt() and 0xFF) shl 8) or
            (payload[4].toInt() and 0xFF)
        val notAfter = ((payload[5].toLong() and 0xFF) shl 24) or
            ((payload[6].toLong() and 0xFF) shl 16) or
            ((payload[7].toLong() and 0xFF) shl 8) or
            (payload[8].toLong() and 0xFF)
        val deviceTag = (9..11).joinToString("") { "%02x".format(payload[it] and 0xFF) }
        val flags = payload[1].toInt() and 0xFF
        return Claims(
            serial = serial,
            permanent = (flags and FLAG_PERMANENT) != 0,
            deviceBound = (flags and FLAG_DEVICE_BOUND) != 0,
            deviceTag = deviceTag,
            notAfter = notAfter,
        )
    }

    private fun verifySignature(payload: ByteArray, signature: ByteArray): Boolean = runCatching {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(payload)
        verifier.verify(rsToDer(signature))
    }.getOrDefault(false)

    /** Strips the human readable prefix and every separator, then upper-cases. */
    private fun normalize(input: String): String {
        val upper = input.trim().uppercase(Locale.ROOT)
        val body = if (upper.startsWith(PREFIX)) upper.removePrefix(PREFIX) else upper
        return body.filter { it.isLetterOrDigit() }
    }

    private fun alphabetIndex(char: Char): Int? = when (char) {
        in '0'..'9' -> char - '0'
        'O' -> 0
        'I', 'L' -> 1
        in 'A'..'Z' -> ALPHABET.indexOf(char).takeIf { it >= 0 }
        else -> null
    }

    private fun decodeBase32(text: String): ByteArray? {
        val output = ByteArrayOutputStream(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (char in text) {
            val value = alphabetIndex(char) ?: return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output.write((buffer shr bits) and 0xFF)
            }
        }
        // Leftover bits must be zero padding, otherwise the token was truncated.
        if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) return null
        return output.toByteArray()
    }

    private fun derInteger(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.size - 1 && value[start].toInt() == 0) start++
        val stripped = value.copyOfRange(start, value.size)
        val padded = if ((stripped[0].toInt() and 0x80) != 0) byteArrayOf(0.toByte()) + stripped else stripped
        return byteArrayOf(0x02.toByte(), padded.size.toByte()) + padded
    }

    private fun rsToDer(signature: ByteArray): ByteArray {
        val r = derInteger(signature.copyOfRange(0, 32))
        val s = derInteger(signature.copyOfRange(32, 64))
        val body = r + s
        return byteArrayOf(0x30.toByte(), body.size.toByte()) + body
    }

    private fun formatTime(epochSeconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))

    private fun formatRemaining(seconds: Long): String {
        val days = seconds / 86400
        if (days >= 1) return "$days 天"
        val hours = seconds / 3600
        if (hours >= 1) return "$hours 小时"
        return "${(seconds / 60).coerceAtLeast(1)} 分钟"
    }
}

@Composable
fun NightActivationScreen(onActivated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fingerprint = remember { NightActivation.deviceHash(context) }
    val keyFingerprint = remember { NightActivation.keyFingerprint() }
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
        Text("离线卡密验证", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(20.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC182133)),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("设备指纹", color = Color(0xFF98A6C2))
                Text(fingerprint, color = Color.White, fontFamily = FontFamily.Monospace)
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
                    placeholder = { Text("NIGHT-XXXXX-XXXXX-…") },
                    isError = error.isNotBlank(),
                    supportingText = { if (error.isNotBlank()) Text(error) },
                    minLines = 2,
                    maxLines = 4,
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
                ) { Text(if (loading) "正在验证…" else "验证并进入 Night") }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("卡密由签发器离线签名，本机校验，不联网、不上传任何数据。", color = Color(0xFF8190AA))
        Text("签发公钥 $keyFingerprint", color = Color(0xFF5F6C85), fontFamily = FontFamily.Monospace)
    }
}
