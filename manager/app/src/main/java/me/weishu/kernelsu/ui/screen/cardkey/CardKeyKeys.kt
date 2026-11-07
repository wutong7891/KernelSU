package me.weishu.kernelsu.ui.screen.cardkey

/**
 * XhovoSU 卡密校验密钥。
 *
 * 14 个字符只有 70 bit，放不下不可伪造的签名，所以卡密用的是截断 HMAC：
 * 签发端与面具共用这一个密钥。它能挡住手滑输错和随手瞎猜，挡不住有人把面具
 * 拆开把密钥翻出来——要真正不可伪造，卡密就得回到几十个字符。
 *
 * 这里放的是随仓库发布的开发密钥；正式发版时 CI 会用
 * XHOVO_CARDKEY_SECRET_B64 覆盖本文件。
 */
internal object CardKeyKeys {
    const val SECRET_BASE64: String = "pYl041w4GAutoyMHHXsKtdxnAwbJpRbcw4f0nSD59ZE="
}
