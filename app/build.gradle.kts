plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.disastermesh"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.disastermesh"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
}