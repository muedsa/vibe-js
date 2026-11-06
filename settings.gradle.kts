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
