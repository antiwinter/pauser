plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":content:contract"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
}
