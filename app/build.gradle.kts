import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val voiceMobile = providers.gradleProperty("voiceMobile").orNull == "true"
val mobileKey = rootProject.layout.buildDirectory.file("voice-mobile-public-test.jks").get().asFile
if (voiceMobile) {
    mobileKey.parentFile.mkdirs()
    mobileKey.writeBytes(Base64.getMimeDecoder().decode(rootProject.file("scripts/voice-mobile-test-key.base64").readText()))
}

android {
    namespace = "life.mosaic.fit"
    compileSdk = 35

    defaultConfig {
        applicationId = "life.mosaic.fit"
        minSdk = 29
        if (voiceMobile) ndk { abiFilters += "arm64-v8a" }
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1-cpu-diagnostic"
        manifestPlaceholders["voicePocLabel"] = if (voiceMobile) "Mosaic Voice Mobile" else "Mosaic Voice POC"
    }

    buildFeatures { compose = true }

    // This experimental branch installs alongside Mosaic, avoiding any Room downgrade
    // if the device already runs the unmerged v4 revision-persistence branch.
    if (voiceMobile) {
        signingConfigs.create("voiceMobilePublicTest") {
            storeFile = mobileKey
            storePassword = "android"
            keyAlias = "voice-mobile-test"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = if (voiceMobile) ".voicepoc.mobile" else ".voicepoc"
            if (voiceMobile) signingConfig = signingConfigs.getByName("voiceMobilePublicTest")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    debugImplementation(project(":poc:speech"))
    debugImplementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    implementation(project(":core:model"))
    implementation(project(":core:settings"))
    implementation(project(":feature:fit"))
    implementation(project(":feature:photos"))
    implementation(project(":feature:training"))
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
