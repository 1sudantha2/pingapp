plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pingbooster.app"
    compileSdk = 35

    /**
     * Both variants are signed with the keystore in /keystore so that every APK built by
     * GitHub Actions can be installed as an update over the previous one. The keystore is a
     * public development key: never use it to publish on an app store.
     */
    val ciKeystore = rootProject.file("keystore/pingbooster-ci.p12")

    defaultConfig {
        applicationId = "com.pingbooster.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"

        // Only ship the locales the app actually uses -> smaller APK, fewer resources loaded.
        resourceConfigurations += listOf("en")
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (ciKeystore.exists()) {
            create("ci") {
                storeFile = ciKeystore
                storePassword = "pingbooster"
                keyAlias = "pingbooster"
                keyPassword = "pingbooster"
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (ciKeystore.exists()) signingConfig = signingConfigs.getByName("ci")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (ciKeystore.exists()) signingConfig = signingConfigs.getByName("ci")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
            "META-INF/*.version"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
