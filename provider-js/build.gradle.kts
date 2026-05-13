plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.opentune.provider.js"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        externalNativeBuild {
            cmake {
                cppFlags("")
                arguments("-DANDROID_STL=c_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../app/src/main/jni/CMakeLists.txt")
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
    api(project(":provider-api"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
