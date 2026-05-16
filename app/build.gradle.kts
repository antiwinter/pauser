import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

fun gitExec(vararg args: String): String =
    providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()

fun gitVersion(): String {
    val hash = gitExec("rev-parse", "--short=5", "HEAD").ifEmpty { "00000" }
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMdd"))
    val dirty = gitExec("status", "--porcelain").isNotEmpty()
    val tag = gitExec("describe", "--tags", "--exact-match", "HEAD")

    return buildString {
        if (tag.isNotEmpty()) append("$tag-")
        append("$hash-$date")
        if (dirty) append("-dirty")
    }
}

android {
    namespace = "com.opentune.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.opentune.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GIT_VERSION", "\"${gitVersion()}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "${rootDir}/release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "opentune"
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        debug {
            // Include all ABIs in debug so emulators work
            buildConfigField("String", "GIT_VERSION", "\"${gitVersion()}-debug\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk {
                abiFilters += setOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "${rootDir}/providers-ts/dist")
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (variant.buildType.name == "release") {
                output.outputFileName = "opentune-v${variant.versionName}.apk"
            }
        }
    }
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":providers:js"))

    implementation(project(":player"))
    implementation(project(":storage"))
    implementation(project(":providers:emby"))
    implementation(project(":providers:smb"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.coil.compose)
    implementation(libs.room.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    testImplementation("junit:junit:4.13.2")
}
