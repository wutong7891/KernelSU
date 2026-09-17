# YipaSU 3.2.5ksu

This fork is based on KernelSU v3.2.5 and keeps the kernel/userspace UAPI at version code 32525.

## UI refresh and Root terminal

- Uses the supplied “无痛定制” artwork as the adaptive and legacy launcher icon.
- Adds a dedicated **Terminal** page at the right side of the main bottom navigation, next to Settings.
- Adds a full-screen Root file browser with a cyber-style dark cyan interface that can navigate from `/`, jump to a typed path, select a file, confirm execution, and display stdout/stderr plus the exit code.
- Root paths are shell-escaped before browsing, moving, or execution.
- The Root terminal now uses an interactive stdin session: tap the terminal, type while the selected program is running, and press Enter to send input. The separate argument field has been removed.
- Moves the `/data/adb` shortcut and refresh action into the top-right overflow menu.
- Tapping a file opens centered Execute and Move-to-`/data/adb` actions; execution switches to a full-screen interactive terminal view.
- The confirmed one-click Root move action refuses to overwrite an existing destination file.

## Packages

- Manager display name: `YipaSU`
- Manager application ID: `com.jinfuwei.luoyu`
- License signer application ID: `com.jinfuwei.luoyu.signer`

## Activation

The manager displays its Android ID before initializing KernelSU. It accepts only an activation code signed for that exact ID with the matching ECDSA P-256 private key. The manager contains the public key only.

The signer APK accepts an Android ID copied from the manager and produces the matching activation code. Android 8 and later scope Android ID by application signing identity, so the signer intentionally does not use its own Android ID.

The signer private key is supplied at build time through the `YIPASU_LICENSE_PRIVATE_KEY` Gradle property. The GitHub workflow reads it from the Actions secret with the same name. Never commit the private key.

## GitHub Actions artifacts

Pushing to `main`, `dev`, or `ci` runs `.github/workflows/build-manager.yml` and produces:

- the signed/repacked YipaSU manager APK;
- the signed YipaSU license signer APK;
- `kernelsu.ko` artifacts for `android12-5.10`, `android13-5.10`, `android13-5.15`, `android14-5.15`, `android14-6.1`, `android15-6.6`, and `android16-6.12`;
- matching `ksud` and `ksuinit` artifacts.

The manager signing certificate is generated per workflow run. Its hash and the exact package name `com.jinfuwei.luoyu` replace the upstream manager identity in every KMI module, so the modules accept only the YipaSU manager from the same run. Always install a manager APK and KMI module downloaded from that same Actions run.

The Android `ksud` binaries repacked into the manager are built with `pack_lkm: false`, so the manager APK contains no built-in KMI modules. The separate `YipaSUOfflinePatcher.exe` is built on GitHub Actions and embeds the Windows patch engine plus the exclusive KMI set for fully offline boot/init_boot patching.

Additional GitHub-built tools are provided for the requested platform split:

- `YipaSULicenseSigner.exe`: a Windows offline Android ID activation-code signer with the private key injected from the encrypted Actions secret;
- `YipaSU-Offline-Patcher-release.apk`: an Android app with no Internet permission that contains the exclusive KMI files and patches a user-selected boot/init_boot image through Android's document picker.
