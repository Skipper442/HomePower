plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ua.andrii.homepower"
    compileSdk = 35
    defaultConfig {
        applicationId = "ua.andrii.homepower"
        minSdk = 26
        targetSdk = 35
        // Номер збірки GitHub Actions — щоб кожна нова версія мала більший versionCode
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.$build"
    }
    signingConfigs {
        // Постійний ключ підпису: без нього кожна збірка мала б інший підпис,
        // і Android відмовлявся б ставити оновлення поверх старої версії.
        create("release") {
            storeFile = file("release.p12")
            storeType = "PKCS12"
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "homepower"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
