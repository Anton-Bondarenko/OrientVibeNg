plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ru.bondarenko.orientvibe.ng"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.bondarenko.orientvibe.ng"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    aaptOptions {
        noCompress += "onnx"
    }
    lint {
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    // BOM for Compose
    implementation(platform(libs.androidx.compose.bom))

    // Compose UI
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Material3
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation.core)

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)

    // UI libraries
    implementation(libs.subsampling.scale.image.view)
    implementation(libs.androidx.material.icons.extended)

    // Permissions
    implementation(libs.accompanist.permissions)

    // ONNX Runtime
    implementation(libs.onnxruntime.android)

    // Other essentials
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.window:window:1.3.0")

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
