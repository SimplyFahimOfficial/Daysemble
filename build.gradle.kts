// Patched versions of vulnerable libraries that the Gradle plugins (AGP, KSP, Room, Kotlin) pull
// onto the build classpath. Constraints only raise versions. See gradle/libs.versions.toml.
buildscript {
    dependencies {
        constraints {
            libs.bundles.tooling.security.get().forEach { lib ->
                classpath(lib) { because("Security fix for a build-tool dependency (Dependabot alert)") }
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.cyclonedx) apply false
}
