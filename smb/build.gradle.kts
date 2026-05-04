plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.opentune.smb"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.datasource)
    implementation(libs.media3.exoplayer)
    api(libs.smbj)
}
