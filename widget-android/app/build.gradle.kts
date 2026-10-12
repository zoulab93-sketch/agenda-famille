plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Numéro de version = numéro de fabrication sur GitHub : chaque nouvelle version s'installe par-dessus l'ancienne
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "fr.earthshade.widget"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.earthshade.widget"
        minSdk = 29
        targetSdk = 34
        versionCode = runNumber
        versionName = "4.0.$runNumber"
    }

    // Toujours la même signature (debug.keystore du dépôt) : sinon Android refuserait les mises à jour
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.compose.runtime:runtime:1.6.8")
    implementation("androidx.compose.ui:ui-graphics:1.6.8")
    implementation("androidx.compose.ui:ui-unit:1.6.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
