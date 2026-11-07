plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["androidMinSdkVersion"] = 31
extra["androidTargetSdkVersion"] = 37
extra["androidCompileSdkVersion"] = 37
extra["androidCompileSdkVersionMinor"] = 0
extra["androidBuildToolsVersion"] = "37.0.0"
extra["androidCompileNdkVersion"] = libs.versions.ndk.get()
extra["androidSourceCompatibility"] = JavaVersion.VERSION_21
extra["androidTargetCompatibility"] = JavaVersion.VERSION_21
extra["managerVersionCode"] = getVersionCode()
extra["managerVersionName"] = getVersionName()

/**
 * The manager compares its own version code with the kernel module's KSU_VERSION
 * to decide whether "a newer bundled LKM is available". KSU_VERSION is
 * 30000 + git commit count, so the manager has to use the very same number,
 * otherwise that prompt never goes away after installing the module.
 *
 * CI passes -PksuVersionCode=<same value> so both sides are guaranteed equal;
 * local builds fall back to counting commits the same way the kernel Kbuild does.
 */
fun getVersionCode(): Int {
    (findProperty("ksuVersionCode") as String?)?.toIntOrNull()?.let { return it }

    val commitCount = try {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.get().trim().toIntOrNull()
    } catch (_: Exception) {
        null
    }
    return 30000 + (commitCount ?: 1)
}

fun getVersionName(): String {
    return "1.0"
}
