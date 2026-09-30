plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNo = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.example.tvremote"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.tvremote"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNo
        versionName = "build $buildNo"
    }
    sourceSets.getByName("main").java.srcDir("../shared")
    val ks = rootProject.file("release.keystore")
    if (ks.exists()) {
        signingConfigs {
            create("shared") {
                storeFile = ks
                storePassword = "tvdash123"
                keyAlias = "tvdash"
                keyPassword = "tvdash123"
            }
        }
        buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("shared") } }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
