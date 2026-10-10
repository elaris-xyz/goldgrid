import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.vamahan.dailydraw"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vamahan.dailydraw"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.2"
        // A keyed RPC (Helius) lives in local.properties as rpc.url, never in the repo;
        // without one the app falls back to the public devnet endpoint.
        buildConfigField("String", "RPC_URL", "\"${localRpcUrl()}\"")
    }

    // The release key and its passwords live in local.properties and keys/, both
    // git-ignored: a build without them is simply unsigned, never signed with a
    // key someone could have read from the repo.
    val release = localProps()
    signingConfigs {
        if (release.getProperty("release.storeFile") != null) {
            create("release") {
                storeFile = file(release.getProperty("release.storeFile"))
                storePassword = release.getProperty("release.storePassword")
                keyAlias = release.getProperty("release.keyAlias")
                keyPassword = release.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.mwa.clientlib.ktx)
    implementation(libs.web3.solana)
    implementation(libs.rpc.core)
    implementation(libs.multimult)
    testImplementation(libs.junit)
}

fun localProps(): Properties {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    return props
}

fun localRpcUrl(): String = localProps().getProperty("rpc.url") ?: "https://api.devnet.solana.com"
