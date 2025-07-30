plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "co.uk.doverguitarteacher.superstepstracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "co.uk.doverguitarteacher.superstepstracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // Do NOT set composeOptions.kotlinCompilerExtensionVersion with Kotlin 2.0+ plugin.

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3:1.2.1")

    // >>> Material Icons (needed for Icons.Default.BarChart, DirectionsRun, etc.)
    implementation("androidx.compose.material:material-icons-extended")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Fused Location (you already had this)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Core KTX
    implementation("androidx.core:core-ktx:1.13.1")

    // >>> DataStore Preferences (needed for preferencesDataStore, edit, *PreferencesKey)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // (Optional but harmless) Material Components if your XML theme references it
    implementation("com.google.android.material:material:1.12.0")
}
