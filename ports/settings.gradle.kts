pluginManagement {
    repositories { maven("https://maven.fabricmc.net/");maven("https://maven.muon.rip/releases");gradlePluginPortal();mavenCentral() }
}
rootProject.name="GuardianPorts"
include("common","fabric","neoforge")
