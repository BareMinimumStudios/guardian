pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.muon.rip/releases")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "Guardian"

include("worldedit-adapter")

include("common")

include("neoforge")
