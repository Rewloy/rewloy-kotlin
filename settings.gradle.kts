pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "rewloy-kotlin"

include(":rewloy", ":rewloy-okhttp", ":rewloy-coroutines", ":generator")
