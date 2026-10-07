pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "Guardian"

include("worldedit-adapter")

include("common")

include("neoforge")
