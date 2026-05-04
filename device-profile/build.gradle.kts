plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.opentune.deviceprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":emby-api"))
    implementation(libs.androidx.core.ktx)
}
