import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Read the terminal endpoint + device token from local.properties (gitignored) so the
// URL/token never hit git. Names match nothing-to-say + nothing-serious (.env
// TERMINALS_DEVICE_TOKEN) so the same values work across all three.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val terminalsBaseUrl: String = localProps.getProperty("TERMINALS_BASE_URL") ?: ""
val terminalsDeviceToken: String = localProps.getProperty("TERMINALS_DEVICE_TOKEN") ?: ""

android {
    namespace = "com.humanemagica.nothing.terminal"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.humanemagica.nothing.terminal"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Base URL of the stand-in / backend, e.g. http://192.168.1.10:8080. The
        // app derives REST (http) and per-session WS (ws) endpoints from it.
        buildConfigField("String", "TERMINALS_BASE_URL", "\"$terminalsBaseUrl\"")
        buildConfigField("String", "TERMINALS_DEVICE_TOKEN", "\"$terminalsDeviceToken\"")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.termux.terminal.view)

    testImplementation(libs.junit)
}
