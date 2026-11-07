package me.weishu.kernelsu.ui.screen.cardkey

import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp

/**
 * XhovoSU 独家卡密的校验引擎。
 *
 * 卡密是自包含的 ECDSA P-256 令牌，由签发端离线签名，管理器只做本机验签：
 * 没有账号、没有接口、没有可被下线的后端，断网也能用。
 *
 * 令牌 76 字节，Crockford Base32 编码，`XHOVO-` 前缀：
 *
 * | 偏移 | 长度 | 含义                                                   |
 * |------|------|--------------------------------------------------------|
 * | 0    | 1    | 版本，当前 0x02                                         |
 * | 1    | 1    | 标志位：bit0 绑定设备，bit1 永久有效                     |
 * | 2    | 3    | 流水号，大端                                            |
 * | 5    | 4    | 到期时间，大端 Unix 秒；永久卡为 0                       |
 * | 9    | 3    | 设备标签：设备指纹的前 3 字节                            |
 * | 12   | 64   | 签名：SHA-256 ECDSA，覆盖前 12 字节，r || s             |
 *
 * 签名只覆盖载荷，因此分组符、大小写、前后空白都不影响校验结果。
 */
object CardKeyApi {

    private const val KEY_LAST_SEEN = "last_seen_epoch"

    private const val PREFIX = "XHOVO"
    private const val LEGACY_PREFIX = "NIGHT"
    private const val VERSION = 0x02
    private const val PAYLOAD_SIZE = 12
    private const val SIGNATURE_SIZE = 64
    private const val TOKEN_SIZE = PAYLOAD_SIZE + SIGNATURE_SIZE

    private const val FLAG_DEVICE_BOUND = 0x01
    private const val FLAG_PERMANENT = 0x02

    /** 允许的时钟回拨量，超过即认为设备时间被改过。 */
    private const val CLOCK_SKEW_SECONDS = 2L * 24 * 60 * 60

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val publicKey by lazy {
        val der = Base64.decode(CardKeyKeys.PUBLIC_KEY_DER_BASE64, Base64.DEFAULT)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }

    /** 一次校验的结果，字段与旧版网络验证保持同名，便于界面复用。 */
    data class Outcome(
        val ok: Boolean,
        val code: Int,
        val msg: String,
        val expireAt: String? = null,
        val heartbeat: Int? = null,
        /** true 表示卡密本身没问题、但读取本机状态失败（极罕见）。 */
        val networkError: Boolean = false,
    )

    data class Claims(
        val serial: Int,
        val permanent: Boolean,
        val deviceBound: Boolean,
        val deviceTag: String,
        val notAfter: Long,
    )

    /** 设备指纹：SHA-256(Android ID)，管理器界面与签发器都显示这一串。 */
    val deviceFingerprint: String by lazy {
        val androidId = runCatching {
            Settings.Secure.getString(ksuApp.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty().trim().lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("xhovo-device-v1:$androidId".toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun deviceTag(): String = deviceFingerprint.take(6)

    fun keyFingerprint(): String = runCatching {
        val der = Base64.decode(CardKeyKeys.PUBLIC_KEY_DER_BASE64, Base64.DEFAULT)
        MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .substring(0, 16)
            .uppercase(Locale.ROOT)
    }.getOrDefault("unknown")

    /** 已记住的卡密，用于预填输入框。 */
    fun savedCode(context: Context = ksuApp): String =
        CardKeyStore.rememberedKey(context).orEmpty()

    suspend fun authenticate(code: String): Outcome = withContext(Dispatchers.Default) {
        if (code.isBlank()) return@withContext Outcome(false, 400, "请输入卡密")
        evaluate(code)
    }

    suspend fun verify(code: String? = null): Outcome = withContext(Dispatchers.Default) {
        val target = code ?: CardKeyStore.rememberedKey(ksuApp)
        if (target.isNullOrBlank()) return@withContext Outcome(false, 400, "请输入卡密")
        evaluate(target)
    }

    /** 离线模式下首次绑定不需要服务器参与，等同一次校验。 */
    suspend fun activate(code: String): Outcome = withContext(Dispatchers.Default) {
        if (code.isBlank()) return@withContext Outcome(false, 400, "请输入卡密")
        evaluate(code)
    }

    /** 没有服务端就没有心跳；保留接口形状，避免调用方改动。 */
    fun startHeartbeat(intervalSeconds: Int) = Unit

    fun stopHeartbeat() = Unit

    fun claimsOf(code: String): Claims? = runCatching { parse(code) }.getOrNull()

    private fun evaluate(rawCode: String): Outcome {
        val claims = runCatching { parse(rawCode) }.getOrElse { error ->
            return Outcome(false, 404, error.message ?: "卡密无效")
        }

        val now = System.currentTimeMillis() / 1000
        if (claims.deviceBound && claims.deviceTag != deviceTag()) {
            return Outcome(
                false,
                403,
                "该卡密已绑定设备 ${claims.deviceTag.uppercase(Locale.ROOT)}，本机是 ${deviceTag().uppercase(Locale.ROOT)}",
            )
        }
        if (!claims.permanent && claims.notAfter <= now) {
            return Outcome(false, 403, "卡密已于 ${formatTime(claims.notAfter)} 过期")
        }

        val prefs = ksuApp.getSharedPreferences(CardKeyStore.PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L)
        if (lastSeen > 0 && now + CLOCK_SKEW_SECONDS < lastSeen) {
            return Outcome(false, 403, "系统时间早于上次校验时间，请校准后重试")
        }
        prefs.edit().putLong(KEY_LAST_SEEN, now).apply()

        return Outcome(
            ok = true,
            code = 0,
            msg = "校验通过",
            expireAt = if (claims.permanent) "永久有效" else formatTime(claims.notAfter),
        )
    }

    private fun parse(rawCode: String): Claims {
        val body = normalize(rawCode)
        require(body.isNotEmpty()) { "请输入卡密" }
        val token = decodeBase32(body) ?: throw IllegalArgumentException("卡密格式无效，请确认复制完整")
        if (token.size != TOKEN_SIZE) {
            throw IllegalArgumentException("卡密长度无效（需要 $TOKEN_SIZE 字节，实际 ${token.size} 字节）")
        }
        val payload = token.copyOfRange(0, PAYLOAD_SIZE)
        val signature = token.copyOfRange(PAYLOAD_SIZE, TOKEN_SIZE)
        if ((payload[0].toInt() and 0xFF) != VERSION) {
            throw IllegalArgumentException("卡密版本不受支持")
        }
        if (!verifySignature(payload, signature)) {
            throw IllegalArgumentException("卡密签名无效，可能被修改或伪造")
        }
        return readClaims(payload)
    }

    private fun readClaims(payload: ByteArray): Claims {
        val flags = payload[1].toInt() and 0xFF
        val serial = ((payload[2].toInt() and 0xFF) shl 16) or
            ((payload[3].toInt() and 0xFF) shl 8) or
            (payload[4].toInt() and 0xFF)
        val notAfter = ((payload[5].toLong() and 0xFF) shl 24) or
            ((payload[6].toLong() and 0xFF) shl 16) or
            ((payload[7].toLong() and 0xFF) shl 8) or
            (payload[8].toLong() and 0xFF)
        return Claims(
            serial = serial,
            permanent = (flags and FLAG_PERMANENT) != 0,
            deviceBound = (flags and FLAG_DEVICE_BOUND) != 0,
            deviceTag = (9..11).joinToString("") { "%02x".format(payload[it].toInt() and 0xFF) },
            notAfter = notAfter,
        )
    }

    private fun verifySignature(payload: ByteArray, signature: ByteArray): Boolean = runCatching {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(payload)
        verifier.verify(rawToDer(signature))
    }.getOrDefault(false)

    /** 去掉前缀、分隔符与空白，统一大写。旧品牌前缀也接受，方便平滑迁移。 */
    private fun normalize(input: String): String {
        val upper = input.trim().uppercase(Locale.ROOT)
        val body = when {
            upper.startsWith(PREFIX) -> upper.removePrefix(PREFIX)
            upper.startsWith(LEGACY_PREFIX) -> upper.removePrefix(LEGACY_PREFIX)
            else -> upper
        }
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

    private fun rawToDer(signature: ByteArray): ByteArray {
        val r = derInteger(signature.copyOfRange(0, 32))
        val s = derInteger(signature.copyOfRange(32, 64))
        val body = r + s
        return byteArrayOf(0x30.toByte(), body.size.toByte()) + body
    }

    private fun formatTime(epochSeconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))
}
