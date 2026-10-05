plugins { id("com.android.application") version "8.7.3" }

android {
    namespace = "life.mosaic.tensorprobe"
    compileSdk = 35
    defaultConfig {
        applicationId = "life.mosaic.tensorprobe"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-tensor-probe"
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
    // This Java-only probe uses synchronous local CompiledModel APIs. It does
    // not use the separate lifecycle/Play delivery/coroutines model providers.
    implementation("com.google.ai.edge.litert:litert:2.1.6") { isTransitive = false }
    implementation("com.google.ai.edge.litert:litert-api:2.1.6") { isTransitive = false }
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
}
