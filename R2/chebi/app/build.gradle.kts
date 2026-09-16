import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: credentials live OUTSIDE this source tree (never shipped in SOURCE.zip).
// R2/keystore/keystore.properties provides storeFile/storePassword/keyAlias/keyPassword.
val keystoreProps = Properties().apply {
    val f = rootProject.file("../keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.sdh.chebi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sdh.chebi"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
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

dependencies {
    implementation(files("libs/scpc-probe-starter-3.0.0-draft.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
