plugins { id("com.android.application") version "8.7.3" }
android {
    namespace = "life.mosaic.tensorllm"
    compileSdk = 35
    defaultConfig {
        applicationId = "life.mosaic.tensorllm"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-gemma4-g5"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = true }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
