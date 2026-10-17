plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
}
android {
    namespace = "life.mosaic.zipformergate"
    compileSdk = 35
    defaultConfig {
        applicationId = "life.mosaic.zipformergate"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "0.4-zipformer-replay-diagnostic"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.useLegacyPackaging = true }
}
dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
}
