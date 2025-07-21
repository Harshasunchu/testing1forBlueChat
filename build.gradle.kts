// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // This defines the Android Gradle Plugin for the entire project, using the version from the TOML file.
    alias(libs.plugins.android.application) apply false
    // This defines the Kotlin Android Plugin for the entire project, using the version from the TOML file.
    alias(libs.plugins.kotlin.android) apply false
}
