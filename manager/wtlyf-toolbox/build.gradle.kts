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
    namespace = "com.wutong.yingcang"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wutong.yingcang"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            vcsInfo.include = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

base { archivesName.set("wtlyf") }
