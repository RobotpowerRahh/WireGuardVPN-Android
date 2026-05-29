import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.barsam.wireguardvpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.barsam.WireGuardVPN"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"

        // VPN credentials from local.properties — never commit these
        val props = Properties()
        val propsFile = rootProject.file("local.properties")
        if (propsFile.exists()) props.load(propsFile.inputStream())
        buildConfigField("String", "VLESS_UUID", "\"${props.getProperty("VLESS_UUID", "")}\"")
        buildConfigField("String", "WARP_VLESS_UUID", "\"${props.getProperty("WARP_VLESS_UUID", "")}\"")
        buildConfigField("String", "REALITY_PUBLIC_KEY", "\"${props.getProperty("REALITY_PUBLIC_KEY", "")}\"")
        buildConfigField("String", "REALITY_SHORT_ID", "\"${props.getProperty("REALITY_SHORT_ID", "")}\"")
        buildConfigField("String", "TLS_SNI", "\"${props.getProperty("TLS_SNI", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.4")

    // Encrypted storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
