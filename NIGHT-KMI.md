# Night exclusive KMI

Night KMI modules are built for the Night manager signing certificate only.

- Certificate DER size: `0x02ca`
- Certificate SHA-256: `606f3dc77238f37243cf57d2ecb6a6383cadfa48bb2f69dcd501435167c631be`
- Supported ARM64 KMI: eight Android 12–17 variants (`5.10`, `5.15`, `6.1`, `6.6`, `6.12`, `6.18`)
- Night builds the certificate as the primary manager identity, so the kernel no longer reports PR mode.
- Manager package binding: `com.Night.night`

The Night manager APK is intentionally KMI-free. The modules are distributed as separate artifacts and are embedded only in the dedicated offline patcher tools. This keeps the root manager smaller and makes the module/signing identity explicit.

Back up the original `boot.img` or `init_boot.img` before patching. The offline tools create a new image and never flash a device.
