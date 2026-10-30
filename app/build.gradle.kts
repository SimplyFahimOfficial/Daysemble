plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "app.daysemble"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.daysemble"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes only from environment variables (CI secrets or your shell); no keys in the repo.
    // If any are missing, release builds are produced unsigned.
    val keystorePath = System.getenv("DAYSEMBLE_KEYSTORE_PATH")
    val releaseSigning = if (
        keystorePath != null && listOf("DAYSEMBLE_KEYSTORE_PASSWORD", "DAYSEMBLE_KEY_ALIAS", "DAYSEMBLE_KEY_PASSWORD").all { System.getenv(it) != null }
    ) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("DAYSEMBLE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("DAYSEMBLE_KEY_ALIAS")
            keyPassword = System.getenv("DAYSEMBLE_KEY_PASSWORD")
        }
    } else null

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            releaseSigning?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

tasks.withType<Test>().configureEach {
    // Optional override for environments with a read-only home directory (Robolectric caches SDK jars there).
    providers.gradleProperty("testUserHome").orNull?.let { systemProperty("user.home", it) }
    // Opt-in developer visual QA renders (not screenshots; see VisualAuditRender).
    providers.gradleProperty("daysemble.visualAudit").orNull?.let {
        systemProperty("daysemble.visualAudit", it)
        systemProperty("daysemble.visualAuditDir", layout.buildDirectory.dir("visual-audit").get().asFile.absolutePath)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.play.services.auth)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}

// Patched versions of vulnerable libraries reached only through build and test tooling: the
// Android Lint tool (androidLintTool), the Room annotation processor (ksp) and the unit-test
// libraries (testImplementation, e.g. Robolectric). The app's own runtime classpath doesn't contain
// them. Constraints only raise versions. See gradle/libs.versions.toml.
dependencies {
    constraints {
        for (configuration in listOf("androidLintTool", "ksp", "testImplementation")) {
            libs.bundles.tooling.security.get().forEach { lib ->
                add(configuration, lib) { because("Security fix for a build-tool dependency (Dependabot alert)") }
            }
        }
    }
}
