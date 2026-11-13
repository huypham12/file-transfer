import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.example.file_tranfer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.file_tranfer"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["fileProviderAuthority"] = "${applicationId}.fileprovider"
        buildConfigField("String", "FILE_PROVIDER_AUTHORITY", "\"${applicationId}.fileprovider\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = (project.findProperty("KEYSTORE_FILE") as? String)
                ?: System.getenv("KEYSTORE_FILE")
            val keystoreFile = if (keystorePath != null) file(keystorePath) else null

            if (keystoreFile != null && keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = (project.findProperty("KEYSTORE_PASSWORD") as? String)
                    ?: System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = (project.findProperty("KEY_ALIAS") as? String)
                    ?: System.getenv("KEY_ALIAS") ?: ""
                keyPassword = (project.findProperty("KEY_PASSWORD") as? String)
                    ?: System.getenv("KEY_PASSWORD") ?: ""
            } else {
                logger.warn("==========================================================================")
                logger.warn("[WARNING] Không tìm thấy KEYSTORE_FILE. Tự động fallback sang Debug Keystore.")
                logger.warn("Bản APK này chỉ dùng cho kiểm thử nội bộ/CI. KHÔNG tải lên Google Play Store!")
                logger.warn("==========================================================================")
                // Tự động fallback an toàn ký bằng Debug Keystore trên môi trường CI/CD
                val debugSigning = getByName("debug")
                storeFile = debugSigning.storeFile
                storePassword = debugSigning.storePassword
                keyAlias = debugSigning.keyAlias
                keyPassword = debugSigning.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true

            val host = localProperties.getProperty("RELAY_HOST")?.trim()?.ifBlank { null } ?: "10.0.2.2"
            val relayPort = localProperties.getProperty("RELAY_PORT")?.trim()?.toIntOrNull() ?: 8080
            val lanPort = localProperties.getProperty("LAN_PORT")?.trim()?.toIntOrNull() ?: 8889

            buildConfigField("String", "DEFAULT_RELAY_HOST", "\"$host\"")
            buildConfigField("int", "DEFAULT_RELAY_PORT", "$relayPort")
            buildConfigField("int", "DEFAULT_LAN_PORT", "$lanPort")
            buildConfigField("String", "RELAY_URL", "\"ws://$host:$relayPort/v1/stream\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")

            buildConfigField("String", "DEFAULT_RELAY_HOST", "\"relay.filetransfer.app\"")
            buildConfigField("int", "DEFAULT_RELAY_PORT", "443")
            buildConfigField("int", "DEFAULT_LAN_PORT", "8889")
            buildConfigField("String", "RELAY_URL", "\"wss://relay.filetransfer.app/v1/stream\"")

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.okhttp)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}