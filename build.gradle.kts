// AGP 9 ships Kotlin 2.2.10. A newer plugin on the buildscript classpath
// replaces it so the Compose compiler plugin matches the Kotlin version.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
