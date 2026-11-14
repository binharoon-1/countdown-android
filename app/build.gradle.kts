plugins {
    id("com.android.application") version "9.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}
android {
    namespace = "local.countdown"
    compileSdk = 37
    defaultConfig {
        applicationId = "local.countdown"
        minSdk = 24
        targetSdk = 37
    }
    buildFeatures { compose = true }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation:1.8.3")
}
