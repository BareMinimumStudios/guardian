plugins {
    kotlin("jvm") version "2.4.20" apply false
    id("net.fabricmc.fabric-loom") version "1.17.21" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.17.21" apply false
    id("net.neoforged.moddev") version "2.0.148" apply false
    id("dev.mixinmcp.decompile") version "1.5.0" apply false
}
val requestedTarget=providers.gradleProperty("guardianTarget").orElse("26.2").get()
val targetFile=file("targets/$requestedTarget.properties")
require(targetFile.isFile) { "No verified dependency profile for Minecraft $requestedTarget" }
val properties=java.util.Properties().apply { targetFile.inputStream().use { load(it) } }
val pins=properties.stringPropertyNames().associateWith { properties.getProperty(it) }
require(pins.getValue("minecraftVersion")==requestedTarget)
extra["guardianTargetPins"]=pins
fun sourceLineage(target:String,visiting:Set<String>):List<String> {
    require(target !in visiting) { "Cyclic source family at $target" }
    val profile=file("targets/$target.properties")
    require(profile.isFile) { "Missing source-family profile: $target" }
    val values=java.util.Properties().apply { profile.inputStream().use { load(it) } }
    val parent=values.getProperty("sourceFamily")
    return (if(parent==null)emptyList() else sourceLineage(parent,visiting+target))+target
}
extra["guardianSourceVersions"]=sourceLineage(requestedTarget,emptySet())

project(":common").layout.buildDirectory.set(layout.buildDirectory.dir("common"))
allprojects {
    group=providers.gradleProperty("mavenGroup").get()
    version=pins.getValue("modVersion")
    if(name!=rootProject.name && requestedTarget!="26.2")layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("$requestedTarget/$name"))
}
