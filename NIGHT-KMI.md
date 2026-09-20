# Night exclusive KMI

Night KMI modules are built for the Night manager signing certificate only.

- Certificate DER size: `0x052b`
- Certificate SHA-256: `2de33664e55fa2427469d1c84c530c3b582538763d2361b909593a50b81ad509`
- Supported ARM64 KMI: Android 12–17 (`5.10`, `5.15`, `6.1`, `6.6`, `6.12`, `6.18`)
- Supported x86_64 KMI: the same build matrix, distributed separately for compatible targets

The Night manager APK is intentionally KMI-free. The modules are distributed as separate artifacts and are embedded only in the dedicated offline patcher tools. This keeps the root manager smaller and makes the module/signing identity explicit.

Back up the original `boot.img` or `init_boot.img` before patching. The offline tools create a new image and never flash a device.
