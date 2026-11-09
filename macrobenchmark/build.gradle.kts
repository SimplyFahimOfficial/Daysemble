// Macrobenchmarks and the Baseline Profile generator for the app. They run on a device or emulator
// against the app's "benchmark" build type (release-like, signed with the debug key); see
// docs/PERFORMANCE.md. Nothing here is part of the shipped app.
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "app.daysemble.macrobenchmark"
    compileSdk = 37

    defaultConfig {
        // Baseline Profile generation needs API 28+.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"

    buildTypes {
        // Same name as the app's, so the test APK is built against it.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // The test module installs and drives the app itself.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

// Only the benchmark variant is meaningful; the other variants exist because AGP makes them.
androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}
