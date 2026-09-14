# YipaSU 3.2.5ksu

This fork is based on KernelSU v3.2.5 and keeps the kernel/userspace UAPI at version code 32525.

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
