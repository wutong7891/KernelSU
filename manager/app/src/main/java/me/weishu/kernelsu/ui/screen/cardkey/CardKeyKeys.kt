package me.weishu.kernelsu.ui.screen.cardkey

/**
 * XhovoSU 卡密的验签公钥。
 *
 * ECDSA P-256 公钥，X.509 SubjectPublicKeyInfo DER 的 base64。私钥只存在于签发端
 * （tools/xhovosu-cardkey 与 manager/activation-issuer），管理器里只有公钥，
 * 所以即使管理器被反编译也签不出能用的卡密。
 *
 * 这里放的是随仓库发布的开发密钥对；正式构建时 CI 会用
 * NIGHT_LICENSE_PUBKEY_DER_B64 覆盖本文件。
 */
internal object CardKeyKeys {
    const val PUBLIC_KEY_DER_BASE64: String =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEVkOL9xug7xPW/j/3UPYq39DSlW7nfzNCipBz/8XwZL4YvgObuKFIKCa54K2RyZIQqBMzpvJdHCPiExi3H5EYUA=="
}
