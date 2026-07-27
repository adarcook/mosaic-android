plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-parcelize")
}

val samsungHealthAar = file("libs/samsung-health-data-api-1.1.0.aar")
val samsungHealthEnabled = samsungHealthAar.exists()

android {
    namespace = "life.mosaic.feature.training"
    compileSdk = 35

    defaultConfig { minSdk = 29 }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes.configureEach {
        buildConfigField("boolean", "SAMSUNG_HEALTH_SDK_AVAILABLE", samsungHealthEnabled.toString())
    }

    sourceSets.getByName("main").java.srcDir(
        if (samsungHealthEnabled) "src/samsungHealth/java" else "src/noSamsungHealth/java"
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha12")

    if (samsungHealthEnabled) {
        implementation(files(samsungHealthAar))
        implementation("com.google.code.gson:gson:2.13.2")
    }
}
