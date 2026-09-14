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

The manager signing certificate is generated per workflow run and its hash is compiled into the KMI modules from the same run. Always install a manager APK and KMI module downloaded from the same Actions run.
