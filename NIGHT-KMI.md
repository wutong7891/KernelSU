# Night exclusive KMI v2

Night KMI modules are built for the Night manager signing certificate only.

Night is an exclusive derivative created by 无痛 for personal hobby development and remains transparently based on KernelSU. Upstream copyright and GPL licensing are retained.

- Certificate identity is derived automatically from the `NIGHT_KEYSTORE` used to sign the published manager APK.
- The build manifest records the exact DER size and SHA-256 for every KMI release, preventing stale values after a signing-key rotation.
- Supported ARM64 KMI: eight Android 12–17 variants (`5.10`, `5.15`, `6.1`, `6.6`, `6.12`, `6.18`)
- Night builds the certificate as the primary manager identity, so the kernel no longer reports PR mode.
- Manager package binding: `com.Night.night`
- Embedded module identity: `Night-KMI-v2-<android-kernel-kmi>`
- Module author: `无痛`
- Every build publishes a consolidated `Night-exclusive-KMI-v2` artifact with all eight ARM64 modules and a SHA-256 manifest.

The Night manager APK is intentionally KMI-free. The modules are distributed as separate artifacts and are embedded only in the dedicated offline patcher tools. This keeps the root manager smaller and makes the module/signing identity explicit.

Back up the original `boot.img` or `init_boot.img` before patching. The offline tools create a new image and never flash a device.
