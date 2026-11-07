package me.weishu.kernelsu.ui.screen.cardkey

import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp

/**
 * XhovoSU 独家卡密的校验引擎（14 位格式）。
 *
 * 卡密 14 个 Crockford Base32 字符 = 70 bit，分成两段：
 *
 * | 段 | 长度 | 内容 |
 * |----|------|------|
 * | 前 8 字符 | 40 bit | 流水号 16 + 到期日 12 + 设备标签 12 |
 * | 后 6 字符 | 30 bit | 前 40 bit 的 HMAC-SHA256 截断值 |
 *
 * 到期日以 2024-01-01 为第 0 天，单位天，0 表示永久。设备标签为 0 表示通用卡，
 * 否则是设备指纹（SHA-256 十六进制）前 3 位的数值。
 *
 * 校验完全在本机完成，不联网。强度说明见 [CardKeyKeys]：30 bit 的截断 MAC 只能
 * 防误输入，不是防逆向的授权体系。
 */
object CardKeyApi {

    private const val KEY_LAST_SEEN = "last_seen_epoch"

    private const val PREFIX = "XHOVO"
    private const val CODE_LENGTH = 14
    private const val PAYLOAD_CHARS = 8
    private const val CHECK_CHARS = 6

    /** 到期日历的第 0 天：2024-01-01（UTC）。 */
    private const val DAY_EPOCH = 1704067200L

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 超过这个回拨量就认为设备时间被改过。 */
    private const val CLOCK_SKEW_SECONDS = 2L * 24 * 60 * 60

    private val secret: ByteArray by lazy {
        Base64.decode(CardKeyKeys.SECRET_BASE64, Base64.DEFAULT)
    }

    data class Outcome(
        val ok: Boolean,
        val code: Int,
        val msg: String,
        val expireAt: String? = null,
        val heartbeat: Int? = null,
        val networkError: Boolean = false,
    )

    data class Claims(
        val serial: Int,
        val permanent: Boolean,
        val deviceBound: Boolean,
        val deviceTag: String,
        val notAfter: Long,
    )

    /** 设备指纹：SHA-256(Android ID)，卡密页显示的就是这一串。 */
    val deviceFingerprint: String by lazy {
        val androidId = runCatching {
            Settings.Secure.getString(ksuApp.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty().trim().lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("xhovo-device-v1:$androidId".toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** 12 bit 设备标签，与卡密里内嵌的值比较。 */
    fun deviceTag(): Int = deviceFingerprint.take(3).toInt(16) and 0xFFF

    fun deviceTagHex(): String = deviceTag().toString(16).padStart(3, '0')

    fun keyFingerprint(): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(secret)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .substring(0, 16)
            .uppercase(Locale.ROOT)
    }.getOrDefault("unknown")

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

    suspend fun activate(code: String): Outcome = withContext(Dispatchers.Default) {
        if (code.isBlank()) return@withContext Outcome(false, 400, "请输入卡密")
        evaluate(code)
    }

    fun startHeartbeat(intervalSeconds: Int) = Unit

    fun stopHeartbeat() = Unit

    fun claimsOf(code: String): Claims? = runCatching { parse(code) }.getOrNull()

    private fun evaluate(rawCode: String): Outcome {
        val claims = runCatching { parse(rawCode) }.getOrElse { error ->
            return Outcome(false, 404, error.message ?: "卡密无效")
        }

        val now = System.currentTimeMillis() / 1000
        if (claims.deviceBound && claims.deviceTag != deviceTagHex()) {
            return Outcome(
                false,
                403,
                "该卡密已绑定设备 ${claims.deviceTag.uppercase(Locale.ROOT)}，本机是 ${deviceTagHex().uppercase(Locale.ROOT)}",
            )
        }
        if (!claims.permanent && claims.notAfter <= now) {
            return Outcome(false, 403, "卡密已于 ${formatDate(claims.notAfter)} 过期")
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
            expireAt = if (claims.permanent) "永久有效" else formatDate(claims.notAfter),
        )
    }

    private fun parse(rawCode: String): Claims {
        val body = normalize(rawCode)
        require(body.length == CODE_LENGTH) { "卡密应为 $CODE_LENGTH 位，当前 ${body.length} 位" }

        val payload = decode(body.substring(0, PAYLOAD_CHARS))
            ?: throw IllegalArgumentException("卡密含有无效字符")
        val checksum = decode(body.substring(PAYLOAD_CHARS))
            ?: throw IllegalArgumentException("卡密含有无效字符")
        if (mac(payload) != checksum) {
            throw IllegalArgumentException("卡密校验失败，请确认没有抄错")
        }
        return readClaims(payload)
    }

    private fun readClaims(payload: Long): Claims {
        val serial = ((payload ushr 24) and 0xFFFF).toInt()
        val dayCode = ((payload ushr 12) and 0xFFF).toInt()
        val tag = (payload and 0xFFF).toInt()
        return Claims(
            serial = serial,
            permanent = dayCode == 0,
            deviceBound = tag != 0,
            deviceTag = tag.toString(16).padStart(3, '0'),
            notAfter = if (dayCode == 0) 0L else DAY_EPOCH + dayCode * 86400L,
        )
    }

    /** 前 40 bit 的 HMAC-SHA256，取前 4 字节再右移 2 位，得到 30 bit。 */
    private fun mac(payload: Long): Long {
        val input = ByteArray(5)
        input[0] = ((payload ushr 32) and 0xFF).toByte()
        input[1] = ((payload ushr 24) and 0xFF).toByte()
        input[2] = ((payload ushr 16) and 0xFF).toByte()
        input[3] = ((payload ushr 8) and 0xFF).toByte()
        input[4] = (payload and 0xFF).toByte()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val digest = mac.doFinal(input)
        val head = ((digest[0].toInt() and 0xFF) shl 24) or
            ((digest[1].toInt() and 0xFF) shl 16) or
            ((digest[2].toInt() and 0xFF) shl 8) or
            (digest[3].toInt() and 0xFF)
        return (head ushr 2).toLong() and 0x3FFFFFFF
    }

    private fun normalize(input: String): String {
        val upper = input.trim().uppercase(Locale.ROOT)
        val body = if (upper.startsWith(PREFIX)) upper.substring(PREFIX.length) else upper
        return body.filter { it.isLetterOrDigit() }
    }

    private fun alphabetIndex(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        'O' -> 0
        'I', 'L' -> 1
        in 'A'..'Z' -> ALPHABET.indexOf(char)
        else -> -1
    }

    /** 把若干个 5 bit 字符还原成数值；非法字符返回 null。 */
    private fun decode(text: String): Long? {
        var value = 0L
        for (char in text) {
            val digit = alphabetIndex(char)
            if (digit < 0) return null
            value = value * 32 + digit
        }
        return value
    }

    private fun formatDate(epochSeconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochSeconds * 1000))
}
