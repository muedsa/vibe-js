pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vibe-js"
include(":vibe-js-core")
include(":vibe-js-host")
include(":vibe-js-conformance")
project(":vibe-js-host").projectDir = file("vibe-js-host")
project(":vibe-js-conformance").projectDir = file("vibe-js-conformance")
