package me.weishu.kernelsu.ui.screen.cardkey

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * XhovoSU 卡密校验（联网）。
 *
 * 卡密由服务器生成并入库，面具每次进入前向服务器确认：
 *
 *   POST {API_BASE}/api/license/verify  { code, deviceId }
 *   -> { ok, message, expiresAt }
 *
 * 服务器按 deviceId 做一机一码绑定：首次验证即绑定，之后换设备会返回 403。
 * 卡密被删除后立即失效，且服务端保留该码，永远不会第二次发出来。
 *
 * 服务端地址可在运行时覆盖（SharedPreferences 的 api_base），默认走
 * 内置地址，方便换域名或换端口而不用重新打包。
 */
object CardKeyApi {

    private const val PREFS = "xhovo_su_cardkey"
    private const val KEY_API_BASE = "api_base"

    /** 服务器 443 上的自签证书，等域名与正式证书就绪后可换成真实域名。 */
    private const val DEFAULT_API_BASE = "https://82.158.231.234"

    /**
     * 自签证书 X.509 DER 的 SHA-256。握手时只认这一张，换证书必须改这里，
     * 否则请求会被拒绝——这比信任所有证书安全，能挡住中间人。
     */
    private const val PINNED_CERT_SHA256 =
        "b74f7256f0c4f4773b6c49237b6deae1b88817fd555116d7cefece1bcb78ec8d"

    /**
     * 服务器上 nginx 只给 wutong.linux.cn 这个 Host 配了反代，用 IP 访问时
     * 需要手动带上 Host 头，否则会落到 default server 返回 404。
     */
    private const val SERVER_HOST_HEADER = "wutong.linux.cn"

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** 只信任服务器那一张自签证书，握手时比对 DER 的 SHA-256。 */
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val certificate = chain.firstOrNull()
                ?: throw CertificateException("服务器没有提供证书")
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            if (hex != PINNED_CERT_SHA256) {
                throw CertificateException("服务器证书与内置指纹不一致，可能被劫持")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val client by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // 证书是签给 IP 的自签证书，主机名由上面的指纹校验替代。
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    data class Outcome(
        val ok: Boolean,
        val code: Int,
        val msg: String,
        val expireAt: String? = null,
        val heartbeat: Int? = null,
        /** true 表示压根没连上服务器，和「服务器明确拒绝」要分开提示。 */
        val networkError: Boolean = false,
    )

    /** 设备指纹：SHA-256(Android ID)，与服务器绑定时使用的就是这一串。 */
    val deviceFingerprint: String by lazy {
        val androidId = runCatching {
            Settings.Secure.getString(ksuApp.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty().trim().lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("xhovo-device-v1:$androidId".toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun apiBase(context: Context = ksuApp): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_API_BASE, DEFAULT_API_BASE)
        .orEmpty()
        .trimEnd('/')
        .ifBlank { DEFAULT_API_BASE }

    fun setApiBase(value: String, context: Context = ksuApp) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_BASE, value.trim().trimEnd('/'))
            .apply()
    }

    fun savedCode(context: Context = ksuApp): String =
        CardKeyStore.rememberedKey(context).orEmpty()

    suspend fun authenticate(code: String): Outcome = withContext(Dispatchers.IO) {
        val normalized = normalize(code)
        if (normalized.isEmpty()) return@withContext Outcome(false, 400, "请输入卡密")
        verifyRemote(normalized)
    }

    suspend fun verify(code: String? = null): Outcome = withContext(Dispatchers.IO) {
        val target = normalize(code ?: savedCode())
        if (target.isEmpty()) return@withContext Outcome(false, 400, "请输入卡密")
        verifyRemote(target)
    }

    suspend fun activate(code: String): Outcome = authenticate(code)

    /** 没有常驻心跳：每次进入都验一次，避免后台常驻连接。 */
    fun startHeartbeat(intervalSeconds: Int) = Unit

    fun stopHeartbeat() = Unit

    private fun verifyRemote(code: String): Outcome {
        val url = "${apiBase()}/api/license/verify"
        val payload = JSONObject()
            .put("code", code)
            .put("deviceId", deviceFingerprint)
            .toString()

        return runCatching {
            val builder = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(jsonType))
            // 直连 IP 时补上站点 Host，让 nginx 命中反向代理的 server 块。
            if (url.contains("82.158.231.234")) builder.header("Host", SERVER_HOST_HEADER)
            val request = builder.build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    return@use Outcome(false, -1, "服务器没有返回内容（HTTP ${response.code}）", networkError = true)
                }
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: return@use Outcome(false, -1, "服务器返回了无法解析的内容", networkError = true)
                val ok = response.isSuccessful && json.optBoolean("ok", false)
                val message = json.optString("message", "").ifBlank {
                    if (ok) "验证成功" else "验证失败（HTTP ${response.code}）"
                }
                val expire = json.optString("expiresAt", "")
                Outcome(
                    ok = ok,
                    code = response.code,
                    msg = message,
                    expireAt = expire.takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }.getOrElse { error ->
            Outcome(
                ok = false,
                code = -1,
                msg = "无法连接卡密服务器：${error.message ?: error.javaClass.simpleName}",
                networkError = true,
            )
        }
    }

    /** 去掉前缀、分隔符与空白并大写，服务器按同样规则归一化。 */
    private fun normalize(input: String): String {
        val upper = input.trim().uppercase(Locale.ROOT)
        val body = if (upper.startsWith("XHOVO")) upper.substring(5) else upper
        return body.filter { it.isLetterOrDigit() }
    }
}
