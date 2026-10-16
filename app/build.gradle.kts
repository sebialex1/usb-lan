import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing. Nothing secret is committed. Sources, in order:
//  1. Environment (CI): USBLAN_KEYSTORE_BASE64 (base64 keystore),
//     USBLAN_KEYSTORE_PASSWORD, USBLAN_KEY_ALIAS, USBLAN_KEY_PASSWORD.
//  2. Local untracked keystore/keystore.properties (storeFile relative to
//     keystore/, storePassword, keyAlias, keyPassword).
//  3. Neither: the release build is left unsigned.
data class ReleaseSigning(val store: File, val storePassword: String, val alias: String, val keyPassword: String)

val releaseSigning: ReleaseSigning? = run {
    val b64 = System.getenv("USBLAN_KEYSTORE_BASE64")?.trim().orEmpty()
    if (b64.isNotEmpty()) {
        val storePw = System.getenv("USBLAN_KEYSTORE_PASSWORD").orEmpty()
        val alias = System.getenv("USBLAN_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "usblan"
        val keyPw = System.getenv("USBLAN_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: storePw
        val out = layout.buildDirectory.file("signing/release.keystore").get().asFile
        out.parentFile.mkdirs()
        out.writeBytes(Base64.getMimeDecoder().decode(b64))
        return@run ReleaseSigning(out, storePw, alias, keyPw)
    }
    val keystoreDir = rootProject.file("keystore")
    val propsFile = File(keystoreDir, "keystore.properties")
    if (propsFile.isFile) {
        val p = Properties().apply { propsFile.inputStream().use { load(it) } }
        val storePw = p.getProperty("storePassword").orEmpty()
        return@run ReleaseSigning(
            File(keystoreDir, p.getProperty("storeFile", "usblan-release.keystore")),
            storePw,
            p.getProperty("keyAlias", "usblan"),
            p.getProperty("keyPassword") ?: storePw,
        )
    }
    logger.warn("USB LAN: no release signing material found; release APK will be unsigned.")
    null
}

android {
    namespace = "com.usblan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.usblan"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        // CI passes -PversionNameOverride=<tag without "v"> so the installed
        // versionName always matches the release tag the in-app updater sees.
        versionName = (findProperty("versionNameOverride") as String?)?.takeIf { it.isNotBlank() } ?: "0.3.0"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseSigning.store
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.alias
                keyPassword = releaseSigning.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
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
