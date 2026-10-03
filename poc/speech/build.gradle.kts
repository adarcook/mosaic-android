plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
val voiceVulkan = providers.gradleProperty("voiceVulkan").orNull == "true"

android {
    namespace = "life.mosaic.voice.runtime"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DGGML_VULKAN=${if (voiceVulkan) "ON" else "OFF"}") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
