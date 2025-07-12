plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose) // Essential for Jetpack Compose projects
}

android {
    namespace = "com.example.testing1"
    compileSdk = 36 // Compile against Android 14 (API 36)

    defaultConfig {
        applicationId = "com.example.testing1"
        minSdk = 29 // Minimum API level supported (Android 10)
        targetSdk = 35 // Target API level, should ideally match compileSdk for latest behavior
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Set to true for production builds to reduce app size
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 // Java source compatibility
        targetCompatibility = JavaVersion.VERSION_17 // Java target compatibility
    }

    // Modern way to configure Kotlin JVM target
    // The old 'kotlinOptions' block is deprecated and removed as per your original file.
    // The configuration is now handled in the tasks.withType block below.

    buildFeatures {
        compose = true // Enable Jetpack Compose
        viewBinding = false // Set to true if you mix Compose with XML layouts and need View Binding
    }

    composeOptions {
        // Specify the Kotlin Compiler Extension version compatible with your Compose BOM.
        // 1.5.10 is recommended for Compose BOM 2024.09.00.
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    // Exclude problematic files from packaging to avoid build issues
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

// Configure Kotlin compiler options for all Kotlin compile tasks
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) // Set JVM target to 17
        // Add other Kotlin compiler options here if necessary, e.g.:
        // freeCompilerArgs.add("-Xopt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    // --- Core AndroidX Dependencies ---
    // Core KTX for Kotlin extensions
    implementation(libs.androidx.core.ktx)
    // Lifecycle components for observing lifecycle-aware components
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Kotlin Coroutines Android specific utilities
    implementation(libs.kotlinx.coroutines.android)

    // --- Compose Dependencies ---
    // Import the Compose BOM (Bill of Materials) to manage Compose library versions consistently
    implementation(platform(libs.androidx.compose.bom))
    // Compose integration for Activity
    implementation(libs.androidx.activity.compose)
    // Foundation components for Compose UI
    implementation(libs.androidx.foundation)
    // Material 3 design system components
    implementation(libs.androidx.material3)
    // Core UI components for Compose
    implementation(libs.androidx.ui)
    // Graphics utilities for Compose
    implementation(libs.androidx.ui.graphics)
    // Tooling for Compose previews in Android Studio
    implementation(libs.androidx.ui.tooling.preview)
    // ViewModel integration for Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // --- Testing Dependencies ---
    // JUnit for unit tests
    testImplementation(libs.junit)
    // AndroidX JUnit for instrumented tests
    androidTestImplementation(libs.androidx.junit)
    // Espresso for UI testing
    androidTestImplementation(libs.androidx.espresso.core)
    // Import Compose BOM for Android instrumented tests
    androidTestImplementation(platform(libs.androidx.compose.bom))
    // Compose UI testing utilities for JUnit 4
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // --- Debug Tools ---
    // Compose UI tooling for debugging and inspection
    debugImplementation(libs.androidx.ui.tooling)
    // Compose UI test manifest for instrumented tests
    debugImplementation(libs.androidx.ui.test.manifest)
}
