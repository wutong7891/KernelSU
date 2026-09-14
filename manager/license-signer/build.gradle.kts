plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.lsplugin.apksign)
}

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

android {
    namespace = "com.jinfuwei.luoyu.signer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jinfuwei.luoyu.signer"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        val privateKey = providers.gradleProperty("YIPASU_LICENSE_PRIVATE_KEY").orNull.orEmpty()
        buildConfigField("String", "LICENSE_PRIVATE_KEY", "\"$privateKey\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
}

base { archivesName.set("YipaSU-License-Signer") }
