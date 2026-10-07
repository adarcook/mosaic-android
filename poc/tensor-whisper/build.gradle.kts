plugins { id("com.android.application") version "8.7.3" }

android {
    namespace = "life.mosaic.tensorwhisper"
    compileSdk = 35
    defaultConfig {
        applicationId = "life.mosaic.tensorwhisper"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "0.3-ivrit-hybrid-asr-gate"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = true }
    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation(project(":speech"))
    implementation("com.google.ai.edge.litert:litert:2.1.6") { isTransitive = false }
    implementation("com.google.ai.edge.litert:litert-api:2.1.6") { isTransitive = false }
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
}
