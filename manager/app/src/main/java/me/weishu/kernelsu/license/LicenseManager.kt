package me.weishu.kernelsu.license

import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object LicenseManager {
    private const val PREFS = "yipasu_license"
    private const val CODE = "activation_code"
    private const val PREFIX = "YIPASU1."
    private const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEhbOnJD1Ug7WqLyZ9YTC/wO4TCLJh85vndZAsDgSTPaB/amdiO7CPhYG1E0eCFuF84BcMPuHMiZCGOQwkZC/6iA=="

    fun androidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    fun isActivated(context: Context): Boolean {
        val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CODE, null) ?: return false
        return verify(androidId(context), code)
    }

    fun activate(context: Context, code: String): Boolean {
        val normalized = code.trim()
        if (!verify(androidId(context), normalized)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(CODE, normalized).apply()
        return true
    }

    private fun verify(androidId: String, code: String): Boolean = runCatching {
        if (androidId.isBlank() || !code.startsWith(PREFIX)) return false
        val signatureBytes = Base64.decode(
            code.removePrefix(PREFIX),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val publicKeyBytes = Base64.decode(PUBLIC_KEY, Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(payload(androidId))
            verify(signatureBytes)
        }
    }.getOrDefault(false)

    private fun payload(androidId: String): ByteArray =
        "YipaSU|1|$androidId".toByteArray(StandardCharsets.UTF_8)
}
