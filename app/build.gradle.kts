import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing material lives in <repo>/keystore (committed on purpose,
// see README). storeFile in the properties is relative to that directory.
val keystoreDir = rootProject.file("keystore")
val keystoreProps = Properties().apply {
    val f = File(keystoreDir, "keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "com.usblan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.usblan"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        // CI passes -PversionNameOverride=<tag without "v"> so the installed
        // versionName always matches the release tag the in-app updater sees.
        versionName = (findProperty("versionNameOverride") as String?)?.takeIf { it.isNotBlank() } ?: "0.2.0"
    }

    signingConfigs {
        create("release") {
            storeFile = File(keystoreDir, keystoreProps.getProperty("storeFile", "usblan-release.keystore"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
