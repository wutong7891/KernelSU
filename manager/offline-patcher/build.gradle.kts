plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.lsplugin.apksign)
}

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

android {
    namespace = "com.Night.night.patcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.Night.night.patcher.fixed"
        minSdk = 31
        targetSdk = 37
        versionCode = 4
        versionName = "1.3"
    }

    buildTypes {
        release { vcsInfo.include = false }
    }

    packaging { jniLibs.useLegacyPackaging = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

base { archivesName.set("Night-Offline-Patcher") }
