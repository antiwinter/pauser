plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.insomnia.provider.js"
    compileSdk = 35
    ndkVersion = "30.0.14904198"
    defaultConfig {
        minSdk = 21
        externalNativeBuild {
            cmake {
                cppFlags("")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":content:contract"))
    implementation(project(":player"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    implementation(libs.okhttp)
    implementation(libs.coil.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.startup:startup-runtime:1.2.0")
    testImplementation("junit:junit:4.13.2")
}