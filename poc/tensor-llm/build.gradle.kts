plugins { id("com.android.application") version "8.7.3" }
android {
    namespace = "life.mosaic.tensorllm"
    compileSdk = 35
    sourceSets.getByName("main").java.srcDir("../model-storage/src/main/java")
    sourceSets.getByName("main").assets.srcDir("../../scripts/voice_metadata")
    defaultConfig {
        applicationId = "life.mosaic.tensorllm"
        minSdk = 31
        targetSdk = 35
        versionCode = 21
        versionName = "0.23-personal-commands"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/libLiteRtDispatch_GoogleTensor.so"
    }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
