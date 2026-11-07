package me.weishu.kernelsu.ui.activation

/**
 * Night offline licence verification key.
 *
 * An ECDSA P-256 public key in X.509 SubjectPublicKeyInfo DER form, base64.
 * The private half never ships with the Manager: it lives only inside the
 * issuer tooling (manager/activation-issuer and tools/night-offline-issuer),
 * so a leaked or decompiled Manager build still cannot mint licences.
 *
 * The constant below is the development key pair that ships in the repository
 * for local testing and CI smoke runs. Production builds overwrite this file
 * from the NIGHT_LICENSE_PUBKEY_DER_B64 secret before Gradle runs.
 */
internal object NightLicenseKeys {
    const val PUBLIC_KEY_DER_BASE64: String =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEbozxq8eq8TuYxd1jR6Tp6sEvSXMRuRrWBb7eN6hkowf6AU198vz3VjQ2f/vo+tBgIj6UiYmpCAVzE5wrDvRDjA=="
}
