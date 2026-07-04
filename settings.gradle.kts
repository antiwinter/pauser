pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Insomnia"
include(":app")
include(":core:form")
include(":core:form:contract")
include(":core:osd")
include(":content:contract")
include(":content:providers:smb")
include(":content:providers:js")
include(":content:ui")
include(":proxy:contract")
include(":proxy:providers:http")
include(":proxy:providers:clash")
include(":proxy:ui")
include(":player")
include(":storage")
include(":server")
include(":image-viewer")
include(":gen-art")
