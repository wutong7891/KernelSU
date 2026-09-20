# Night exclusive KMI

Night KMI modules are built for the Night manager signing certificate only.

- Certificate DER size: `0x0429`
- Certificate SHA-256: `60669d4521187d0608fecbd8a8ba8add1b00b5dcf90c5d8b1b9dc268df5cb18f`
- Supported ARM64 KMI: Android 12–17 (`5.10`, `5.15`, `6.1`, `6.6`, `6.12`, `6.18`)
- Supported x86_64 KMI: the same build matrix, distributed separately for compatible targets

The Night manager APK is intentionally KMI-free. The modules are distributed as separate artifacts and are embedded only in the dedicated offline patcher tools. This keeps the root manager smaller and makes the module/signing identity explicit.

Back up the original `boot.img` or `init_boot.img` before patching. The offline tools create a new image and never flash a device.
